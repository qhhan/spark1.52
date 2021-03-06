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

package org.apache.spark

/**
 * A client that communicates with the cluster manager to request or kill executors.
 * This is currently supported only in YARN mode.
  *与集群管理器通信以请求或杀死执行程序的客户端,目前仅在YARN模式下支持。
 */
private[spark] trait ExecutorAllocationClient {

  /**
   * Update the cluster manager on our scheduling needs. Three bits of information are included
   * to help it make decisions.
    * 根据我们的调度需求更新集群管理器,包括三位信息以帮助其作出决定。
   * @param numExecutors The total number of executors we'd like to have. The cluster manager
   *                     shouldn't kill any running executor to reach this number, but,
   *                     if all existing executors were to die, this is the number of executors
   *                     we'd want to be allocated.
    *                     我们想要的总executors执行数,集群管理器不应该杀死任何正在执行的执行者来达到这个数,
    *                     但是如果所有现有的杀亡执行者,这就是我们要分配的执行者的数量,
   * @param localityAwareTasks The number of tasks in all active stages that have a locality
   *                           preferences. This includes running, pending, and completed tasks.
    *                           所有活动阶段中具有地区偏好的任务数量,这包括运行,挂起和已完成的任务
   * @param hostToLocalTaskCount A map of hosts to the number of tasks from all active stages
   *                             that would like to like to run on that host.
   *                             This includes running, pending, and completed tasks.
    *                            主机映射到要在该主机上运行的所有活动阶段的任务数量,这包括运行,挂起和已完成的任务
   * @return whether the request is acknowledged by the cluster manager.该请求是否被集群管理器确认
   */
  private[spark] def requestTotalExecutors(
      numExecutors: Int,
      localityAwareTasks: Int,
      hostToLocalTaskCount: Map[String, Int]): Boolean

  /**
   * Request an additional number of executors from the cluster manager.
    * 从集群管理器请求另外数量的执行程序
   * @return whether the request is acknowledged by the cluster manager.
   */
  def requestExecutors(numAdditionalExecutors: Int): Boolean

  /**
   * Request that the cluster manager kill the specified executors.
    * 请求群集管理器杀死指定的执行程序
   * @return whether the request is acknowledged by the cluster manager.
   */
  def killExecutors(executorIds: Seq[String]): Boolean

  /**
   * Request that the cluster manager kill the specified executor.
    * 请求群集管理器杀死指定的执行程序
   * @return whether the request is acknowledged by the cluster manager.
   */
  def killExecutor(executorId: String): Boolean = killExecutors(Seq(executorId))
}
