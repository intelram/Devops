package dev.netpilot.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PacketsTest {
    private val client4 = byteArrayOf(10, 111, 222.toByte(), 2)
    private val dns4 = byteArrayOf(10, 111, 222.toByte(), 1)

    private fun udp4(payload: ByteArray, flagsFrag: Int = 0): ByteArray {
        val fake = UdpPacket(4, dns4, client4, 53, 40000, payload) // reply() swaps, giving client -> dns
        return Packets.reply(fake, payload).also { if (flagsFrag != 0) Dns.put16(it, 6, flagsFrag) }
    }

    @Test fun ipv4RoundTripAndChecksums() {
        val payload = "hello-dns".toByteArray()
        val pkt = udp4(payload)
        val p = assertNotNull(Packets.parseUdp(pkt, pkt.size))
        assertEquals(4, p.ipVersion)
        assertContentEquals(client4, p.src); assertContentEquals(dns4, p.dst)
        assertEquals(40000, p.srcPort); assertEquals(53, p.dstPort)
        assertContentEquals(payload, p.payload)
        // A valid header checksums to zero.
        assertEquals(0, Packets.checksum(pkt, 0, 20, 0L))
        // UDP checksum over pseudo header + segment is zero when correct.
        val pseudo = sum(client4) + sum(dns4) + 17 + (8 + payload.size)
        assertEquals(0, Packets.checksum(pkt, 20, 8 + payload.size, pseudo))
    }

    @Test fun replySwapsEndpoints() {
        val req = Packets.parseUdp(udp4(ByteArray(3)), 31)!!
        val reply = Packets.reply(req, byteArrayOf(9, 9))
        val r = Packets.parseUdp(reply, reply.size)!!
        assertContentEquals(req.dst, r.src); assertContentEquals(req.src, r.dst)
        assertEquals(req.dstPort, r.srcPort); assertEquals(req.srcPort, r.dstPort)
    }

    @Test fun ipv6RoundTrip() {
        val a = ByteArray(16).also { it[0] = 0xfd.toByte(); it[15] = 1 }
        val b = ByteArray(16).also { it[0] = 0xfd.toByte(); it[15] = 2 }
        val pkt = Packets.reply(UdpPacket(6, a, b, 53, 5555, byteArrayOf(1, 2, 3)), byteArrayOf(1, 2, 3))
        val p = assertNotNull(Packets.parseUdp(pkt, pkt.size))
        assertEquals(6, p.ipVersion); assertContentEquals(b, p.src); assertEquals(5555, p.srcPort)
        assertContentEquals(byteArrayOf(1, 2, 3), p.payload)
        val pseudo = sum(b) + sum(a) + 17 + 11
        assertEquals(0, Packets.checksum(pkt, 40, 11, pseudo))
    }

    @Test fun dropsFragmentsTcpAndShortPackets() {
        assertNull(Packets.parseUdp(udp4(ByteArray(4), flagsFrag = 0x2000), 32))
        val tcp = udp4(ByteArray(4)).also { it[9] = 6 }
        assertNull(Packets.parseUdp(tcp, tcp.size))
        assertNull(Packets.parseUdp(ByteArray(10), 10))
    }

    private fun sum(b: ByteArray): Long { var s = 0L; var i = 0; while (i < b.size) { s += Dns.u16(b, i); i += 2 }; return s }
}
