/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.samza.checkpoint

import java.util

import org.apache.samza.{Partition, SamzaException}
import org.apache.samza.config.MapConfig
import org.apache.samza.container.TaskName
import org.apache.samza.metadatastore.InMemoryMetadataStoreFactory
import org.apache.samza.startpoint.{StartpointManager, StartpointOldest, StartpointUpcoming}
import org.apache.samza.system.SystemStreamMetadata.{OffsetType, SystemStreamPartitionMetadata}
import org.apache.samza.system._
import org.apache.samza.util.NoOpMetricsRegistry
import org.junit.Assert._
import org.junit.Test
import org.mockito.Mockito.{mock, when}
import org.scalatest.Assertions.intercept

import scala.collection.JavaConverters._

class TestOffsetManager {
  @Test
  def testSystemShouldUseDefaults {
    val taskName = new TaskName("c")
    val systemStream = new SystemStream("test-system", "test-stream")
    val partition = new Partition(0)
    val systemStreamPartition = new SystemStreamPartition(systemStream, partition)
    val testStreamMetadata = new SystemStreamMetadata(systemStream.getStream, Map(partition -> new SystemStreamPartitionMetadata("0", "1", "2")).asJava)
    val systemStreamMetadata = Map(systemStream -> testStreamMetadata)
    val config = new MapConfig(Map("systems.test-system.samza.offset.default" -> "oldest").asJava)
    val offsetManager = OffsetManager(systemStreamMetadata, config)
    offsetManager.register(taskName, Set(systemStreamPartition))
    offsetManager.start
    assertFalse(offsetManager.getLastProcessedOffset(taskName, systemStreamPartition).isDefined)
    assertTrue(offsetManager.getStartingOffset(taskName, systemStreamPartition).isDefined)
    assertEquals("0", offsetManager.getStartingOffset(taskName, systemStreamPartition).get)
  }

