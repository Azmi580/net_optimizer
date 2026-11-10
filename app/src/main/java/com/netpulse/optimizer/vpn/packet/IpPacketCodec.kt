package com.netpulse.optimizer.vpn.packet

import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min

/** Big-endian helpers shared by the IP and DNS codecs. */
internal object Bytes {
    fun u16(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)

    fun u32(b: ByteArray, off: Int): Long =
        (u16(b, off).toLong() shl 16) or u16(b, off + 2).toLong()

    fun put16(b: ByteArray, off: Int, value: Int) {
        b[off] = (value ushr 8).toByte()
        b[off + 1] = value.toByte()
    }

    fun put32(b: ByteArray, off: Int, value: Long) {
        put16(b, off, ((value ushr 16) and 0xFFFF).toInt())
        put16(b, off + 2, (value and 0xFFFF).toInt())
    }
}

sealed interface TunPacket {
    val srcIp: ByteArray
    val dstIp: ByteArray
    val srcPort: Int
    val dstPort: Int

    class Udp(
        override val srcIp: ByteArray,
        override val dstIp: ByteArray,
        override val srcPort: Int,
        override val dstPort: Int,
        val payload: ByteArray,
    ) : TunPacket

    class Tcp(
        override val srcIp: ByteArray,
        override val dstIp: ByteArray,
        override val srcPort: Int,
        override val dstPort: Int,
        val seq: Long,
        val ack: Long,
        val flags: Int,
        val payloadLength: Int,
    ) : TunPacket {
        val isSyn: Boolean get() = (flags and TCP_SYN) != 0
        val isFin: Boolean get() = (flags and TCP_FIN) != 0
        val hasAck: Boolean get() = (flags and TCP_ACK) != 0
        val isRst: Boolean get() = (flags and TCP_RST) != 0
    }

    companion object {
        const val TCP_FIN = 0x01
        const val TCP_SYN = 0x02
        const val TCP_RST = 0x04
        const val TCP_ACK = 0x10
    }
}

/**
 * Minimal, allocation-conscious IPv4 / UDP / TCP codec for the DNS-only TUN.
 *
 * Only DNS virtual IPs are routed into the TUN, so the codec handles exactly three cases:
 *  1. UDP/53 queries -> parsed for forwarding.
 *  2. TCP to the virtual resolver (DNS-over-TCP fallback, opportunistic DoT probe on :853) -> answered with RST
 *     so the client resolver fails over instantly instead of waiting for a multi-second SYN timeout.
 *  3. Everything else -> dropped.
 */
object IpPacketCodec {

    private const val IPV4_HEADER = 20
    private const val UDP_HEADER = 8
    private const val TCP_HEADER = 20
    private const val PROTO_TCP = 6
    private const val PROTO_UDP = 17
    private const val FLAG_DF = 0x4000
    private const val FLAG_MF = 0x2000
    private const val DEFAULT_TTL = 64

    private val ipId = AtomicInteger((System.nanoTime() and 0xFFFF).toInt())

    fun parse(buf: ByteArray, length: Int): TunPacket? {
        if (length < IPV4_HEADER) return null
        val version = (buf[0].toInt() ushr 4) and 0x0F
        if (version != 4) return null
        val ihl = (buf[0].toInt() and 0x0F) * 4
        if (ihl < IPV4_HEADER || length < ihl) return null
        val totalLength = Bytes.u16(buf, 2)
        if (totalLength > length || totalLength < ihl) return null
        // Drop fragments: DNS queries from the platform resolver are never fragmented.
        if ((Bytes.u16(buf, 6) and 0x3FFF) != 0) return null

        val protocol = buf[9].toInt() and 0xFF
        val src = buf.copyOfRange(12, 16)
        val dst = buf.copyOfRange(16, 20)

        return when (protocol) {
            PROTO_UDP -> {
                if (totalLength < ihl + UDP_HEADER) return null
                val udpLength = Bytes.u16(buf, ihl + 4)
                if (udpLength < UDP_HEADER || ihl + udpLength > totalLength) return null
                TunPacket.Udp(
                    srcIp = src,
                    dstIp = dst,
                    srcPort = Bytes.u16(buf, ihl),
                    dstPort = Bytes.u16(buf, ihl + 2),
                    payload = buf.copyOfRange(ihl + UDP_HEADER, ihl + udpLength),
                )
            }
            PROTO_TCP -> {
                if (totalLength < ihl + TCP_HEADER) return null
                val dataOffset = ((buf[ihl + 12].toInt() ushr 4) and 0x0F) * 4
                if (dataOffset < TCP_HEADER || ihl + dataOffset > totalLength) return null
                TunPacket.Tcp(
                    srcIp = src,
                    dstIp = dst,
                    srcPort = Bytes.u16(buf, ihl),
                    dstPort = Bytes.u16(buf, ihl + 2),
                    seq = Bytes.u32(buf, ihl + 4),
                    ack = Bytes.u32(buf, ihl + 8),
                    flags = buf[ihl + 13].toInt() and 0x3F,
                    payloadLength = totalLength - ihl - dataOffset,
                )
            }
            else -> null
        }
    }

