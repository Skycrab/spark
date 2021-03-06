package spark

import org.scalatest.FunSuite
import org.scalatest.BeforeAndAfter

import akka.actor._
import spark.scheduler.MapStatus
import spark.storage.BlockManagerId
import spark.util.AkkaUtils

class MapOutputTrackerSuite extends FunSuite with BeforeAndAfter {
  after {
    System.clearProperty("spark.master.port")
  }
 
  test("compressSize") {
    assert(MapOutputTracker.compressSize(0L) === 0)
    assert(MapOutputTracker.compressSize(1L) === 0)
    assert(MapOutputTracker.compressSize(2L) === 8)
    assert(MapOutputTracker.compressSize(10L) === 25)
    assert((MapOutputTracker.compressSize(1000000L) & 0xFF) === 145)
    assert((MapOutputTracker.compressSize(1000000000L) & 0xFF) === 218)
    // This last size is bigger than we can encode in a byte, so check that we just return 255
    assert((MapOutputTracker.compressSize(1000000000000000000L) & 0xFF) === 255)
  }

  test("decompressSize") {
    assert(MapOutputTracker.decompressSize(0) === 1)
    for (size <- Seq(2L, 10L, 100L, 50000L, 1000000L, 1000000000L)) {
      val size2 = MapOutputTracker.decompressSize(MapOutputTracker.compressSize(size))
      assert(size2 >= 0.99 * size && size2 <= 1.11 * size,
        "size " + size + " decompressed to " + size2 + ", which is out of range")
    }
  }

  test("master start and stop") {
    val actorSystem = ActorSystem("test")
    val tracker = new MapOutputTracker(actorSystem, true)
    tracker.stop()
  }

  test("master register and fetch") {
    val actorSystem = ActorSystem("test")
    val tracker = new MapOutputTracker(actorSystem, true)
    tracker.registerShuffle(10, 2)
    val compressedSize1000 = MapOutputTracker.compressSize(1000L)
    val compressedSize10000 = MapOutputTracker.compressSize(10000L)
    val size1000 = MapOutputTracker.decompressSize(compressedSize1000)
    val size10000 = MapOutputTracker.decompressSize(compressedSize10000)
    tracker.registerMapOutput(10, 0, new MapStatus(new BlockManagerId("hostA", 1000),
        Array(compressedSize1000, compressedSize10000)))
    tracker.registerMapOutput(10, 1, new MapStatus(new BlockManagerId("hostB", 1000),
        Array(compressedSize10000, compressedSize1000)))
    val statuses = tracker.getServerStatuses(10, 0)
    assert(statuses.toSeq === Seq((new BlockManagerId("hostA", 1000), size1000),
                                  (new BlockManagerId("hostB", 1000), size10000)))
    tracker.stop()
  }

  test("master register and unregister and fetch") {
    val actorSystem = ActorSystem("test")
    val tracker = new MapOutputTracker(actorSystem, true)
    tracker.registerShuffle(10, 2)
    val compressedSize1000 = MapOutputTracker.compressSize(1000L)
    val compressedSize10000 = MapOutputTracker.compressSize(10000L)
    val size1000 = MapOutputTracker.decompressSize(compressedSize1000)
    val size10000 = MapOutputTracker.decompressSize(compressedSize10000)
    tracker.registerMapOutput(10, 0, new MapStatus(new BlockManagerId("hostA", 1000),
        Array(compressedSize1000, compressedSize1000, compressedSize1000)))
    tracker.registerMapOutput(10, 1, new MapStatus(new BlockManagerId("hostB", 1000),
        Array(compressedSize10000, compressedSize1000, compressedSize1000)))

    // As if we had two simulatenous fetch failures
    tracker.unregisterMapOutput(10, 0, new BlockManagerId("hostA", 1000))
    tracker.unregisterMapOutput(10, 0, new BlockManagerId("hostA", 1000))

    // The remaining reduce task might try to grab the output dispite the shuffle failure;
    // this should cause it to fail, and the scheduler will ignore the failure due to the
    // stage already being aborted.
    intercept[FetchFailedException] { tracker.getServerStatuses(10, 1) }
  }

  test("remote fetch") {
    System.clearProperty("spark.master.host")
    val (actorSystem, boundPort) =
      AkkaUtils.createActorSystem("test", "localhost", 0)
    System.setProperty("spark.master.port", boundPort.toString)
    val masterTracker = new MapOutputTracker(actorSystem, true)
    val slaveTracker = new MapOutputTracker(actorSystem, false)
    masterTracker.registerShuffle(10, 1)
    masterTracker.incrementGeneration()
    slaveTracker.updateGeneration(masterTracker.getGeneration)
    intercept[FetchFailedException] { slaveTracker.getServerStatuses(10, 0) }

    val compressedSize1000 = MapOutputTracker.compressSize(1000L)
    val size1000 = MapOutputTracker.decompressSize(compressedSize1000)
    masterTracker.registerMapOutput(10, 0, new MapStatus(
      new BlockManagerId("hostA", 1000), Array(compressedSize1000)))
    masterTracker.incrementGeneration()
    slaveTracker.updateGeneration(masterTracker.getGeneration)
    assert(slaveTracker.getServerStatuses(10, 0).toSeq ===
           Seq((new BlockManagerId("hostA", 1000), size1000)))

    masterTracker.unregisterMapOutput(10, 0, new BlockManagerId("hostA", 1000))
    masterTracker.incrementGeneration()
    slaveTracker.updateGeneration(masterTracker.getGeneration)
    intercept[FetchFailedException] { slaveTracker.getServerStatuses(10, 0) }

    // failure should be cached
    intercept[FetchFailedException] { slaveTracker.getServerStatuses(10, 0) }
  }
}