  @Test
  def testShouldLoadFromAndSaveWithCheckpointManager {
    val taskName = new TaskName("c")
    val systemStream = new SystemStream("test-system", "test-stream")
    val partition = new Partition(0)
    val systemStreamPartition = new SystemStreamPartition(systemStream, partition)
    val testStreamMetadata = new SystemStreamMetadata(systemStream.getStream, Map(partition -> new SystemStreamPartitionMetadata("0", "1", "2")).asJava)
    val systemStreamMetadata = Map(systemStream -> testStreamMetadata)
    val config = new MapConfig
    val checkpointManager = getCheckpointManager(systemStreamPartition, taskName)
    val startpointManager = getStartpointManager()
    val systemAdmins = mock(classOf[SystemAdmins])
    when(systemAdmins.getSystemAdmin("test-system")).thenReturn(getSystemAdmin)
    val offsetManager = OffsetManager(systemStreamMetadata, config, checkpointManager, getStartpointManager(), systemAdmins, Map(), new OffsetManagerMetrics)
    offsetManager.register(taskName, Set(systemStreamPartition))
    startpointManager.writeStartpoint(systemStreamPartition, taskName, new StartpointOldest)
    assertNotNull(startpointManager.readStartpoint(systemStreamPartition, taskName))
    offsetManager.start
    assertTrue(checkpointManager.isStarted)
    assertEquals(1, checkpointManager.registered.size)
    assertEquals(taskName, checkpointManager.registered.head)
    assertEquals(checkpointManager.checkpoints.head._2, checkpointManager.readLastCheckpoint(taskName))
    // Should get offset 45 back from the checkpoint manager, which is last processed, and system admin should return 46 as starting offset.
    assertEquals("46", offsetManager.getStartingOffset(taskName, systemStreamPartition).get)
    assertEquals("45", offsetManager.getLastProcessedOffset(taskName, systemStreamPartition).get)
    offsetManager.update(taskName, systemStreamPartition, "46")
    assertEquals("46", offsetManager.getLastProcessedOffset(taskName, systemStreamPartition).get)
    offsetManager.update(taskName, systemStreamPartition, "47")
    assertEquals("47", offsetManager.getLastProcessedOffset(taskName, systemStreamPartition).get)
    // Should never update starting offset.
    assertEquals("46", offsetManager.getStartingOffset(taskName, systemStreamPartition).get)
    // Should not update null offset
    offsetManager.update(taskName, systemStreamPartition, null)
    checkpoint(offsetManager, taskName)
    assertNull(startpointManager.readStartpoint(systemStreamPartition, taskName)) // Startpoint should delete after checkpoint commit
    val expectedCheckpoint = new Checkpoint(Map(systemStreamPartition -> "47").asJava)
    assertEquals(expectedCheckpoint, checkpointManager.readLastCheckpoint(taskName))
  }
  @Test
  def testGetAndSetStartpoint {
    val taskName1 = new TaskName("c")
    val taskName2 = new TaskName("d")
    val systemStream = new SystemStream("test-system", "test-stream")
    val partition = new Partition(0)
    val systemStreamPartition = new SystemStreamPartition(systemStream, partition)
    val testStreamMetadata = new SystemStreamMetadata(systemStream.getStream, Map(partition -> new SystemStreamPartitionMetadata("0", "1", "2")).asJava)
    val systemStreamMetadata = Map(systemStream -> testStreamMetadata)
    val config = new MapConfig
    val checkpointManager = getCheckpointManager(systemStreamPartition, taskName1)
    val startpointManager = getStartpointManager()
    val systemAdmins = mock(classOf[SystemAdmins])
    when(systemAdmins.getSystemAdmin("test-system")).thenReturn(getSystemAdmin)
    val offsetManager = OffsetManager(systemStreamMetadata, config, checkpointManager, getStartpointManager(), systemAdmins, Map(), new OffsetManagerMetrics)

    offsetManager.register(taskName1, Set(systemStreamPartition))
    val startpoint1 = new StartpointOldest
    startpointManager.writeStartpoint(systemStreamPartition, taskName1, startpoint1)
    assertNotNull(startpointManager.readStartpoint(systemStreamPartition, taskName1))
    offsetManager.start
    val startpoint2 = new StartpointUpcoming
    offsetManager.setStartpoint(taskName2, systemStreamPartition, startpoint2)

    assertEquals(Option(startpoint1), offsetManager.getStartpoint(taskName1, systemStreamPartition))
    assertEquals(Option(startpoint2), offsetManager.getStartpoint(taskName2, systemStreamPartition))

    assertEquals(startpoint1, startpointManager.readStartpoint(systemStreamPartition, taskName1))
    // Startpoint written to offset manager, but not directly to startpoint manager.
    assertNull(startpointManager.readStartpoint(systemStreamPartition, taskName2))
  }

  @Test
  def testGetCheckpointedOffsetMetric{
    val taskName = new TaskName("c")
    val systemStream = new SystemStream("test-system", "test-stream")
    val partition = new Partition(0)
    val systemStreamPartition = new SystemStreamPartition(systemStream, partition)
    val testStreamMetadata = new SystemStreamMetadata(systemStream.getStream, Map(partition -> new SystemStreamPartitionMetadata("0", "1", "2")).asJava)
    val systemStreamMetadata = Map(systemStream -> testStreamMetadata)
    val config = new MapConfig
    val checkpointManager = getCheckpointManager(systemStreamPartition, taskName)
    val startpointManager = getStartpointManager()
    val systemAdmins = mock(classOf[SystemAdmins])
    when(systemAdmins.getSystemAdmin("test-system")).thenReturn(getSystemAdmin)
    val offsetManager = OffsetManager(systemStreamMetadata, config, checkpointManager, getStartpointManager(), systemAdmins, Map(), new OffsetManagerMetrics)
    offsetManager.register(taskName, Set(systemStreamPartition))

    // Pre-populate startpoint
    startpointManager.writeStartpoint(systemStreamPartition, taskName, new StartpointOldest)
    offsetManager.start
    // Should get offset 45 back from the checkpoint manager, which is last processed, and system admin should return 46 as starting offset.
    assertNotNull(startpointManager.readStartpoint(systemStreamPartition, taskName))
    checkpoint(offsetManager, taskName)
    assertNull(startpointManager.readStartpoint(systemStreamPartition, taskName)) // Startpoint should delete after checkpoint commit
    assertEquals("45", offsetManager.offsetManagerMetrics.checkpointedOffsets.get(systemStreamPartition).getValue)
    offsetManager.update(taskName, systemStreamPartition, "46")

    offsetManager.update(taskName, systemStreamPartition, "47")
    startpointManager.writeStartpoint(systemStreamPartition, taskName, new StartpointOldest)
    assertNotNull(startpointManager.readStartpoint(systemStreamPartition, taskName))
    checkpoint(offsetManager, taskName)
    assertNotNull(startpointManager.readStartpoint(systemStreamPartition, taskName)) // Startpoint should only be deleted at first checkpoint
    assertEquals("47", offsetManager.offsetManagerMetrics.checkpointedOffsets.get(systemStreamPartition).getValue)

    offsetManager.update(taskName, systemStreamPartition, "48")
    assertNotNull(startpointManager.readStartpoint(systemStreamPartition, taskName))
    checkpoint(offsetManager, taskName)
    assertNotNull(startpointManager.readStartpoint(systemStreamPartition, taskName)) // Startpoint should only be deleted at first checkpoint
    assertEquals("48", offsetManager.offsetManagerMetrics.checkpointedOffsets.get(systemStreamPartition).getValue)
  }

