package dev.netpilot.core

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChunkSchedulerTest {

    @Test fun rangesTileTheFileExactly() {
        val s = ChunkScheduler(totalBytes = 10_000_001, chunkBytes = 1_000_000)
        val ranges = generateSequence { s.next("a") }.toList()
        assertEquals(11, ranges.size)
        assertEquals(0, ranges.first().start)
        assertEquals(10_000_000, ranges.last().endInclusive)
        ranges.zipWithNext().forEach { (a, b) -> assertEquals(a.endInclusive + 1, b.start) }
        assertEquals(10_000_001, ranges.sumOf { it.length })
        assertEquals("bytes=0-999999", ranges.first().header())
    }

    @Test fun failedRangeIsRetriedByAnotherLink() {
        val s = ChunkScheduler(totalBytes = 3, chunkBytes = 1)
        val r = s.next("wifi")!!
        s.fail(r)
        assertEquals(r, s.next("cell"))
        s.complete(r)
        assertEquals(mapOf("cell" to 1L), s.bytesPerLink())
    }

    @Test fun failAllRequeuesLostLinksWork() {
        val s = ChunkScheduler(totalBytes = 4, chunkBytes = 1)
        s.next("wifi"); s.next("wifi"); val c = s.next("cell")!!
        s.failAll("wifi")
        s.complete(c)
        val rest = generateSequence { s.next("cell") }.toList()
        assertEquals(3, rest.size)
        rest.forEach(s::complete)
        assertTrue(s.isDone)
        assertNull(s.next("cell"))
    }

    @Test fun duplicateCompleteIsIgnored() {
        val s = ChunkScheduler(totalBytes = 2, chunkBytes = 1)
        val r = s.next("a")!!
        s.complete(r); s.complete(r)
        assertEquals(1, s.completed())
    }

    @Test fun concurrentWorkersCompleteEverything() {
        val s = ChunkScheduler(totalBytes = 5_000, chunkBytes = 7)
        val pool = Executors.newFixedThreadPool(8)
        repeat(8) { w ->
            pool.execute {
                val link = if (w % 2 == 0) "wifi" else "cell"
                while (true) {
                    val r = s.next(link) ?: break
                    if (r.start % 3 == 0L && w == 0) s.fail(r) else s.complete(r)
                }
            }
        }
        pool.shutdown(); pool.awaitTermination(10, TimeUnit.SECONDS)
        // Worker 0 may have failed ranges after others stopped; drain them.
        while (true) { val r = s.next("cell") ?: break; s.complete(r) }
        assertTrue(s.isDone)
        assertEquals(5_000, s.bytesPerLink().values.sum())
    }
}