    /**
     * Builds one or more IPv4 packets carrying a UDP datagram. Datagrams larger than the TUN MTU
     * (e.g. DNSSEC / large TXT answers fetched over TCP upstream) are IP-fragmented instead of truncated.
     */
    fun buildUdp(
        srcIp: ByteArray,
        dstIp: ByteArray,
        srcPort: Int,
        dstPort: Int,
        payload: ByteArray,
        mtu: Int,
    ): List<ByteArray> {
        val udpLength = UDP_HEADER + payload.size
        val udp = ByteArray(udpLength)
        Bytes.put16(udp, 0, srcPort)
        Bytes.put16(udp, 2, dstPort)
        Bytes.put16(udp, 4, udpLength)
        System.arraycopy(payload, 0, udp, UDP_HEADER, payload.size)
        val checksum = checksum(pseudoHeaderSum(srcIp, dstIp, PROTO_UDP, udpLength), udp, 0, udpLength)
        Bytes.put16(udp, 6, if (checksum == 0) 0xFFFF else checksum)

        val id = nextIpId()
        if (udpLength <= mtu - IPV4_HEADER) {
            return listOf(ipv4(srcIp, dstIp, PROTO_UDP, id, FLAG_DF, udp, 0, udpLength))
        }

        val maxFragmentData = ((mtu - IPV4_HEADER) / 8) * 8
        val fragments = ArrayList<ByteArray>((udpLength / maxFragmentData) + 1)
        var offset = 0
        while (offset < udpLength) {
            val chunk = min(maxFragmentData, udpLength - offset)
            val more = offset + chunk < udpLength
            val flagsAndOffset = (if (more) FLAG_MF else 0) or (offset / 8)
            fragments += ipv4(srcIp, dstIp, PROTO_UDP, id, flagsAndOffset, udp, offset, chunk)
            offset += chunk
        }
        return fragments
    }

    /** RFC 793 reset generation for a segment addressed to our virtual resolver. */
    fun buildTcpReset(segment: TunPacket.Tcp): ByteArray? {
        if (segment.isRst) return null
        val tcp = ByteArray(TCP_HEADER)
        Bytes.put16(tcp, 0, segment.dstPort)
        Bytes.put16(tcp, 2, segment.srcPort)
        val flags: Int
        if (segment.hasAck) {
            Bytes.put32(tcp, 4, segment.ack)
            Bytes.put32(tcp, 8, 0)
            flags = TunPacket.TCP_RST
        } else {
            val consumed = segment.payloadLength + (if (segment.isSyn) 1 else 0) + (if (segment.isFin) 1 else 0)
            Bytes.put32(tcp, 4, 0)
            Bytes.put32(tcp, 8, (segment.seq + consumed) and 0xFFFFFFFFL)
            flags = TunPacket.TCP_RST or TunPacket.TCP_ACK
        }
        tcp[12] = (5 shl 4).toByte()
        tcp[13] = flags.toByte()
        Bytes.put16(tcp, 14, 0)
        val checksum = checksum(pseudoHeaderSum(segment.dstIp, segment.srcIp, PROTO_TCP, TCP_HEADER), tcp, 0, TCP_HEADER)
        Bytes.put16(tcp, 16, checksum)
        return ipv4(segment.dstIp, segment.srcIp, PROTO_TCP, nextIpId(), FLAG_DF, tcp, 0, TCP_HEADER)
    }

    private fun ipv4(
        src: ByteArray,
        dst: ByteArray,
        protocol: Int,
        id: Int,
        flagsAndOffset: Int,
        data: ByteArray,
        dataOffset: Int,
        dataLength: Int,
    ): ByteArray {
        val packet = ByteArray(IPV4_HEADER + dataLength)
        packet[0] = 0x45
        packet[1] = 0
        Bytes.put16(packet, 2, IPV4_HEADER + dataLength)
        Bytes.put16(packet, 4, id)
        Bytes.put16(packet, 6, flagsAndOffset)
        packet[8] = DEFAULT_TTL.toByte()
        packet[9] = protocol.toByte()
        System.arraycopy(src, 0, packet, 12, 4)
        System.arraycopy(dst, 0, packet, 16, 4)
        Bytes.put16(packet, 10, checksum(0L, packet, 0, IPV4_HEADER))
        System.arraycopy(data, dataOffset, packet, IPV4_HEADER, dataLength)
        return packet
    }

    private fun nextIpId(): Int = ipId.incrementAndGet() and 0xFFFF

    private fun pseudoHeaderSum(src: ByteArray, dst: ByteArray, protocol: Int, length: Int): Long =
        Bytes.u16(src, 0).toLong() + Bytes.u16(src, 2) + Bytes.u16(dst, 0) + Bytes.u16(dst, 2) + protocol + length

    /** RFC 1071 internet checksum. */
    internal fun checksum(initial: Long, data: ByteArray, offset: Int, length: Int): Int {
        var sum = initial
        var i = offset
        val end = offset + length
        while (i + 1 < end) {
            sum += Bytes.u16(data, i)
            i += 2
        }
        if (i < end) sum += (data[i].toInt() and 0xFF) shl 8
        while ((sum ushr 16) != 0L) sum = (sum and 0xFFFF) + (sum ushr 16)
        return (sum.inv() and 0xFFFF).toInt()
    }
}
