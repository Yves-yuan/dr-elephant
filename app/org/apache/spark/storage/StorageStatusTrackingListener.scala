/*
 * Copyright 2016 LinkedIn Corp.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package org.apache.spark.storage


import scala.collection.mutable

import org.apache.spark.annotation.DeveloperApi
import org.apache.spark.scheduler._


/**
 * :: DeveloperApi ::
 * A modified version of StorageStatusListener that tracks the peak memory usage during the entire application runtime.
 *
 * NOTICE: this class copies StorageStatusListener's codes instead of extending from it, because the methods that
 * require being overridden are all in private scope.
 */
@DeveloperApi
class StorageStatusTrackingListener extends SparkListener {
  // This maintains only blocks that are cached (i.e. storage level is not StorageLevel.NONE)
  private[storage] val executorIdToStorageStatus = mutable.Map[String, StorageStatus]()

  def storageStatusList = executorIdToStorageStatus.values.toSeq

  val executorIdToMaxUsedMem = mutable.Map[String, Long]()

  /** Update storage status list to reflect updated block statuses */
  private def updateStorageStatus(execId: String, updatedBlocks: Seq[(BlockId, BlockStatus)]): Unit = {
    executorIdToStorageStatus.get(execId).foreach { storageStatus =>
      updatedBlocks.foreach { case (blockId, updatedStatus) =>
        if (updatedStatus.storageLevel == StorageLevel.NONE) {
          storageStatus.removeBlock(blockId)
        } else {
          storageStatus.updateBlock(blockId, updatedStatus)
        }
      }
    }
    updateUsedMem()
  }

  /** Update storage status list to reflect the removal of an RDD from the cache */
  private def updateStorageStatus(unpersistedRDDId: Int): Unit = {
    storageStatusList.foreach { storageStatus =>
      storageStatus.rddBlocksById(unpersistedRDDId).foreach { case (blockId, _) =>
        storageStatus.removeBlock(blockId)
      }
    }
    updateUsedMem()
  }

  private def updateUsedMem(): Unit = {
    executorIdToStorageStatus.foreach { case (execId, storageStatus) =>
      val currentMemUsed = storageStatus.memUsed
      if (currentMemUsed > executorIdToMaxUsedMem.getOrElse(execId, 0L)) {
        executorIdToMaxUsedMem(execId) = currentMemUsed
      }
    }
  }

  override def onTaskEnd(taskEnd: SparkListenerTaskEnd): Unit = {
    synchronized {
      val info = taskEnd.taskInfo
      val metrics = taskEnd.taskMetrics
      if (info != null && metrics != null) {
        val updatedBlocks = metrics.updatedBlocks.getOrElse(Seq[(BlockId, BlockStatus)]())
        if (updatedBlocks.length > 0) {
          updateStorageStatus(info.executorId, updatedBlocks)
        }
      }
    }
  }

  override def onUnpersistRDD(unpersistRDD: SparkListenerUnpersistRDD): Unit = {
    synchronized {
      updateStorageStatus(unpersistRDD.rddId)
    }
  }

  override def onBlockManagerAdded(blockManagerAdded: SparkListenerBlockManagerAdded): Unit = {
    synchronized {
      val blockManagerId = blockManagerAdded.blockManagerId
      val executorId = blockManagerId.executorId
      val maxMem = blockManagerAdded.maxMem
      val storageStatus = new StorageStatus(blockManagerId, maxMem)
      executorIdToStorageStatus(executorId) = storageStatus
    }
  }

  override def onBlockManagerRemoved(blockManagerRemoved: SparkListenerBlockManagerRemoved): Unit = {
    synchronized {
      val executorId = blockManagerRemoved.blockManagerId.executorId
      executorIdToStorageStatus.remove(executorId)
    }
  }
}