  @Test
  def testShouldResetStreams {
    val taskName = new TaskName("c")
    val systemStream = new SystemStream("test-system", "test-stream")
    val partition = new Partition(0)
    val systemStreamPartition = new SystemStreamPartition(systemStream, partition)
    val testStreamMetadata = new SystemStreamMetadata(systemStream.getStream, Map(partition -> new SystemStreamPartitionMetadata("0", "1", "2")).asJava)
    val systemStreamMetadata = Map(systemStream -> testStreamMetadata)
    val checkpoint = new Checkpoint(Map(systemStreamPartition -> "45").asJava)
    val checkpointManager = getCheckpointManager(systemStreamPartition, taskName)
    val config = new MapConfig(Map(
      "systems.test-system.samza.offset.default" -> "oldest",
      "systems.test-system.streams.test-stream.samza.reset.offset" -> "true").asJava)
    val offsetManager = OffsetManager(systemStreamMetadata, config, checkpointManager)
    offsetManager.register(taskName, Set(systemStreamPartition))
    offsetManager.start
    assertTrue(checkpointManager.isStarted)
    assertEquals(1, checkpointManager.registered.size)
    assertEquals(taskName, checkpointManager.registered.head)
    assertEquals(checkpoint, checkpointManager.readLastCheckpoint(taskName))
    // Should be zero even though the checkpoint has an offset of 45, since we're forcing a reset.
    assertEquals("0", offsetManager.getStartingOffset(taskName, systemStreamPartition).get)
  }

  @Test
  def testOffsetManagerShouldHandleNullCheckpoints {
    val systemStream = new SystemStream("test-system", "test-stream")
    val partition1 = new Partition(0)
    val partition2 = new Partition(1)
    val taskName1 = new TaskName("P0")
    val taskName2 = new TaskName("P1")
    val systemStreamPartition1 = new SystemStreamPartition(systemStream, partition1)
    val systemStreamPartition2 = new SystemStreamPartition(systemStream, partition2)
    val testStreamMetadata = new SystemStreamMetadata(systemStream.getStream, Map(
      partition1 -> new SystemStreamPartitionMetadata("0", "1", "2"),
      partition2 -> new SystemStreamPartitionMetadata("3", "4", "5")).asJava)
    val systemStreamMetadata = Map(systemStream -> testStreamMetadata)
    val checkpoint = new Checkpoint(Map(systemStreamPartition1 -> "45").asJava)
    // Checkpoint manager only has partition 1.
    val checkpointManager = getCheckpointManager(systemStreamPartition1, taskName1)
    val startpointManager = getStartpointManager()
    val config = new MapConfig
    val systemAdmins = mock(classOf[SystemAdmins])
    when(systemAdmins.getSystemAdmin("test-system")).thenReturn(getSystemAdmin)
    val offsetManager = OffsetManager(systemStreamMetadata, config, checkpointManager, getStartpointManager(), systemAdmins)
    // Register both partitions. Partition 2 shouldn't have a checkpoint.
    offsetManager.register(taskName1, Set(systemStreamPartition1))
    offsetManager.register(taskName2, Set(systemStreamPartition2))
    startpointManager.writeStartpoint(systemStreamPartition1, taskName1, new StartpointOldest)
    assertNotNull(startpointManager.readStartpoint(systemStreamPartition1, taskName1))
    offsetManager.start
    assertTrue(checkpointManager.isStarted)
    assertEquals(2, checkpointManager.registered.size)
    assertEquals(checkpoint, checkpointManager.readLastCheckpoint(taskName1))
    assertNull(checkpointManager.readLastCheckpoint(taskName2))
    assertNotNull(startpointManager.readStartpoint(systemStreamPartition1, taskName1)) // no checkpoint commit so this should still be there
  }

