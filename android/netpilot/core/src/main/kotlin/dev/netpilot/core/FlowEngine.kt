package dev.netpilot.core

import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Opens real sockets for the engine, each on a network chosen by the caller (e.g. Wi-Fi or mobile data). */
interface Dialer {
    class Tcp(val socket: Socket, val linkId: String)
    class Udp(val socket: DatagramSocket, val linkId: String)

    /** A connected socket to [dst], or throws [IOException]. */
    fun connectTcp(dst: InetSocketAddress, timeoutMs: Int): Tcp
    fun openUdp(dst: InetSocketAddress): Udp
}

/**
 * Forwards every TCP connection and UDP flow from the TUN interface to a real socket opened
 * by [dialer], and copies the data back. This is what lets NetPilot choose a network per
 * connection: each app connection ends here and is re-opened on Wi-Fi or mobile data.
 *
 * The TCP side towards apps is a small, deliberately simple implementation: in-order
 * delivery only (the TUN link doesn't lose or reorder packets in practice), cumulative ACKs,
 * window scaling, and go-back retransmission on a timer. It never inspects or changes the
 * data. ICMP, IP fragments and IPv6 extension headers are dropped.
 *
 * [write] must be thread-safe: it is called from many threads.
 */
class FlowEngine(
    private val mtu: Int,
    private val write: (ByteArray) -> Unit,
    private val dialer: Dialer,
    private val executor: ExecutorService,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    class LinkTraffic(val up: AtomicLong = AtomicLong(), val down: AtomicLong = AtomicLong(), val connections: AtomicInteger = AtomicInteger())

    /** Bytes and open connections per network id, for the screen. */
    val traffic = ConcurrentHashMap<String, LinkTraffic>()

    private val tcp = ConcurrentHashMap<FlowKey, TcpSession>()
    /** Bytes held for all connections (waiting to go out, or sent to apps but unacknowledged). */
    private val buffered = AtomicLong()
    private val udp = ConcurrentHashMap<FlowKey, UdpFlow>()
    private val random = SecureRandom()
    @Volatile private var closed = false

    val tcpCount: Int get() = tcp.size
    val udpCount: Int get() = udp.size

    private data class FlowKey(val src: InetAddress, val srcPort: Int, val dst: InetAddress, val dstPort: Int)

    /** Feeds one packet read from the TUN interface. Called from the single TUN reader thread. */
    fun onPacket(buf: ByteArray, len: Int) {
        if (closed) return
        val ip = Ip.parse(buf, len) ?: return
        when (ip.protocol) {
            Ip.TCP -> onTcp(ip, Ip.parseTcp(buf, ip) ?: return)
            Ip.UDP -> onUdp(Packets.parseUdp(buf, len) ?: return)
        }
    }

    /** Called about every 100 ms: retransmissions and clean-up. */
    fun tick() {
        val now = clock()
        for (s in tcp.values) s.tick(now)
        for (f in udp.values) if (now - f.lastActive > f.idleMs) f.close()
    }

    fun shutdown() {
        closed = true
        tcp.values.forEach { it.abort(sendRst = true) }
        udp.values.forEach { it.close() }
    }

    private fun linkTraffic(id: String) = traffic.getOrPut(id) { LinkTraffic() }

    // ------------------------------------------------------------------------------- TCP

    private fun onTcp(ip: IpHeader, seg: TcpSegment) {
        val key = FlowKey(InetAddress.getByAddress(ip.src), seg.srcPort, InetAddress.getByAddress(ip.dst), seg.dstPort)
        val s = tcp[key]
        if (s != null) { s.onSegment(seg); return }
        if (seg.has(Ip.RST)) return
        if (!seg.has(Ip.SYN) || seg.has(Ip.ACK) || tcp.size >= MAX_TCP) { resetFor(ip, seg); return }
        val n = TcpSession(key, ip, seg)
        tcp[key] = n
        try { executor.execute(n::connectAndRead) } catch (e: Exception) { tcp.remove(key); resetFor(ip, seg) }
    }

    /** RST for a segment that belongs to no connection (RFC 793 reset generation). */
    private fun resetFor(ip: IpHeader, seg: TcpSegment) {
        val pkt = if (seg.has(Ip.ACK)) {
            Ip.buildTcp(ip.version, ip.dst, ip.src, seg.dstPort, seg.srcPort, seg.ack, 0, Ip.RST, 0)
        } else {
            val consumed = seg.payloadLength + (if (seg.has(Ip.SYN)) 1 else 0) + (if (seg.has(Ip.FIN)) 1 else 0)
            Ip.buildTcp(ip.version, ip.dst, ip.src, seg.dstPort, seg.srcPort, 0, seg.seq + consumed, Ip.RST or Ip.ACK, 0)
        }
        write(pkt)
    }

    /**
     * A segment sent to the app and not yet acknowledged, kept as the finished packet: a
     * retransmission resends it unchanged (an older ACK number and window in it are harmless).
     */
    private class Sent(val seq: Int, val len: Int, val packet: ByteArray, val fin: Boolean = false)

    private inner class TcpSession(private val key: FlowKey, ip: IpHeader, syn: TcpSegment) {
        private val lock = Object()
        private val version = ip.version
        private val appAddr = ip.src
        private val remAddr = ip.dst
        private val appPort = syn.srcPort
        private val remPort = syn.dstPort

        private val scaling = syn.windowScale != null
        private val appShift = syn.windowScale ?: 0
        private val ourShift = if (scaling) OUR_SHIFT else 0
        private val sendMss = minOf(syn.mss ?: 536, mtu - Ip.headerLength(version) - 20).coerceAtLeast(536)

        private var rcvNxt = syn.seq + 1
        private val iss = random.nextInt()
        private var sndUna = iss
        private var sndNxt = iss
        private var appWnd = syn.window.toLong()
        private val unacked = ArrayDeque<Sent>()
        private var unackedBytes = 0
        private var lastProgress = clock()
        private var rto = RTO_MIN
        private var retries = 0
        private val created = clock()

        private val upQueue = LinkedBlockingQueue<ByteArray>()
        private val queued = AtomicInteger()
        @Volatile private var advertisedSmall = false

        private var open = false
        private var finished = false
        private var appFin = false
        private var finSent = false
        private var finAcked = false
        private var writerDone = false
        private var socket: Socket? = null
        private var link: LinkTraffic? = null

        fun connectAndRead() {
            val d = try {
                dialer.connectTcp(InetSocketAddress(InetAddress.getByAddress(remAddr), remPort), CONNECT_TIMEOUT_MS)
            } catch (e: Exception) {
                synchronized(lock) { if (!finished) send(Ip.RST or Ip.ACK, seq = iss + 1) }
                finish()
                return
            }
            val traffic = linkTraffic(d.linkId)
            synchronized(lock) {
                if (finished) { runCatching { d.socket.close() }; return }
                socket = d.socket
                link = traffic
                traffic.connections.incrementAndGet()
                open = true
                sendSynAck()
                sndNxt = iss + 1
                sndUna = iss + 1 // the SYN-ACK is retransmitted from the app's SYN, not the timer
                lastProgress = clock()
            }
            try { executor.execute(::writeLoop) } catch (e: Exception) { abort(sendRst = true); return }
            readLoop(d.socket, traffic)
        }

        private fun readLoop(sock: Socket, traffic: LinkTraffic) {
            val buf = ByteArray(READ_BYTES)
            val input = try { sock.getInputStream() } catch (e: IOException) { abort(true); return }
            try {
                while (true) {
                    var room: Int
                    synchronized(lock) {
                        while (true) {
                            if (finished) return
                            room = minOf(appWnd - (sndNxt - sndUna).toLong(), (MAX_IN_FLIGHT - unackedBytes).toLong(),
                                TOTAL_BUFFER - buffered.get()).toInt()
                            if (room > 0) break
                            lock.wait(1_000)
                        }
                    }
                    val n = input.read(buf, 0, minOf(room, buf.size))
                    if (n < 0) {
                        synchronized(lock) {
                            if (finished) return
                            unacked.addLast(Sent(sndNxt, 0, send(Ip.FIN or Ip.ACK), fin = true))
                            sndNxt += 1
                            finSent = true
                        }
                        return
                    }
                    traffic.down.addAndGet(n.toLong())
                    synchronized(lock) {
                        if (finished) return
                        var off = 0
                        while (off < n) {
                            val len = minOf(sendMss, n - off)
                            if (unacked.isEmpty()) lastProgress = clock()
                            // Built straight from the read buffer: one copy per byte, into the packet.
                            val pkt = send(Ip.ACK or (if (off + len == n) Ip.PSH else 0), data = buf, off = off, len = len)
                            unacked.addLast(Sent(sndNxt, len, pkt))
                            unackedBytes += len
                            buffered.addAndGet(len.toLong())
                            sndNxt += len
                            off += len
                        }
                    }
                }
            } catch (e: IOException) {
                abort(sendRst = true)
            } catch (e: InterruptedException) {
                abort(sendRst = true)
            }
        }

        private fun writeLoop() {
            val sock = socket ?: return
            try {
                val out = sock.getOutputStream()
                while (true) {
                    val b = upQueue.take()
                    if (b === EOF) {
                        runCatching { sock.shutdownOutput() }
                        synchronized(lock) { writerDone = true; maybeFinish() }
                        return
                    }
                    if (b === POISON) return
                    out.write(b)
                    link?.up?.addAndGet(b.size.toLong())
                    synchronized(lock) {
                        if (finished) return // finishLocked already released this session's bytes
                        val left = queued.addAndGet(-b.size)
                        buffered.addAndGet(-b.size.toLong())
                        // Tell the app as soon as there's room again, rather than waiting for its next packet.
                        if (advertisedSmall && left < UP_BUFFER / 2) { advertisedSmall = false; send(Ip.ACK) }
                    }
                }
            } catch (e: IOException) {
                abort(sendRst = true)
            } catch (e: InterruptedException) {
                abort(sendRst = true)
            }
        }

        fun onSegment(seg: TcpSegment) {
            synchronized(lock) {
                if (finished) return
                if (seg.has(Ip.RST)) { abortLocked(sendRst = false); return }
                if (seg.has(Ip.SYN) && !seg.has(Ip.ACK)) { if (open) sendSynAck(); return } // repeated SYN
                if (!open) return // still connecting: the app will retransmit
                if (seg.has(Ip.ACK)) onAck(seg)
                var needAck = false
                val len = seg.payloadLength
                if (len > 0) {
                    val skip = rcvNxt - seg.seq
                    when {
                        skip < 0 -> needAck = true // a gap: duplicate ACK, the app resends
                        skip >= len -> needAck = true // already have it
                        appFin -> Unit
                        else -> {
                            val n = len - skip
                            if (queued.get() + n <= UP_BUFFER && buffered.get() + n <= TOTAL_BUFFER) {
                                upQueue.put(seg.buf.copyOfRange(seg.payloadOffset + skip, seg.payloadOffset + len))
                                queued.addAndGet(n)
                                buffered.addAndGet(n.toLong())
                                rcvNxt += n
                            }
                            needAck = true
                        }
                    }
                }
                if (seg.has(Ip.FIN) && !appFin && seg.seq + len == rcvNxt) {
                    appFin = true
                    rcvNxt += 1
                    upQueue.put(EOF)
                    needAck = true
                }
                if (needAck) send(Ip.ACK)
                maybeFinish()
            }
        }

        private fun onAck(seg: TcpSegment) {
            val ack = seg.ack
            if (ack - sndNxt > 0 || ack - sndUna < 0) return // acknowledges nothing we sent
            if (ack - sndUna > 0) {
                sndUna = ack
                while (unacked.isNotEmpty()) {
                    val h = unacked.peekFirst()
                    // A partly acknowledged segment stays whole; resending bytes the app has is harmless.
                    if (h.seq + h.len + (if (h.fin) 1 else 0) - ack > 0) break
                    unacked.pollFirst()
                    unackedBytes -= h.len
                    buffered.addAndGet(-h.len.toLong())
                }
                lastProgress = clock()
                rto = RTO_MIN
                retries = 0
            }
            if (finSent && ack == sndNxt) finAcked = true
            appWnd = seg.window.toLong() shl appShift
            lock.notifyAll()
        }

        fun tick(now: Long) {
            synchronized(lock) {
                if (finished) return
                if (!open) { if (now - created > CONNECT_TIMEOUT_MS + 5_000) abortLocked(true); return }
                if (unacked.isEmpty() || now - lastProgress < rto) return
                if (++retries > MAX_RETRIES) { abortLocked(sendRst = true); return }
                // Go back: resend from the oldest unacknowledged byte, as much as the window allows.
                var budget = maxOf(appWnd, sendMss.toLong())
                for (s in unacked) {
                    if (budget <= 0) break
                    write(s.packet)
                    budget -= s.len
                }
                lastProgress = now
                rto = minOf(rto * 2, RTO_MAX)
            }
        }

        private fun maybeFinish() {
            if (appFin && finAcked && writerDone) finishLocked()
        }

        private fun sendSynAck() {
            val opts = ByteArray(if (scaling) 8 else 4)
            opts[0] = 2; opts[1] = 4; Dns.put16(opts, 2, mtu - Ip.headerLength(version) - 20)
            if (scaling) { opts[4] = 1; opts[5] = 3; opts[6] = 3; opts[7] = ourShift.toByte() }
            write(Ip.buildTcp(version, remAddr, appAddr, remPort, appPort, iss, rcvNxt, Ip.SYN or Ip.ACK,
                minOf(UP_BUFFER, 65_535), options = opts))
        }

        private fun ourWindow(): Int {
            val free = (UP_BUFFER - queued.get()).coerceAtLeast(0)
            if (free < UP_BUFFER / 2) advertisedSmall = true
            return (free shr ourShift).coerceAtMost(65_535)
        }

        private fun send(flags: Int, seq: Int = sndNxt, data: ByteArray = EMPTY, off: Int = 0, len: Int = data.size): ByteArray {
            val p = Ip.buildTcp(version, remAddr, appAddr, remPort, appPort, seq, rcvNxt, flags, ourWindow(), data, off, len)
            write(p)
            return p
        }

        fun abort(sendRst: Boolean) = synchronized(lock) { abortLocked(sendRst) }

        private fun abortLocked(sendRst: Boolean) {
            if (finished) return
            if (sendRst) send(Ip.RST or Ip.ACK)
            finishLocked()
        }

        private fun finish() = synchronized(lock) { finishLocked() }

        private fun finishLocked() {
            if (finished) return
            finished = true
            tcp.remove(key)
            if (open) link?.connections?.decrementAndGet()
            runCatching { socket?.close() }
            buffered.addAndGet(-(queued.getAndSet(0) + unackedBytes).toLong())
            unackedBytes = 0
            unacked.clear()
            upQueue.clear()
            upQueue.put(POISON)
            lock.notifyAll()
        }
    }

    // ------------------------------------------------------------------------------- UDP

    private fun onUdp(p: UdpPacket) {
        val key = FlowKey(InetAddress.getByAddress(p.src), p.srcPort, InetAddress.getByAddress(p.dst), p.dstPort)
        var f = udp[key]
        if (f == null) {
            if (udp.size >= MAX_UDP) return
            val d = try { dialer.openUdp(InetSocketAddress(InetAddress.getByAddress(p.dst), p.dstPort)) } catch (e: Exception) { return }
            f = UdpFlow(key, p, d)
            udp[key] = f
            try { executor.execute(f::readLoop) } catch (e: Exception) { f.close(); return }
        }
        f.send(p.payload)
    }

    private inner class UdpFlow(private val key: FlowKey, private val template: UdpPacket, d: Dialer.Udp) {
        private val socket = d.socket
        private val link = linkTraffic(d.linkId)
        @Volatile var lastActive = clock()
        /** DNS-style request/response flows are dropped sooner than streams such as QUIC. */
        val idleMs = if (template.dstPort == 53) 20_000L else 120_000L

        init { link.connections.incrementAndGet() }

        fun send(payload: ByteArray) {
            lastActive = clock()
            try {
                socket.send(DatagramPacket(payload, payload.size))
                link.up.addAndGet(payload.size.toLong())
            } catch (e: IOException) { close() }
        }

        fun readLoop() {
            val buf = ByteArray(65_535)
            val maxPayload = mtu - Ip.headerLength(template.ipVersion) - 8
            try {
                socket.soTimeout = 1_000
                while (!socket.isClosed) {
                    val dp = DatagramPacket(buf, buf.size)
                    try { socket.receive(dp) } catch (e: SocketTimeoutException) { continue }
                    lastActive = clock()
                    if (dp.length > maxPayload) continue // can't be delivered whole through the TUN
                    link.down.addAndGet(dp.length.toLong())
                    write(Packets.reply(template, buf.copyOf(dp.length)))
                }
            } catch (e: IOException) {
                // closed or network gone
            } finally {
                close()
            }
        }

        fun close() {
            if (udp.remove(key, this)) link.connections.decrementAndGet()
            socket.close()
        }
    }

    companion object {
        // Each connection uses two threads and each UDP flow one, so these also cap threads.
        const val MAX_TCP = 768
        const val MAX_UDP = 256
        /** All connections together never hold more than this in memory. */
        private const val TOTAL_BUFFER = 64L shl 20
        const val CONNECT_TIMEOUT_MS = 10_000
        /** Bytes accepted from an app before the remote side has taken them. */
        private const val UP_BUFFER = 256 shl 10
        /** Bytes sent to an app but not yet acknowledged. */
        private const val MAX_IN_FLIGHT = 512 shl 10
        private const val OUR_SHIFT = 5
        private const val READ_BYTES = 64 * 1024
        private const val RTO_MIN = 300L
        private const val RTO_MAX = 4_000L
        private const val MAX_RETRIES = 8
        private val EMPTY = ByteArray(0)
        private val EOF = ByteArray(0)
        private val POISON = ByteArray(0)
    }
}
