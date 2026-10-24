package dev.netpilot.core

/** An IPv4 or IPv6 header as read from the TUN interface (no IPv6 extension headers, no fragments). */
class IpHeader(
    val version: Int,
    val src: ByteArray,
    val dst: ByteArray,
    val protocol: Int,
    val payloadOffset: Int,
    val payloadLength: Int,
)

/** A TCP segment inside [buf]; the payload is buf[payloadOffset, payloadOffset + payloadLength). */
class TcpSegment(
    val srcPort: Int,
    val dstPort: Int,
    val seq: Int,
    val ack: Int,
    val flags: Int,
    val window: Int,
    /** From the SYN's options, else null. */
    val mss: Int?,
    val windowScale: Int?,
    val buf: ByteArray,
    val payloadOffset: Int,
    val payloadLength: Int,
) {
    fun has(flag: Int) = flags and flag != 0
}

/** IP and TCP codec for the forwarding engine. Anything malformed or unsupported returns null. */
object Ip {
    const val TCP = 6
    const val UDP = 17

    const val FIN = 0x01
    const val SYN = 0x02
    const val RST = 0x04
    const val PSH = 0x08
    const val ACK = 0x10

    fun headerLength(version: Int) = if (version == 4) 20 else 40

    fun parse(b: ByteArray, len: Int): IpHeader? {
        if (len < 20) return null
        return when ((b[0].toInt() and 0xFF) shr 4) {
            4 -> {
                val ihl = (b[0].toInt() and 0x0F) * 4
                val total = Dns.u16(b, 2)
                if (ihl < 20 || total > len || total < ihl) return null
                if (Dns.u16(b, 6) and 0x3FFF != 0) return null // fragment
                IpHeader(4, b.copyOfRange(12, 16), b.copyOfRange(16, 20), b[9].toInt() and 0xFF, ihl, total - ihl)
            }
            6 -> {
                if (len < 40) return null
                val payloadLen = Dns.u16(b, 4)
                if (40 + payloadLen > len) return null
                IpHeader(6, b.copyOfRange(8, 24), b.copyOfRange(24, 40), b[6].toInt() and 0xFF, 40, payloadLen)
            }
            else -> null
        }
    }

    fun parseTcp(b: ByteArray, ip: IpHeader): TcpSegment? {
        if (ip.protocol != TCP || ip.payloadLength < 20) return null
        val o = ip.payloadOffset
        val dataOff = ((b[o + 12].toInt() and 0xFF) shr 4) * 4
        if (dataOff < 20 || dataOff > ip.payloadLength) return null
        val flags = b[o + 13].toInt() and 0x3F
        var mss: Int? = null
        var ws: Int? = null
        if (flags and SYN != 0) {
            var p = o + 20
            val end = o + dataOff
            while (p < end) {
                val kind = b[p].toInt() and 0xFF
                if (kind == 0) break
                if (kind == 1) { p++; continue }
                if (p + 1 >= end) break
                val l = b[p + 1].toInt() and 0xFF
                if (l < 2 || p + l > end) break
                if (kind == 2 && l == 4) mss = Dns.u16(b, p + 2)
                if (kind == 3 && l == 3) ws = (b[p + 2].toInt() and 0xFF).coerceAtMost(14)
                p += l
            }
        }
        return TcpSegment(Dns.u16(b, o), Dns.u16(b, o + 2), u32(b, o + 4), u32(b, o + 8), flags, Dns.u16(b, o + 14),
            mss, ws, b, o + dataOff, ip.payloadLength - dataOff)
    }

    /**
     * Builds an IP packet carrying one TCP segment from [src]:[srcPort] to [dst]:[dstPort].
     * [options] must be a multiple of 4 bytes.
     */
    fun buildTcp(
        version: Int, src: ByteArray, dst: ByteArray, srcPort: Int, dstPort: Int,
        seq: Int, ack: Int, flags: Int, window: Int,
        data: ByteArray = EMPTY, off: Int = 0, len: Int = 0, options: ByteArray = EMPTY,
    ): ByteArray {
        val h = headerLength(version)
        val tcpLen = 20 + options.size + len
        val out = ByteArray(h + tcpLen)
        writeIp(out, version, src, dst, TCP, tcpLen)
        Dns.put16(out, h, srcPort)
        Dns.put16(out, h + 2, dstPort)
        put32(out, h + 4, seq)
        put32(out, h + 8, ack)
        out[h + 12] = (((20 + options.size) / 4) shl 4).toByte()
        out[h + 13] = flags.toByte()
        Dns.put16(out, h + 14, window)
        options.copyInto(out, h + 20)
        if (len > 0) System.arraycopy(data, off, out, h + 20 + options.size, len)
        Dns.put16(out, h + 16, Packets.checksum(out, h, tcpLen, pseudoSum(src, dst, TCP, tcpLen)))
        return out
    }

    fun writeIp(out: ByteArray, version: Int, src: ByteArray, dst: ByteArray, protocol: Int, payloadLen: Int) {
        if (version == 4) {
            out[0] = 0x45
            Dns.put16(out, 2, 20 + payloadLen)
            Dns.put16(out, 6, 0x4000) // DF
            out[8] = 64; out[9] = protocol.toByte()
            src.copyInto(out, 12); dst.copyInto(out, 16)
            Dns.put16(out, 10, Packets.checksum(out, 0, 20, 0L))
        } else {
            out[0] = 0x60
            Dns.put16(out, 4, payloadLen)
            out[6] = protocol.toByte(); out[7] = 64
            src.copyInto(out, 8); dst.copyInto(out, 24)
        }
    }

    fun pseudoSum(src: ByteArray, dst: ByteArray, protocol: Int, len: Int): Long =
        sum(src) + sum(dst) + protocol + len

    private fun sum(b: ByteArray): Long {
        var s = 0L
        var i = 0
        while (i + 1 < b.size) { s += Dns.u16(b, i); i += 2 }
        return s
    }

    fun u32(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xFF) shl 24) or ((b[o + 1].toInt() and 0xFF) shl 16) or
            ((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)

    fun put32(b: ByteArray, o: Int, v: Int) {
        b[o] = (v ushr 24).toByte(); b[o + 1] = (v ushr 16).toByte(); b[o + 2] = (v ushr 8).toByte(); b[o + 3] = v.toByte()
    }

    private val EMPTY = ByteArray(0)
}
