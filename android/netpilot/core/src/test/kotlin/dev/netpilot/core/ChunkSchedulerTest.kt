package dev.netpilot.core

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChunkSchedulerTest {

    @Test fun rangesTileTheFileExactly() {
        val s = ChunkScheduler(totalBytes = 10_000_001, chunkBytes = 1_000_000, endGame = false)
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
        s.fail(r, "wifi")
        assertEquals(r, s.next("cell"))
        s.complete(r, "cell")
        assertEquals(mapOf("cell" to 1L), s.bytesPerLink())
    }

    @Test fun failAllRequeuesLostLinksWork() {
        val s = ChunkScheduler(totalBytes = 4, chunkBytes = 1, endGame = false)
        s.next("wifi"); s.next("wifi"); val c = s.next("cell")!!
        s.failAll("wifi")
        s.complete(c, "cell")
        val rest = generateSequence { s.next("cell") }.toList()
        assertEquals(3, rest.size)
        rest.forEach { s.complete(it, "cell") }
        assertTrue(s.isDone)
        assertNull(s.next("cell"))
    }

    @Test fun duplicateCompleteIsIgnored() {
        val s = ChunkScheduler(totalBytes = 2, chunkBytes = 1)
        val r = s.next("a")!!
        s.complete(r, "a"); s.complete(r, "b")
        assertEquals(1, s.completed())
    }

    @Test fun endGameDuplicatesOldestInFlightRangeOnce() {
        val s = ChunkScheduler(totalBytes = 2, chunkBytes = 1)
        val r1 = s.next("cell")!!
        val r2 = s.next("cell")!!
        assertEquals(r1, s.next("wifi"))      // nothing pending: copy the oldest range cell holds
        assertEquals(r2, s.next("other"))     // r1 already has two owners; next candidate is r2
        assertNull(s.next("wifi2"))           // every range already duplicated
        s.complete(r1, "wifi")                // fast copy wins
        s.complete(r1, "cell")                // late copy ignored
        assertEquals(mapOf("wifi" to 1L), s.bytesPerLink())
    }

    @Test fun failedDuplicateDoesNotRequeueWhileOtherCopyRuns() {
        val s = ChunkScheduler(totalBytes = 1, chunkBytes = 1)
        val r = s.next("cell")!!
        assertEquals(r, s.next("wifi"))
        s.fail(r, "wifi")                     // cell still has it: must not go back to the queue
        s.complete(r, "cell")
        assertTrue(s.isDone)
        assertNull(s.next("wifi"))            // nothing was requeued
        assertEquals(mapOf("cell" to 1L), s.bytesPerLink())
    }

    @Test fun concurrentWorkersCompleteEverything() {
        val s = ChunkScheduler(totalBytes = 5_000, chunkBytes = 7)
        val pool = Executors.newFixedThreadPool(8)
        repeat(8) { w ->
            pool.execute {
                val link = if (w % 2 == 0) "wifi" else "cell"
                while (!s.isDone) {
                    val r = s.next(link) ?: if (s.hasWorkLeft) continue else break
                    if (r.start % 3 == 0L && w == 0) s.fail(r, link) else s.complete(r, link)
                }
            }
        }
        pool.shutdown(); pool.awaitTermination(10, TimeUnit.SECONDS)
        while (true) { val r = s.next("cell") ?: break; s.complete(r, "cell") }
        assertTrue(s.isDone)
        assertEquals(5_000, s.bytesPerLink().values.sum())
    }

    // ---- Model: two links with fixed rates and a request round-trip per chunk ----------------

    private data class Link(val id: String, val mbps: Double, val rttMs: Double)

    /** Deterministic event simulation driving the real scheduler. Returns seconds to finish. */
    private fun simulate(sizeMb: Double, links: List<Link>, endGame: Boolean, chunkMb: Double = 1.0): Double {
        val total = (sizeMb * 1_000_000).toLong()
        val s = ChunkScheduler(total, (chunkMb * 1_000_000).toLong(), endGame)
        data class Job(val link: Link, val range: ByteRange, val doneAt: Double)
        val running = ArrayList<Job>()
        var now = 0.0
        fun start(l: Link) {
            val r = s.next(l.id) ?: return
            running += Job(l, r, now + l.rttMs / 1000 + r.length * 8 / (l.mbps * 1e6))
        }
        links.forEach(::start)
        while (!s.isDone && running.isNotEmpty()) {
            val j = running.minByOrNull { it.doneAt }!!
            running.remove(j); now = j.doneAt
            s.complete(j.range, j.link.id)
            running.removeAll { it.range == j.range } // a duplicate on another link is cancelled
            links.filter { l -> running.none { it.link == l } }.forEach(::start)
        }
        return now
    }

    @Test fun endGameNeverSlowerAndBeatsSingleLinkInModel() {
        val cases = listOf(
            "similar (Wi-Fi 50/30 ms + 4G 30/60 ms)" to listOf(Link("wifi", 50.0, 30.0), Link("cell", 30.0, 60.0)),
            "unequal (Wi-Fi 100/20 ms + 4G 8/80 ms)" to listOf(Link("wifi", 100.0, 20.0), Link("cell", 8.0, 80.0)),
            "very unequal (Wi-Fi 200/15 ms + 3G 2/150 ms)" to listOf(Link("wifi", 200.0, 15.0), Link("cell", 2.0, 150.0)),
        )
        for ((name, links) in cases) {
            val fastOnly = simulate(50.0, listOf(links.maxByOrNull { it.mbps }!!), endGame = false)
            val noEnd = simulate(50.0, links, endGame = false)
            val withEnd = simulate(50.0, links, endGame = true)
            val sum = links.sumOf { it.mbps }
            println("MODEL %-45s fast-only %.2fs | combined no-end-game %.2fs | combined end-game %.2fs | %.0f%% of sum-of-rates speed"
                .format(name, fastOnly, noEnd, withEnd, 50.0 * 8 / withEnd / sum * 100))
            assertTrue(withEnd <= noEnd + 1e-9, "end-game slower for $name")
            assertTrue(withEnd <= fastOnly + 1e-9, "combined slower than fast link alone for $name")
        }
    }
}