  @Test
  def testShouldFailWhenMissingMetadata {
    val taskName = new TaskName("c")
    val systemStream = new SystemStream("test-system", "test-stream")
    val partition = new Partition(0)
    val systemStreamPartition = new SystemStreamPartition(systemStream, partition)
    val offsetManager = new OffsetManager
    offsetManager.register(taskName, Set(systemStreamPartition))

    intercept[SamzaException] {
      offsetManager.start
    }
  }

  @Test
  def testDefaultSystemShouldFailWhenFailIsSpecified {
    val systemStream = new SystemStream("test-system", "test-stream")
    val partition = new Partition(0)
    val systemStreamPartition = new SystemStreamPartition(systemStream, partition)
    val testStreamMetadata = new SystemStreamMetadata(systemStream.getStream, Map(partition -> new SystemStreamPartitionMetadata("0", "1", "2")).asJava)
    val systemStreamMetadata = Map(systemStream -> testStreamMetadata)
    val config = new MapConfig(Map("systems.test-system.samza.offset.default" -> "fail").asJava)
    intercept[IllegalArgumentException] {
      OffsetManager(systemStreamMetadata, config)
    }
  }

  @Test
  def testDefaultStreamShouldFailWhenFailIsSpecified {
    val systemStream = new SystemStream("test-system", "test-stream")
    val partition = new Partition(0)
    val testStreamMetadata = new SystemStreamMetadata(systemStream.getStream, Map(partition -> new SystemStreamPartitionMetadata("0", "1", "2")).asJava)
    val systemStreamMetadata = Map(systemStream -> testStreamMetadata)
    val config = new MapConfig(Map("systems.test-system.streams.test-stream.samza.offset.default" -> "fail").asJava)

    intercept[IllegalArgumentException] {
      OffsetManager(systemStreamMetadata, config)
    }
  }

  @Test
  def testOutdatedStreamInCheckpoint {
    val taskName = new TaskName("c")
    val systemStream0 = new SystemStream("test-system-0", "test-stream")
    val systemStream1 = new SystemStream("test-system-1", "test-stream")
    val partition0 = new Partition(0)
    val systemStreamPartition0 = new SystemStreamPartition(systemStream0, partition0)
    val systemStreamPartition1 = new SystemStreamPartition(systemStream1, partition0)
    val testStreamMetadata = new SystemStreamMetadata(systemStream0.getStream, Map(partition0 -> new SystemStreamPartitionMetadata("0", "1", "2")).asJava)
    val offsetSettings = Map(systemStream0 -> OffsetSetting(testStreamMetadata, OffsetType.UPCOMING, false))
    val checkpointManager = getCheckpointManager(systemStreamPartition1)
    val offsetManager = new OffsetManager(offsetSettings, checkpointManager)
    offsetManager.register(taskName, Set(systemStreamPartition0))
    offsetManager.start
    assertTrue(checkpointManager.isStarted)
    assertEquals(1, checkpointManager.registered.size)
    assertNull(offsetManager.getLastProcessedOffset(taskName, systemStreamPartition1).getOrElse(null))
  }

