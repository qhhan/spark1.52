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

package org.apache.spark.rdd

import java.util.concurrent.Semaphore

import scala.concurrent.{ Await, TimeoutException }
import scala.concurrent.duration.Duration
import scala.concurrent.ExecutionContext.Implicits.global

import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.Timeouts
import org.scalatest.time.SpanSugar._

import org.apache.spark.{ LocalSparkContext, SparkContext, SparkException, SparkFunSuite }
/**
 * 异步RDD
 */
class AsyncRDDActionsSuite extends SparkFunSuite with BeforeAndAfterAll with Timeouts {

  @transient private var sc: SparkContext = _

  override def beforeAll() {
    sc = new SparkContext("local[2]", "test")
  }

  override def afterAll() {
    LocalSparkContext.stop(sc)
    sc = null
  }

  lazy val zeroPartRdd = new EmptyRDD[Int](sc)

  test("countAsync") { //计数异步
    assert(zeroPartRdd.countAsync().get() === 0)
    assert(sc.parallelize(1 to 10000, 5).countAsync().get() === 10000)
  }

  test("collectAsync") { //收集异步
    assert(zeroPartRdd.collectAsync().get() === Seq.empty)

    val collected = sc.parallelize(1 to 1000, 3).collectAsync().get()
    assert(collected === (1 to 1000))
  }

  test("foreachAsync") { //迭代异步
    zeroPartRdd.foreachAsync(i => Unit).get()

    val accum = sc.accumulator(0)
    sc.parallelize(1 to 1000, 3).foreachAsync { i =>
      accum += 1
    }.get()
    assert(accum.value === 1000)
  }

  test("foreachPartitionAsync") { //异步迭代分区
    zeroPartRdd.foreachPartitionAsync(iter => Unit).get()

    val accum = sc.accumulator(0)
    sc.parallelize(1 to 1000, 9).foreachPartitionAsync { iter =>
      accum += 1
    }.get()
    assert(accum.value === 9)
  }

  test("takeAsync") { //
    def testTake(rdd: RDD[Int], input: Seq[Int], num: Int) {
      val expected = input.take(num)
      val saw = rdd.takeAsync(num).get()
      assert(saw == expected, "incorrect result for rdd with %d partitions (expected %s, saw %s)"
        .format(rdd.partitions.size, expected, saw))
    }
    val input = Range(1, 1000)

    var rdd = sc.parallelize(input, 1)
    for (num <- Seq(0, 1, 999, 1000)) {
      testTake(rdd, input, num)
    }

    rdd = sc.parallelize(input, 2)
    for (num <- Seq(0, 1, 3, 500, 501, 999, 1000)) {
      testTake(rdd, input, num)
    }

    rdd = sc.parallelize(input, 100)
    for (num <- Seq(0, 1, 500, 501, 999, 1000)) {
      testTake(rdd, input, num)
    }

    rdd = sc.parallelize(input, 1000)
    for (num <- Seq(0, 1, 3, 999, 1000)) {
      testTake(rdd, input, num)
    }
  }

  /**
   * Make sure onComplete, onSuccess, and onFailure are invoked correctly in the case
   * of a successful job execution.
   * 确保在成功执行作业的情况下正确调用onComplete,onSuccess和onFailure
   */
  test("async success handling") { //异步成功处理
    val f = sc.parallelize(1 to 10, 2).countAsync() //FutureAction[Long]

    // Use a semaphore to make sure onSuccess and onComplete's success path will be called.
    // If not, the test will hang.
    //使用信号量来确保onSuccess和onComplete的成功路径将被调用,如果没有,测试将挂起
    //如果没有，测试将挂起。
    val sem = new Semaphore(0)

    f.onComplete {
      case scala.util.Success(res) =>
        sem.release()
      case scala.util.Failure(e) =>
        info("Should not have reached this code path (onComplete matching Failure)")
        throw new Exception("Task should succeed")
    }
    f.onSuccess {
      case a: Any =>
        sem.release()
    }
    f.onFailure {
      case t =>
        info("Should not have reached this code path (onFailure)")
        throw new Exception("Task should succeed")
    }
    assert(f.get() === 10)

    failAfter(10 seconds) {
      sem.acquire(2)
    }
  }

  /**
   * Make sure onComplete, onSuccess, and onFailure are invoked correctly in the case
   * of a failed job execution.
    * 在执行失败的情况下,请确保onComplete,onSuccess和onFailure正确调用
   */
  test("async failure handling") { //异步故障处理
    val f = sc.parallelize(1 to 10, 2).map { i =>
      throw new Exception("intentional"); i
    }.countAsync()

    // Use a semaphore to make sure onFailure and onComplete's failure path will be called.
    // If not, the test will hang.
    //使用信号量来确保onFailure和onComplete的失败路径将被调用,如果没有，测试将挂起。
    val sem = new Semaphore(0)

    f.onComplete {
      case scala.util.Success(res) =>
        info("Should not have reached this code path (onComplete matching Success)")
        throw new Exception("Task should fail")
      case scala.util.Failure(e) =>
        sem.release()
    }
    f.onSuccess {
      case a: Any =>
        info("Should not have reached this code path (onSuccess)")
        throw new Exception("Task should fail")
    }
    f.onFailure {
      case t =>
        sem.release()
    }
    intercept[SparkException] {
      f.get()
    }

    failAfter(10 seconds) {
      sem.acquire(2)
    }
  }

  /**
   * Awaiting FutureAction results
   * 等待futureaction结果
   *
   */
  test("FutureAction result, infinite(无限) wait") {
    val f = sc.parallelize(1 to 100, 4)
      .countAsync()
    //Await.result或者Await.ready会导致当前线程被阻塞,并等待actor通过它的应答来完成Future
    assert(Await.result(f, Duration.Inf) === 100) //Await.result等待返回结果
  }
  //FutureAction结果,有限等待
  test("FutureAction result, finite wait") {
    val f = sc.parallelize(1 to 100, 4)
      .countAsync()
    //Await.result或者Await.ready会导致当前线程被阻塞,并等待actor通过它的应答来完成Future
    assert(Await.result(f, Duration(30, "seconds")) === 100)
  }
  //FutureAction结果超时
  test("FutureAction result, timeout") {
    val f = sc.parallelize(1 to 100, 4)
      .mapPartitions(itr => { Thread.sleep(20); itr })
      .countAsync()
    intercept[TimeoutException] {
      //Await.result或者Await.ready会导致当前线程被阻塞,并等待actor通过它的应答来完成Future
      Await.result(f, Duration(20, "milliseconds"))
    }
  }
}
