package com.fz.yqlandroid.manager.srt

import java.io.ByteArrayOutputStream

/**
 * 只含一路 H.264 视频的 MPEG-TS 封装（ISO/IEC 13818-1）。拷自 test-android `TsMuxer`
 * （有单测：ffmpeg -err_detect explode 严格解码零报错）。
 *
 * - PID：PAT=0x0000，PMT=0x1000，视频=0x0100（同时作 PCR PID）
 * - 同 silu：每一帧前都发 PAT/PMT；SPS/PPS 从码流里拆出保存，每个 IDR 前插入
 * - 每个访问单元前加 AUD；无 B 帧，PES 只带 PTS（DTS=PTS）
 * - PCR 放在每个 PES 的首包，比 PTS 早 [PCR_LEAD_90K]，给解码端留缓冲
 */
internal class SrtTsMuxer {
    companion object {
        const val PACKET_SIZE = 188
        private const val PID_PAT = 0x0000
        private const val PID_PMT = 0x1000
        private const val PID_VIDEO = 0x0100
        private const val STREAM_TYPE_H264 = 0x1B
        /** 首帧 PTS 起点 1.4s，PCR 提前量 200ms 不会算出负值 */
        private const val PTS_BASE_90K = 126_000L
        private const val PCR_LEAD_90K = 18_000L
        private val AUD = byteArrayOf(0, 0, 0, 1, 0x09, 0xF0.toByte())
        private val START_CODE = byteArrayOf(0, 0, 0, 1)
    }

    private val cc = IntArray(0x2000)
    private var firstPtsUs = -1L
    private var lastPtsUs = -1L
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null

    /** 把一个编码帧封装成若干个 188 字节 TS 包，返回拼接后的字节（长度为 188 的整数倍） */
    fun mux(frame: ByteArray, ptsUs: Long, isKey: Boolean): ByteArray {
        if (firstPtsUs < 0) firstPtsUs = ptsUs
        // 换编码器（切档/切摄像头）后时间戳理论上仍单调；防御：回退则按上一帧 +1ms
        val pts = if (lastPtsUs >= 0 && ptsUs <= lastPtsUs) lastPtsUs + 1000 else ptsUs
        lastPtsUs = pts
        val pts90k = PTS_BASE_90K + (pts - firstPtsUs) * 9 / 100
        val out = ByteArrayOutputStream(frame.size + frame.size / 180 * 4 + 1024)
        out.write(psiPacket(PID_PAT, patSection()))
        out.write(psiPacket(PID_PMT, pmtSection()))
        val pes = pesPacket(buildAccessUnit(frame), pts90k)
        writePes(out, pes, pcr90k = pts90k - PCR_LEAD_90K, randomAccess = isKey)
        return out.toByteArray()
    }

    private fun buildAccessUnit(frame: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(frame.size + 64)
        out.write(AUD)
        for (nal in splitAnnexB(frame)) {
            when (nal[0].toInt() and 0x1F) {
                7 -> sps = nal
                8 -> pps = nal
                9 -> {}
                else -> {
                    val s = sps
                    val p = pps
                    if ((nal[0].toInt() and 0x1F) == 5 && s != null && p != null) {
                        out.write(START_CODE); out.write(s)
                        out.write(START_CODE); out.write(p)
                    }
                    out.write(START_CODE); out.write(nal)
                }
            }
        }
        return out.toByteArray()
    }

    /** Annex-B 码流切成 NAL（不含起始码） */
    private fun splitAnnexB(data: ByteArray): List<ByteArray> {
        val nals = ArrayList<ByteArray>()
        var start = -1
        var i = 0
        while (i + 2 < data.size) {
            if (data[i].toInt() == 0 && data[i + 1].toInt() == 0 && data[i + 2].toInt() == 1) {
                if (start >= 0) {
                    var end = i
                    while (end > start && data[end - 1].toInt() == 0) end--
                    if (end > start) nals += data.copyOfRange(start, end)
                }
                i += 3
                start = i
            } else i++
        }
        if (start in 0 until data.size) nals += data.copyOfRange(start, data.size)
        if (nals.isEmpty() && data.isNotEmpty()) nals += data
        return nals
    }

    // ---------- PES ----------

    private fun pesPacket(es: ByteArray, pts90k: Long): ByteArray {
        val h = ByteArray(14)
        h[0] = 0; h[1] = 0; h[2] = 1
        h[3] = 0xE0.toByte()
        h[4] = 0; h[5] = 0
        h[6] = 0x84.toByte()
        h[7] = 0x80.toByte()
        h[8] = 5
        writeTimestamp(h, 9, 0x2, pts90k)
        return h + es
    }

    private fun writeTimestamp(b: ByteArray, off: Int, prefix: Int, ts: Long) {
        b[off] = ((prefix shl 4) or (((ts shr 30) and 0x07).toInt() shl 1) or 1).toByte()
        b[off + 1] = ((ts shr 22) and 0xFF).toByte()
        b[off + 2] = ((((ts shr 15) and 0x7F).toInt() shl 1) or 1).toByte()
        b[off + 3] = ((ts shr 7) and 0xFF).toByte()
        b[off + 4] = (((ts and 0x7F).toInt() shl 1) or 1).toByte()
    }