  @Test
  def testDefaultToUpcomingOnMissingDefault {
    val taskName = new TaskName("task-name")
    val ssp = new SystemStreamPartition(new SystemStream("test-system", "test-stream"), new Partition(0))
    val sspm = new SystemStreamPartitionMetadata(null, null, "13")
    val offsetMeta = new SystemStreamMetadata("test-stream", Map(new Partition(0) -> sspm).asJava)
    val settings = new OffsetSetting(offsetMeta, OffsetType.OLDEST, resetOffset = false)
    val offsetManager = new OffsetManager(offsetSettings = Map(ssp.getSystemStream -> settings))
    offsetManager.register(taskName, Set(ssp))
    offsetManager.start
    assertEquals(Some("13"), offsetManager.getStartingOffset(taskName, ssp))
  }

  @Test
  def testCheckpointListener{
    val taskName = new TaskName("c")
    val systemName = "test-system"
    val systemName2 = "test-system2"
    val systemStream = new SystemStream(systemName, "test-stream")
    val systemStream2 = new SystemStream(systemName2, "test-stream2")
    val partition = new Partition(0)
    val systemStreamPartition = new SystemStreamPartition(systemStream, partition)
    val systemStreamPartition2 = new SystemStreamPartition(systemStream2, partition)
    val testStreamMetadata = new SystemStreamMetadata(systemStream.getStream, Map(partition -> new SystemStreamPartitionMetadata("0", "1", "2")).asJava)
    val testStreamMetadata2 = new SystemStreamMetadata(systemStream2.getStream, Map(partition -> new SystemStreamPartitionMetadata("0", "1", "2")).asJava)
    val systemStreamMetadata = Map(systemStream -> testStreamMetadata, systemStream2->testStreamMetadata2)
    val config = new MapConfig
    val checkpointManager = getCheckpointManager1(systemStreamPartition,
                                                 new Checkpoint(Map(systemStreamPartition -> "45", systemStreamPartition2 -> "100").asJava),
                                                 taskName)
    val startpointManager = getStartpointManager()
    val consumer = new SystemConsumerWithCheckpointCallback
    val systemAdmins = mock(classOf[SystemAdmins])
    when(systemAdmins.getSystemAdmin(systemName)).thenReturn(getSystemAdmin)
    when(systemAdmins.getSystemAdmin(systemName2)).thenReturn(getSystemAdmin)

    val checkpointListeners: Map[String, CheckpointListener] = if(consumer.isInstanceOf[CheckpointListener])
      Map(systemName -> consumer.asInstanceOf[CheckpointListener])
    else
      Map()

    val offsetManager = OffsetManager(systemStreamMetadata, config, checkpointManager, getStartpointManager(), systemAdmins,
      checkpointListeners, new OffsetManagerMetrics)
    offsetManager.register(taskName, Set(systemStreamPartition, systemStreamPartition2))

    startpointManager.writeStartpoint(systemStreamPartition, taskName, new StartpointOldest)
    assertNotNull(startpointManager.readStartpoint(systemStreamPartition, taskName))
    offsetManager.start
    // Should get offset 45 back from the checkpoint manager, which is last processed, and system admin should return 46 as starting offset.
    checkpoint(offsetManager, taskName)
    assertNull(startpointManager.readStartpoint(systemStreamPartition, taskName)) // Startpoint be deleted at first checkpoint
    assertEquals("45", offsetManager.offsetManagerMetrics.checkpointedOffsets.get(systemStreamPartition).getValue)
    assertEquals("100", offsetManager.offsetManagerMetrics.checkpointedOffsets.get(systemStreamPartition2).getValue)
    assertEquals("45", consumer.recentCheckpoint.get(systemStreamPartition))
    // make sure only the system with the callbacks gets them
    assertNull(consumer.recentCheckpoint.get(systemStreamPartition2))

    offsetManager.update(taskName, systemStreamPartition, "46")
    offsetManager.update(taskName, systemStreamPartition, "47")
    startpointManager.writeStartpoint(systemStreamPartition, taskName, new StartpointOldest)
    assertNotNull(startpointManager.readStartpoint(systemStreamPartition, taskName))
    checkpoint(offsetManager, taskName)
    assertNotNull(startpointManager.readStartpoint(systemStreamPartition, taskName)) // Startpoint should only be deleted at first checkpoint
    assertEquals("47", offsetManager.offsetManagerMetrics.checkpointedOffsets.get(systemStreamPartition).getValue)
    assertEquals("100", offsetManager.offsetManagerMetrics.checkpointedOffsets.get(systemStreamPartition2).getValue)
    assertEquals("47", consumer.recentCheckpoint.get(systemStreamPartition))
    assertNull(consumer.recentCheckpoint.get(systemStreamPartition2))

    offsetManager.update(taskName, systemStreamPartition, "48")
    offsetManager.update(taskName, systemStreamPartition2, "101")
    startpointManager.writeStartpoint(systemStreamPartition, taskName, new StartpointOldest)
    assertNotNull(startpointManager.readStartpoint(systemStreamPartition, taskName))
    checkpoint(offsetManager, taskName)
    assertNotNull(startpointManager.readStartpoint(systemStreamPartition, taskName)) // Startpoint should only be deleted at first checkpoint
    assertEquals("48", offsetManager.offsetManagerMetrics.checkpointedOffsets.get(systemStreamPartition).getValue)
    assertEquals("101", offsetManager.offsetManagerMetrics.checkpointedOffsets.get(systemStreamPartition2).getValue)
    assertEquals("48", consumer.recentCheckpoint.get(systemStreamPartition))
    assertNull(consumer.recentCheckpoint.get(systemStreamPartition2))
    offsetManager.stop
  }

