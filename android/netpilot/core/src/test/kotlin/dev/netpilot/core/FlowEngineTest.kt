package dev.netpilot.core

import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Plays the app's side of TCP by hand (IPv6), against the engine and a real echo server. */
class FlowEngineTest {
    private val app = InetAddress.getByName("fd00::2").address
    private val remote = InetAddress.getByName("2001:db8::80").address
    private val fromEngine = LinkedBlockingQueue<ByteArray>()

    private fun engine(port: Int, failConnect: Boolean = false) = FlowEngine(1_500, { fromEngine.put(it) }, object : Dialer {
        override fun connectTcp(dst: InetSocketAddress, timeoutMs: Int): Dialer.Tcp {
            if (failConnect) throw java.net.ConnectException("refused")
            return Dialer.Tcp(Socket("127.0.0.1", port), "wifi")
        }
        override fun openUdp(dst: InetSocketAddress) = Dialer.Udp(DatagramSocket(), "wifi")
    }, Executors.newCachedThreadPool())

    private fun next(): Pair<IpHeader, TcpSegment> {
        val p = assertNotNull(fromEngine.poll(5, TimeUnit.SECONDS), "engine sent nothing")
        val ip = assertNotNull(Ip.parse(p, p.size))
        assertEquals(0, Packets.checksum(p, ip.payloadOffset, ip.payloadLength, Ip.pseudoSum(ip.src, ip.dst, Ip.TCP, ip.payloadLength)), "bad TCP checksum")
        return ip to assertNotNull(Ip.parseTcp(p, ip))
    }

    private fun appSends(e: FlowEngine, seq: Int, ack: Int, flags: Int, data: ByteArray = ByteArray(0), options: ByteArray = ByteArray(0)) {
        val p = Ip.buildTcp(6, app, remote, 40_000, 80, seq, ack, flags, 65_535, data, 0, data.size, options)
        e.onPacket(p, p.size)
    }

    @Test fun ipv6EchoAndOrderlyClose() {
        val server = ServerSocket(0)
        thread { server.accept().use { s -> val b = ByteArray(5); s.getInputStream().read(b); s.getOutputStream().write(b.reversedArray()) } }
        val e = engine(server.localPort)
        val mss = byteArrayOf(2, 4, 0x05, 0xB4.toByte()) // MSS 1460, no window scaling
        appSends(e, 1_000, 0, Ip.SYN, options = mss)
        val (ip, synAck) = next()
        assertEquals(6, ip.version)
        assertTrue(synAck.has(Ip.SYN) && synAck.has(Ip.ACK))
        assertEquals(1_001, synAck.ack)
        val iss = synAck.seq
        appSends(e, 1_001, iss + 1, Ip.ACK or Ip.PSH, "hello".toByteArray())
        var data = ""
        var finSeq: Int? = null
        while (finSeq == null) {
            val (_, s) = next()
            if (s.payloadLength > 0) data += String(s.buf, s.payloadOffset, s.payloadLength)
            if (s.has(Ip.FIN)) finSeq = s.seq
        }
        assertEquals("olleh", data)
        appSends(e, 1_006, finSeq + 1, Ip.ACK or Ip.FIN)
        val deadline = System.currentTimeMillis() + 5_000
        while (e.tcpCount > 0 && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertEquals(0, e.tcpCount, "session not cleaned up")
        assertEquals(5L, e.traffic["wifi"]!!.up.get())
        assertEquals(5L, e.traffic["wifi"]!!.down.get())
    }

    @Test fun refusedConnectionIsReset() {
        val e = engine(1, failConnect = true)
        appSends(e, 7, 0, Ip.SYN)
        val (_, rst) = next()
        assertTrue(rst.has(Ip.RST))
        assertEquals(8, rst.ack)
    }

    @Test fun strayAckGetsReset() {
        val e = engine(1)
        appSends(e, 5, 99, Ip.ACK)
        val (_, rst) = next()
        assertTrue(rst.has(Ip.RST))
        assertEquals(99, rst.seq)
    }
}