    private fun writePes(out: ByteArrayOutputStream, pes: ByteArray, pcr90k: Long, randomAccess: Boolean) {
        var pos = 0
        var first = true
        while (pos < pes.size) {
            val pkt = ByteArray(PACKET_SIZE) { 0xFF.toByte() }
            val remaining = pes.size - pos

            var afBody = 0
            if (first) afBody = 1 + 6
            var afTotal = if (afBody > 0) afBody + 1 else 0
            var payload = PACKET_SIZE - 4 - afTotal
            if (remaining < payload) {
                afTotal += payload - remaining
                payload = remaining
            }

            pkt[0] = 0x47
            pkt[1] = ((if (first) 0x40 else 0) or ((PID_VIDEO shr 8) and 0x1F)).toByte()
            pkt[2] = (PID_VIDEO and 0xFF).toByte()
            val afc = if (afTotal > 0) 0x30 else 0x10
            pkt[3] = (afc or nextCc(PID_VIDEO)).toByte()

            var p = 4
            if (afTotal > 0) {
                pkt[p++] = (afTotal - 1).toByte()
                if (afTotal > 1) {
                    var flags = 0
                    if (first) {
                        flags = 0x10
                        if (randomAccess) flags = flags or 0x40
                    }
                    pkt[p++] = flags.toByte()
                    if (first) {
                        writePcr(pkt, p, if (pcr90k < 0) 0 else pcr90k)
                        p += 6
                    }
                    p = 4 + afTotal
                }
            }
            System.arraycopy(pes, pos, pkt, p, payload)
            pos += payload
            first = false
            out.write(pkt)
        }
    }

    private fun writePcr(b: ByteArray, off: Int, pcrBase: Long) {
        b[off] = ((pcrBase shr 25) and 0xFF).toByte()
        b[off + 1] = ((pcrBase shr 17) and 0xFF).toByte()
        b[off + 2] = ((pcrBase shr 9) and 0xFF).toByte()
        b[off + 3] = ((pcrBase shr 1) and 0xFF).toByte()
        b[off + 4] = ((((pcrBase and 1).toInt()) shl 7) or 0x7E).toByte()
        b[off + 5] = 0
    }

    // ---------- PSI ----------

    private fun patSection(): ByteArray {
        val body = byteArrayOf(
            0x00, 0x01,
            0xC1.toByte(),
            0x00, 0x00,
            0x00, 0x01,
            (0xE0 or (PID_PMT shr 8)).toByte(), (PID_PMT and 0xFF).toByte(),
        )
        return section(0x00, body)
    }

    private fun pmtSection(): ByteArray {
        val body = byteArrayOf(
            0x00, 0x01,
            0xC1.toByte(),
            0x00, 0x00,
            (0xE0 or (PID_VIDEO shr 8)).toByte(), (PID_VIDEO and 0xFF).toByte(),
            0xF0.toByte(), 0x00,
            STREAM_TYPE_H264.toByte(),
            (0xE0 or (PID_VIDEO shr 8)).toByte(), (PID_VIDEO and 0xFF).toByte(),
            0xF0.toByte(), 0x00,
        )
        return section(0x02, body)
    }

    private fun section(tableId: Int, body: ByteArray): ByteArray {
        val len = body.size + 4
        val head = byteArrayOf(
            tableId.toByte(),
            (0xB0 or ((len shr 8) and 0x0F)).toByte(),
            (len and 0xFF).toByte(),
        )
        val noCrc = head + body
        val crc = crc32Mpeg(noCrc)
        return noCrc + byteArrayOf(
            (crc ushr 24).toByte(), (crc ushr 16).toByte(), (crc ushr 8).toByte(), crc.toByte()
        )
    }

    private fun psiPacket(pid: Int, section: ByteArray): ByteArray {
        val pkt = ByteArray(PACKET_SIZE) { 0xFF.toByte() }
        pkt[0] = 0x47
        pkt[1] = (0x40 or ((pid shr 8) and 0x1F)).toByte()
        pkt[2] = (pid and 0xFF).toByte()
        pkt[3] = (0x10 or nextCc(pid)).toByte()
        pkt[4] = 0
        System.arraycopy(section, 0, pkt, 5, section.size)
        return pkt
    }

    private fun nextCc(pid: Int): Int {
        val v = cc[pid]
        cc[pid] = (v + 1) and 0x0F
        return v
    }

    private fun crc32Mpeg(data: ByteArray): Int {
        var crc = -1
        for (b in data) {
            crc = crc xor ((b.toInt() and 0xFF) shl 24)
            repeat(8) {
                crc = if (crc and 0x80000000.toInt() != 0) (crc shl 1) xor 0x04C11DB7 else crc shl 1
            }
        }
        return crc
    }
}
