package dev.netpilot.core

import java.net.InetSocketAddress
import java.util.concurrent.Executors
import kotlin.random.Random
import kotlin.test.Test

/**
 * Every parser that reads bytes from apps or the network must reject garbage without throwing:
 * an exception on the TUN reader thread would stop protection for the whole phone.
 */
class FuzzTest {
    private val rnd = Random(20261024)

    /** Random bytes, often starting with a plausible IPv4/IPv6 header so the deeper parsers get exercised. */
    private fun sample(): ByteArray {
        val b = ByteArray(rnd.nextInt(0, 200)).also { rnd.nextBytes(it) }
        if (b.size >= 40 && rnd.nextBoolean()) {
            if (rnd.nextBoolean()) { b[0] = 0x45; Dns.put16(b, 2, b.size); b[6] = 0; b[7] = 0; b[9] = (if (rnd.nextBoolean()) 6 else 17).toByte() }
            else { b[0] = 0x60; Dns.put16(b, 4, b.size - 40); b[6] = (if (rnd.nextBoolean()) 6 else 17).toByte() }
        }
        return b
    }

    @Test fun parsersNeverThrow() {
        repeat(200_000) {
            val b = sample()
            Dns.parseQuery(b)
            Dns.cnameTargets(b)
            Dns.minAnswerTtl(b)
            Packets.parseUdp(b, b.size)
            Ip.parse(b, b.size)?.let { Ip.parseTcp(b, it) }
            DomainSet.parseLine(String(b, Charsets.ISO_8859_1))
        }
    }

    @Test fun engineSurvivesGarbage() {
        val e = FlowEngine(1_500, {}, object : Dialer {
            override fun connectTcp(dst: InetSocketAddress, timeoutMs: Int): Dialer.Tcp = throw java.io.IOException("offline")
            override fun openUdp(dst: InetSocketAddress): Dialer.Udp = throw java.io.IOException("offline")
        }, Executors.newCachedThreadPool())
        repeat(100_000) { val b = sample(); e.onPacket(b, b.size) }
        e.tick()
        e.shutdown()
    }
}