  /**
    * If task.max.concurrency > 1 and task.async.commit == true, a task could update its offsets at the same time as
    * TaskInstance.commit(). This makes it possible to checkpoint offsets which did not successfully flush.
    *
    * This is prevented by using separate methods to get a checkpoint and write that checkpoint. See SAMZA-1384
    */
  @Test
  def testConcurrentCheckpointAndUpdate{
    val taskName = new TaskName("c")
    val systemStream = new SystemStream("test-system", "test-stream")
    val partition = new Partition(0)
    val systemStreamPartition = new SystemStreamPartition(systemStream, partition)
    val testStreamMetadata = new SystemStreamMetadata(systemStream.getStream, Map(partition -> new SystemStreamPartitionMetadata("0", "1", "2")).asJava)
    val systemStreamMetadata = Map(systemStream -> testStreamMetadata)
    val checkpointManager = getCheckpointManager(systemStreamPartition, taskName)
    val startpointManager = getStartpointManager()
    val systemAdmins = mock(classOf[SystemAdmins])
    when(systemAdmins.getSystemAdmin("test-system")).thenReturn(getSystemAdmin)
    val offsetManager = OffsetManager(systemStreamMetadata, new MapConfig, checkpointManager, getStartpointManager(), systemAdmins, Map(), new OffsetManagerMetrics)
    offsetManager.register(taskName, Set(systemStreamPartition))
    startpointManager.writeStartpoint(systemStreamPartition, taskName, new StartpointOldest)
    offsetManager.start

    // Should get offset 45 back from the checkpoint manager, which is last processed, and system admin should return 46 as starting offset.
    assertNotNull(startpointManager.readStartpoint(systemStreamPartition, taskName))
    checkpoint(offsetManager, taskName)
    assertNull(startpointManager.readStartpoint(systemStreamPartition, taskName)) // Startpoint be deleted at first checkpoint
    assertEquals("45", offsetManager.offsetManagerMetrics.checkpointedOffsets.get(systemStreamPartition).getValue)

    startpointManager.writeStartpoint(systemStreamPartition, taskName, new StartpointOldest)

    offsetManager.update(taskName, systemStreamPartition, "46")
    // Get checkpoint snapshot like we do at the beginning of TaskInstance.commit()
    val checkpoint46 = offsetManager.buildCheckpoint(taskName)
    offsetManager.update(taskName, systemStreamPartition, "47") // Offset updated before checkpoint
    offsetManager.writeCheckpoint(taskName, checkpoint46)
    assertNotNull(startpointManager.readStartpoint(systemStreamPartition, taskName)) // Startpoint should only be deleted at first checkpoint
    assertEquals(Some("47"), offsetManager.getLastProcessedOffset(taskName, systemStreamPartition))
    assertEquals("46", offsetManager.offsetManagerMetrics.checkpointedOffsets.get(systemStreamPartition).getValue)

    // Now write the checkpoint for the latest offset
    val checkpoint47 = offsetManager.buildCheckpoint(taskName)
    offsetManager.writeCheckpoint(taskName, checkpoint47)
    assertNotNull(startpointManager.readStartpoint(systemStreamPartition, taskName)) // Startpoint should only be deleted at first checkpoint
    assertEquals(Some("47"), offsetManager.getLastProcessedOffset(taskName, systemStreamPartition))
    assertEquals("47", offsetManager.offsetManagerMetrics.checkpointedOffsets.get(systemStreamPartition).getValue)
  }

