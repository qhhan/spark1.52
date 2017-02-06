/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.deploy.history

import java.io.{BufferedInputStream, FileNotFoundException, InputStream, IOException, OutputStream}
import java.util.concurrent.{ExecutorService, Executors, TimeUnit}
import java.util.zip.{ZipEntry, ZipOutputStream}

import scala.collection.mutable

import com.google.common.io.ByteStreams
import com.google.common.util.concurrent.{MoreExecutors, ThreadFactoryBuilder}
import org.apache.hadoop.fs.{FileStatus, FileSystem, Path}
import org.apache.hadoop.fs.permission.AccessControlException

import org.apache.spark.{Logging, SecurityManager, SparkConf, SparkException}
import org.apache.spark.deploy.SparkHadoopUtil
import org.apache.spark.io.CompressionCodec
import org.apache.spark.scheduler._
import org.apache.spark.ui.SparkUI
import org.apache.spark.util.{Clock, SystemClock, ThreadUtils, Utils}

/**
 * A class that provides application history from event logs stored in the file system.
 * 一个从存储在文件系统中的事件日志提供应用程序历史的类
 * This provider checks for new finished applications in the background periodically and
 * renders the history application UI by parsing the associated event logs.
 */
private[history] class FsHistoryProvider(conf: SparkConf, clock: Clock)
  extends ApplicationHistoryProvider with Logging {

  def this(conf: SparkConf) = {
    this(conf, new SystemClock())
  }

  import FsHistoryProvider._

  private val NOT_STARTED = "<Not Started>"

  // Interval between each check for event log updates
  //事件日志更新的每个检查之间的间隔
  private val UPDATE_INTERVAL_S = conf.getTimeAsSeconds("spark.history.fs.update.interval", "10s")

  // Interval between each cleaner checks for event logs to delete
  //每个清洁检查事件日志的间隔之间的间隔,以删除
  private val CLEAN_INTERVAL_S = conf.getTimeAsSeconds("spark.history.fs.cleaner.interval", "1d")

  private val logDir = conf.getOption("spark.history.fs.logDirectory")
    .map { d => Utils.resolveURI(d).toString }
    .getOrElse(DEFAULT_LOG_DIR)

  private val hadoopConf = SparkHadoopUtil.get.newConfiguration(conf)
  private val fs = Utils.getHadoopFileSystem(logDir, hadoopConf)

  // Used by check event thread and clean log thread.
  //通过使用检查事件线程和清理的日志线程
  // Scheduled thread pool size must be one, otherwise it will have concurrent issues about fs
  //调度的线程池大小必须为1,否则将有并发问题的
  // and applications between check task and clean task.
  //检查任务和清洁任务之间的应用
  private val pool = Executors.newScheduledThreadPool(1, new ThreadFactoryBuilder()
    .setNameFormat("spark-history-task-%d").setDaemon(true).build())

  // The modification time of the newest log detected during the last scan. This is used
  //在最后一次扫描过程中检测到的最新日志的修改时间,
  // to ignore logs that are older during subsequent scans, to avoid processing data that
  //这是用来忽略在随后的扫描中旧的日志,为了避免已经知道的处理数据
  // is already known.
  private var lastModifiedTime = -1L

  // Mapping of application IDs to their metadata, in descending end time order. Apps are inserted
  //将应用程序标识映射到它们的元数据,在结束时间递减排序(倒序),
  // into the map in order, so the LinkedHashMap maintains the correct ordering.
  //应用程序被插入到Map中,
  @volatile private var applications: mutable.LinkedHashMap[String, FsApplicationHistoryInfo]
    = new mutable.LinkedHashMap()

  // List of application logs to be deleted by event log cleaner.
  //通过日志清除事件删除的应用程序日志列表
  private var attemptsToClean = new mutable.ListBuffer[FsApplicationAttemptInfo]

  /**
   * Return a runnable that performs the given operation on the event logs.
   * 返回一个可运行的执行特定操作的事件日志
   * This operation is expected to be executed periodically.
   * 预计此操作将定期执行
   */
  private def getRunner(operateFun: () => Unit): Runnable = {
    new Runnable() {
      override def run(): Unit = Utils.tryOrExit {
        operateFun()
      }
    }
  }

  /**
   * An Executor to fetch and parse log files.
   * 一个获取和解析日志文件的执行器
   */
  private val replayExecutor: ExecutorService = {
    if (!conf.contains("spark.testing")) {
      ThreadUtils.newDaemonSingleThreadExecutor("log-replay-executor")
    } else {
      MoreExecutors.sameThreadExecutor()
    }
  }

  initialize()

  private def initialize(): Unit = {
    // Validate the log directory. 验证日志目录
    val path = new Path(logDir)
    if (!fs.exists(path)) {
      var msg = s"Log directory specified does not exist: $logDir."
      if (logDir == DEFAULT_LOG_DIR) {
        msg += " Did you configure the correct one through spark.history.fs.logDirectory?"
      }
      throw new IllegalArgumentException(msg)
    }
    if (!fs.getFileStatus(path).isDir) {
      throw new IllegalArgumentException(
        "Logging directory specified is not a directory: %s".format(logDir))
    }

    // Disable the background thread during tests.
    //在测试过程中禁用背景线程
    if (!conf.contains("spark.testing")) {
      // A task that periodically checks for event log updates on disk.
      //一个定期检查磁盘上的事件日志更新的任务
      pool.scheduleWithFixedDelay(getRunner(checkForLogs), 0, UPDATE_INTERVAL_S, TimeUnit.SECONDS)

      if (conf.getBoolean("spark.history.fs.cleaner.enabled", false)) {
        // A task that periodically cleans event logs on disk.
        //一个定期清理磁盘上的事件日志的任务
        pool.scheduleWithFixedDelay(getRunner(cleanLogs), 0, CLEAN_INTERVAL_S, TimeUnit.SECONDS)
      }
    }
  }

  override def getListing(): Iterable[FsApplicationHistoryInfo] = applications.values

  override def getAppUI(appId: String, attemptId: Option[String]): Option[SparkUI] = {
    try {
      applications.get(appId).flatMap { appInfo =>
        appInfo.attempts.find(_.attemptId == attemptId).flatMap { attempt =>
          val replayBus = new ReplayListenerBus()
          val ui = {
            val conf = this.conf.clone()
            val appSecManager = new SecurityManager(conf)
            SparkUI.createHistoryUI(conf, replayBus, appSecManager, appId,
              HistoryServer.getAttemptURI(appId, attempt.attemptId), attempt.startTime)
            // Do not call ui.bind() to avoid creating a new server for each application
            //不调用UI.bind,为了避免每个应用的一个新的服务器
          }
          val appListener = new ApplicationEventListener()
          replayBus.addListener(appListener)
          val appInfo = replay(fs.getFileStatus(new Path(logDir, attempt.logPath)), replayBus)
          appInfo.map { info =>
            ui.setAppName(s"${info.name} ($appId)")

            val uiAclsEnabled = conf.getBoolean("spark.history.ui.acls.enable", false)
            ui.getSecurityManager.setAcls(uiAclsEnabled)
            // make sure to set admin acls before view acls so they are properly picked up
            ui.getSecurityManager.setAdminAcls(appListener.adminAcls.getOrElse(""))
            ui.getSecurityManager.setViewAcls(attempt.sparkUser,
              appListener.viewAcls.getOrElse(""))
            ui
          }
        }
      }
    } catch {
      case e: FileNotFoundException => None
    }
  }

  override def getConfig(): Map[String, String] = Map("Event log directory" -> logDir.toString)

  /**
   * Builds the application list based on the current contents of the log directory.
   * 根据日志目录的当前内容构建应用程序列表
   * Tries to reuse as much of the data already in memory as possible, by not reading
   * 尝试重用尽可能多的数据存在内存中,
   * applications that haven't been updated since last time the logs were checked.
   * 通过读取不从上次检查日志以来未更新的应用程序
   */
  private[history] def checkForLogs(): Unit = {
    try {
      val statusList = Option(fs.listStatus(new Path(logDir))).map(_.toSeq)
        .getOrElse(Seq[FileStatus]())
      var newLastModifiedTime = lastModifiedTime
      val logInfos: Seq[FileStatus] = statusList
        .filter { entry =>
          try {
            getModificationTime(entry).map { time =>
              newLastModifiedTime = math.max(newLastModifiedTime, time)
              time >= lastModifiedTime
            }.getOrElse(false)
          } catch {
            case e: AccessControlException =>
              // Do not use "logInfo" since these messages can get pretty noisy if printed on
              // every poll.
              logDebug(s"No permission to read $entry, ignoring.")
              false
          }
        }
        .flatMap { entry => Some(entry) }
        .sortWith { case (entry1, entry2) =>
          val mod1 = getModificationTime(entry1).getOrElse(-1L)
          val mod2 = getModificationTime(entry2).getOrElse(-1L)
          mod1 >= mod2
      }

      logInfos.grouped(20)
        .map { batch =>
          replayExecutor.submit(new Runnable {
            override def run(): Unit = mergeApplicationListing(batch)
          })
        }
        .foreach { task =>
          try {
            // Wait for all tasks to finish. This makes sure that checkForLogs
            //等待所有的任务完成,这确保了checkforlogs
            // is not scheduled again while some tasks are already running in
            //这确保了checkforlogs不定又有些任务已经在replayexecutor运行
            // the replayExecutor.
            task.get()
          } catch {
            case e: InterruptedException =>
              throw e
            case e: Exception =>
              logError("Exception while merging application listings", e)
          }
        }

      lastModifiedTime = newLastModifiedTime
    } catch {
      case e: Exception => logError("Exception in checking for event log updates", e)
    }
  }

  override def writeEventLogs(
      appId: String,
      attemptId: Option[String],
      zipStream: ZipOutputStream): Unit = {

    /**
     * This method compresses the files passed in, and writes the compressed data out into the
     * 该方法通过在文件压缩,并将压缩后的数据到输出流中传递
     * [[OutputStream]] passed in. Each file is written as a new [[ZipEntry]] with its name being
     * 每个文件是作为一个新的[ZipEntry]的名字正在被压缩的文件的名称
     * the name of the file being compressed.
     */
    def zipFileToStream(file: Path, entryName: String, outputStream: ZipOutputStream): Unit = {
      val fs = FileSystem.get(hadoopConf)
      val inputStream = fs.open(file, 1 * 1024 * 1024) // 1MB Buffer
      try {
        outputStream.putNextEntry(new ZipEntry(entryName))
        ByteStreams.copy(inputStream, outputStream)
        outputStream.closeEntry()
      } finally {
        inputStream.close()
      }
    }

    applications.get(appId) match {
      case Some(appInfo) =>
        try {
          // If no attempt is specified, or there is no attemptId for attempts, return all attempts
          //如果没有指定,或是没有attemptid尝试,返回所有的尝试
          appInfo.attempts.filter { attempt =>
            attempt.attemptId.isEmpty || attemptId.isEmpty || attempt.attemptId.get == attemptId.get
          }.foreach { attempt =>
            val logPath = new Path(logDir, attempt.logPath)
            // If this is a legacy directory, then add the directory to the zipStream and add
            //如果这是一个传统的目录,然后添加目录zipStream中并添加每文件到该目录
            // each file to that directory.
            if (isLegacyLogDirectory(fs.getFileStatus(logPath))) {
              val files = fs.listStatus(logPath)
              zipStream.putNextEntry(new ZipEntry(attempt.logPath + "/"))
              zipStream.closeEntry()
              files.foreach { file =>
                val path = file.getPath
                zipFileToStream(path, attempt.logPath + Path.SEPARATOR + path.getName, zipStream)
              }
            } else {
              zipFileToStream(new Path(logDir, attempt.logPath), attempt.logPath, zipStream)
            }
          }
        } finally {
          zipStream.close()
        }
      case None => throw new SparkException(s"Logs for $appId not found.")
    }
  }


  /**
   * Replay the log files in the list and merge the list of old applications with new ones
   * 重试列表中的日志文,,并将旧的应用程序的列表合并新的日志文件
   */
  private def mergeApplicationListing(logs: Seq[FileStatus]): Unit = {
    val newAttempts = logs.flatMap { fileStatus =>
      try {
        val bus = new ReplayListenerBus()
        val res = replay(fileStatus, bus)
        res match {
          case Some(r) => logDebug(s"Application log ${r.logPath} loaded successfully.")
          case None => logWarning(s"Failed to load application log ${fileStatus.getPath}. " +
            "The application may have not started.")
        }
        res
      } catch {
        case e: Exception =>
          logError(
            s"Exception encountered when attempting to load application log ${fileStatus.getPath}",
            e)
          None
      }
    }

    if (newAttempts.isEmpty) {
      return
    }

    // Build a map containing all apps that contain new attempts. The app information in this map
    //建立一个包含所有包含新尝试的应用程序的Map,在这个Map中的应用程序信息包含了新的应用程序尝试
    // contains both the new app attempt, and those that were already loaded in the existing apps
    //和那些已经加载存在的的应用程序Map,如果一个尝试已被更新,它将替换列表中的旧的尝试
    // map. If an attempt has been updated, it replaces the old attempt in the list.
    val newAppMap = new mutable.HashMap[String, FsApplicationHistoryInfo]()
    newAttempts.foreach { attempt =>
      val appInfo = newAppMap.get(attempt.appId)
        .orElse(applications.get(attempt.appId))
        .map { app =>
          val attempts =
            app.attempts.filter(_.attemptId != attempt.attemptId).toList ++ List(attempt)
          new FsApplicationHistoryInfo(attempt.appId, attempt.name,
            attempts.sortWith(compareAttemptInfo))
        }
        .getOrElse(new FsApplicationHistoryInfo(attempt.appId, attempt.name, List(attempt)))
      newAppMap(attempt.appId) = appInfo
    }

    // Merge the new app list with the existing one, maintaining the expected ordering (descending
    //合并新的应用程序列表与存在,维持预期的排序(结束时间倒序)
    // end time). Maintaining the order is important to avoid having to sort the list every time
    //维护排序是重要的,以避免每次请求有一个列表的排序的日志列表
    // there is a request for the log list.
    val newApps = newAppMap.values.toSeq.sortWith(compareAppInfo)
    val mergedApps = new mutable.LinkedHashMap[String, FsApplicationHistoryInfo]()
    def addIfAbsent(info: FsApplicationHistoryInfo): Unit = {
      if (!mergedApps.contains(info.id)) {
        mergedApps += (info.id -> info)
      }
    }

    val newIterator = newApps.iterator.buffered
    val oldIterator = applications.values.iterator.buffered
    while (newIterator.hasNext && oldIterator.hasNext) {
      if (newAppMap.contains(oldIterator.head.id)) {
        oldIterator.next()
      } else if (compareAppInfo(newIterator.head, oldIterator.head)) {
        addIfAbsent(newIterator.next())
      } else {
        addIfAbsent(oldIterator.next())
      }
    }
    newIterator.foreach(addIfAbsent)
    oldIterator.foreach(addIfAbsent)

    applications = mergedApps
  }

  /**
   * Delete event logs from the log directory according to the clean policy defined by the user.
   * 根据用户定义的清理策略从日志目录中删除事件日志
   */
  private[history] def cleanLogs(): Unit = {
    try {
      val maxAge = conf.getTimeAsSeconds("spark.history.fs.cleaner.maxAge", "7d") * 1000

      val now = clock.getTimeMillis()
      val appsToRetain = new mutable.LinkedHashMap[String, FsApplicationHistoryInfo]()

      def shouldClean(attempt: FsApplicationAttemptInfo): Boolean = {
        now - attempt.lastUpdated > maxAge && attempt.completed
      }

      // Scan all logs from the log directory.
      //扫描日志目录中的所有日志
      // Only completed applications older than the specified max age will be deleted.
      //仅删除大于指定的最大年龄的应用程序将被删除
      applications.values.foreach { app =>
        val (toClean, toRetain) = app.attempts.partition(shouldClean)
        attemptsToClean ++= toClean

        if (toClean.isEmpty) {
          appsToRetain += (app.id -> app)
        } else if (toRetain.nonEmpty) {
          appsToRetain += (app.id ->
            new FsApplicationHistoryInfo(app.id, app.name, toRetain.toList))
        }
      }

      applications = appsToRetain

      val leftToClean = new mutable.ListBuffer[FsApplicationAttemptInfo]
      attemptsToClean.foreach { attempt =>
        try {
          val path = new Path(logDir, attempt.logPath)
          if (fs.exists(path)) {
            fs.delete(path, true)
          }
        } catch {
          case e: AccessControlException =>
            logInfo(s"No permission to delete ${attempt.logPath}, ignoring.")
          case t: IOException =>
            logError(s"IOException in cleaning ${attempt.logPath}", t)
            leftToClean += attempt
        }
      }

      attemptsToClean = leftToClean
    } catch {
      case t: Exception => logError("Exception in cleaning logs", t)
    }
  }

  /**
   * Comparison function that defines the sort order for the application listing.
   * 定义应用程序列表排序顺序的比较函数
   *
   * @return Whether `i1` should precede `i2`.
   */
  private def compareAppInfo(
      i1: FsApplicationHistoryInfo,
      i2: FsApplicationHistoryInfo): Boolean = {
    val a1 = i1.attempts.head
    val a2 = i2.attempts.head
    if (a1.endTime != a2.endTime) a1.endTime >= a2.endTime else a1.startTime >= a2.startTime
  }

  /**
   * Comparison function that defines the sort order for application attempts within the same
   * 定义在相同应用程序中的应用程序尝试的排序顺序的比较函数
   * application. Order is: attempts are sorted by descending start time.
   * Most recent attempt state matches with current state of the app.
   *
   * Normally applications should have a single running attempt; but failure to call sc.stop()
   * may cause multiple running attempts to show up.
   *
   * @return Whether `a1` should precede `a2`.
   */
  private def compareAttemptInfo(
      a1: FsApplicationAttemptInfo,
      a2: FsApplicationAttemptInfo): Boolean = {
    a1.startTime >= a2.startTime
  }

  /**
   * Replays the events in the specified log file and returns information about the associated
   * application. Return `None` if the application ID cannot be located.
   */
  private def replay(
      eventLog: FileStatus,
      bus: ReplayListenerBus): Option[FsApplicationAttemptInfo] = {
    val logPath = eventLog.getPath()
    logInfo(s"Replaying log path: $logPath")
    val logInput =
      if (isLegacyLogDirectory(eventLog)) {
        openLegacyEventLog(logPath)
      } else {
        EventLoggingListener.openEventLog(logPath, fs)
      }
    try {
      val appListener = new ApplicationEventListener
      val appCompleted = isApplicationCompleted(eventLog)
      bus.addListener(appListener)
      bus.replay(logInput, logPath.toString, !appCompleted)

      // Without an app ID, new logs will render incorrectly in the listing page, so do not list or
      // try to show their UI. Some old versions of Spark generate logs without an app ID, so let
      // logs generated by those versions go through.
      if (appListener.appId.isDefined || !sparkVersionHasAppId(eventLog)) {
        Some(new FsApplicationAttemptInfo(
          logPath.getName(),
          appListener.appName.getOrElse(NOT_STARTED),
          appListener.appId.getOrElse(logPath.getName()),
          appListener.appAttemptId,
          appListener.startTime.getOrElse(-1L),
          appListener.endTime.getOrElse(-1L),
          getModificationTime(eventLog).get,
          appListener.sparkUser.getOrElse(NOT_STARTED),
          appCompleted))
      } else {
        None
      }
    } finally {
      logInput.close()
    }
  }

  /**
   * Loads a legacy log directory. This assumes that the log directory contains a single event
   * log file (along with other metadata files), which is the case for directories generated by
   * the code in previous releases.
   *
   * @return input stream that holds one JSON record per line.
   */
  private[history] def openLegacyEventLog(dir: Path): InputStream = {
    val children = fs.listStatus(dir)
    var eventLogPath: Path = null
    var codecName: Option[String] = None

    children.foreach { child =>
      child.getPath().getName() match {
        case name if name.startsWith(LOG_PREFIX) =>
          eventLogPath = child.getPath()
        case codec if codec.startsWith(COMPRESSION_CODEC_PREFIX) =>
          codecName = Some(codec.substring(COMPRESSION_CODEC_PREFIX.length()))
        case _ =>
      }
    }

    if (eventLogPath == null) {
      throw new IllegalArgumentException(s"$dir is not a Spark application log directory.")
    }

    val codec = try {
        codecName.map { c => CompressionCodec.createCodec(conf, c) }
      } catch {
        case e: Exception =>
          throw new IllegalArgumentException(s"Unknown compression codec $codecName.")
      }

    val in = new BufferedInputStream(fs.open(eventLogPath))
    codec.map(_.compressedInputStream(in)).getOrElse(in)
  }

  /**
   * Return whether the specified event log path contains a old directory-based event log.
   * Previously, the event log of an application comprises of multiple files in a directory.
   * As of Spark 1.3, these files are consolidated into a single one that replaces the directory.
   * See SPARK-2261 for more detail.
   */
  private def isLegacyLogDirectory(entry: FileStatus): Boolean = entry.isDir()

  /**
   * Returns the modification time of the given event log. If the status points at an empty
   * directory, `None` is returned, indicating that there isn't an event log at that location.
   */
  private def getModificationTime(fsEntry: FileStatus): Option[Long] = {
    if (isLegacyLogDirectory(fsEntry)) {
      val statusList = fs.listStatus(fsEntry.getPath)
      if (!statusList.isEmpty) Some(statusList.map(_.getModificationTime()).max) else None
    } else {
      Some(fsEntry.getModificationTime())
    }
  }

  /**
   * Return true when the application has completed.
   */
  private def isApplicationCompleted(entry: FileStatus): Boolean = {
    if (isLegacyLogDirectory(entry)) {
      fs.exists(new Path(entry.getPath(), APPLICATION_COMPLETE))
    } else {
      !entry.getPath().getName().endsWith(EventLoggingListener.IN_PROGRESS)
    }
  }

  /**
   * Returns whether the version of Spark that generated logs records app IDs. App IDs were added
   * in Spark 1.1.
   */
  private def sparkVersionHasAppId(entry: FileStatus): Boolean = {
    if (isLegacyLogDirectory(entry)) {
      fs.listStatus(entry.getPath())
        .find { status => status.getPath().getName().startsWith(SPARK_VERSION_PREFIX) }
        .map { status =>
          val version = status.getPath().getName().substring(SPARK_VERSION_PREFIX.length())
          version != "1.0" && version != "1.1"
        }
        .getOrElse(true)
    } else {
      true
    }
  }

}

private[history] object FsHistoryProvider {
  val DEFAULT_LOG_DIR = "file:/tmp/spark-events"

  // Constants used to parse Spark 1.0.0 log directories.
  val LOG_PREFIX = "EVENT_LOG_"
  val SPARK_VERSION_PREFIX = EventLoggingListener.SPARK_VERSION_KEY + "_"
  val COMPRESSION_CODEC_PREFIX = EventLoggingListener.COMPRESSION_CODEC_KEY + "_"
  val APPLICATION_COMPLETE = "APPLICATION_COMPLETE"
}

private class FsApplicationAttemptInfo(
    val logPath: String,
    val name: String,
    val appId: String,
    attemptId: Option[String],
    startTime: Long,
    endTime: Long,
    lastUpdated: Long,
    sparkUser: String,
    completed: Boolean = true)
  extends ApplicationAttemptInfo(
      attemptId, startTime, endTime, lastUpdated, sparkUser, completed)

private class FsApplicationHistoryInfo(
    id: String,
    override val name: String,
    override val attempts: List[FsApplicationAttemptInfo])
  extends ApplicationHistoryInfo(id, name, attempts)
