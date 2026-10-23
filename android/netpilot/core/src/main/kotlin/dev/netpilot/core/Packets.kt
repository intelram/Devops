package dev.netpilot.core

/** A UDP datagram read from the VPN's TUN interface (IPv4 or IPv6 without extension headers). */
class UdpPacket(
    val ipVersion: Int,
    val src: ByteArray,
    val dst: ByteArray,
    val srcPort: Int,
    val dstPort: Int,
    val payload: ByteArray,
)

/** Minimal IP/UDP codec for the DNS-only tunnel. Anything it does not understand is dropped. */
object Packets {
    private const val UDP = 17

    fun parseUdp(buf: ByteArray, len: Int): UdpPacket? {
        if (len < 1) return null
        return when ((buf[0].toInt() and 0xFF) shr 4) {
            4 -> parse4(buf, len)
            6 -> parse6(buf, len)
            else -> null
        }
    }

    private fun parse4(b: ByteArray, len: Int): UdpPacket? {
        if (len < 28) return null
        val ihl = (b[0].toInt() and 0x0F) * 4
        val total = Dns.u16(b, 2)
        if (ihl < 20 || total > len || total < ihl + 8) return null
        if (b[9].toInt() and 0xFF != UDP) return null
        if (Dns.u16(b, 6) and 0x3FFF != 0) return null        // fragmented (MF or offset): drop
        val u = ihl
        val udpLen = Dns.u16(b, u + 4)
        if (udpLen < 8 || u + udpLen > total) return null
        return UdpPacket(4, b.copyOfRange(12, 16), b.copyOfRange(16, 20), Dns.u16(b, u), Dns.u16(b, u + 2),
            b.copyOfRange(u + 8, u + udpLen))
    }

    private fun parse6(b: ByteArray, len: Int): UdpPacket? {
        if (len < 48) return null
        if (b[6].toInt() and 0xFF != UDP) return null          // extension headers not supported
        val payloadLen = Dns.u16(b, 4)
        if (40 + payloadLen > len || payloadLen < 8) return null
        val udpLen = Dns.u16(b, 44)
        if (udpLen < 8 || udpLen > payloadLen) return null
        return UdpPacket(6, b.copyOfRange(8, 24), b.copyOfRange(24, 40), Dns.u16(b, 40), Dns.u16(b, 42),
            b.copyOfRange(48, 40 + udpLen))
    }

    /** Builds the reply datagram: addresses and ports swapped, checksums filled in. */
    fun reply(req: UdpPacket, payload: ByteArray): ByteArray {
        val udpLen = 8 + payload.size
        return if (req.ipVersion == 4) {
            val out = ByteArray(20 + udpLen)
            out[0] = 0x45
            Dns.put16(out, 2, out.size)
            Dns.put16(out, 6, 0x4000)                          // DF
            out[8] = 64; out[9] = UDP.toByte()
            req.dst.copyInto(out, 12); req.src.copyInto(out, 16)
            Dns.put16(out, 10, checksum(out, 0, 20, 0L))
            writeUdp(out, 20, req, payload, pseudoSum(req.dst, req.src, udpLen))
            out
        } else {
            val out = ByteArray(40 + udpLen)
            out[0] = 0x60
            Dns.put16(out, 4, udpLen)
            out[6] = UDP.toByte(); out[7] = 64
            req.dst.copyInto(out, 8); req.src.copyInto(out, 24)
            writeUdp(out, 40, req, payload, pseudoSum(req.dst, req.src, udpLen))
            out
        }
    }

    private fun writeUdp(out: ByteArray, o: Int, req: UdpPacket, payload: ByteArray, pseudo: Long) {
        Dns.put16(out, o, req.dstPort)
        Dns.put16(out, o + 2, req.srcPort)
        Dns.put16(out, o + 4, 8 + payload.size)
        payload.copyInto(out, o + 8)
        val c = checksum(out, o, 8 + payload.size, pseudo)
        Dns.put16(out, o + 6, if (c == 0) 0xFFFF else c)        // 0 means "no checksum" in UDP
    }

    private fun pseudoSum(src: ByteArray, dst: ByteArray, udpLen: Int): Long {
        var s = sum(src, 0, src.size) + sum(dst, 0, dst.size)
        s += UDP + udpLen
        return s
    }

    private fun sum(b: ByteArray, off: Int, len: Int): Long {
        var s = 0L
        var i = off
        while (i + 1 < off + len) { s += Dns.u16(b, i); i += 2 }
        if (i < off + len) s += (b[i].toInt() and 0xFF) shl 8
        return s
    }

    /** RFC 1071 Internet checksum over [len] bytes plus a pre-summed pseudo header. */
    fun checksum(b: ByteArray, off: Int, len: Int, initial: Long): Int {
        var s = initial + sum(b, off, len)
        while (s shr 16 != 0L) s = (s and 0xFFFF) + (s shr 16)
        return s.inv().toInt() and 0xFFFF
    }
}