  // Utility method to create and write checkpoint in one statement
  def checkpoint(offsetManager: OffsetManager, taskName: TaskName): Unit = {
    offsetManager.writeCheckpoint(taskName, offsetManager.buildCheckpoint(taskName))
  }

  class SystemConsumerWithCheckpointCallback extends SystemConsumer with CheckpointListener{
    var recentCheckpoint: java.util.Map[SystemStreamPartition, String] = java.util.Collections.emptyMap[SystemStreamPartition, String]
    override def start() {}

    override def stop() {}

    override def register(systemStreamPartition: SystemStreamPartition, offset: String) {}

    override def poll(systemStreamPartitions: util.Set[SystemStreamPartition],
                      timeout: Long): util.Map[SystemStreamPartition, util.List[IncomingMessageEnvelope]] = { null }

    override def onCheckpoint(offsets: java.util.Map[SystemStreamPartition,String]){
      recentCheckpoint = (recentCheckpoint.asScala ++ offsets.asScala).asJava
    }
  }

  private def getCheckpointManager(systemStreamPartition: SystemStreamPartition, taskName:TaskName = new TaskName("taskName")) = {
    getCheckpointManager1(systemStreamPartition, new Checkpoint(Map(systemStreamPartition -> "45").asJava), taskName)
  }

  private def getCheckpointManager1(systemStreamPartition: SystemStreamPartition, checkpoint: Checkpoint, taskName:TaskName = new TaskName("taskName")) = {
    new CheckpointManager {
      var isStarted = false
      var isStopped = false
      var registered = Set[TaskName]()
      var checkpoints: Map[TaskName, Checkpoint] = Map(taskName -> checkpoint)
      var taskNameToPartitionMapping: util.Map[TaskName, java.lang.Integer] = new util.HashMap[TaskName, java.lang.Integer]()
      def start { isStarted = true }
      def register(taskName: TaskName) { registered += taskName }
      def writeCheckpoint(taskName: TaskName, checkpoint: Checkpoint) { checkpoints += taskName -> checkpoint }
      def readLastCheckpoint(taskName: TaskName) = checkpoints.getOrElse(taskName, null)
      def stop { isStopped = true }

      // Only for testing purposes - not present in actual checkpoint manager
      def getOffets = Map(taskName -> checkpoint.getOffsets.asScala.toMap)
    }
  }

  private def getStartpointManager() = {
    val startpointManager = new StartpointManager(new InMemoryMetadataStoreFactory, new MapConfig, new NoOpMetricsRegistry)
    startpointManager.start
    startpointManager
  }

  private def getSystemAdmin: SystemAdmin = {
    new SystemAdmin {
      def getOffsetsAfter(offsets: java.util.Map[SystemStreamPartition, String]) =
        offsets.asScala.mapValues(offset => (offset.toLong + 1).toString).asJava

      def getSystemStreamMetadata(streamNames: java.util.Set[String]) =
        Map[String, SystemStreamMetadata]().asJava

      override def offsetComparator(offset1: String, offset2: String) = null
    }
  }
}
