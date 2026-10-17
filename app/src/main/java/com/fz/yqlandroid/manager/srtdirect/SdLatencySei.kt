package com.fz.yqlandroid.manager.srtdirect

import android.os.SystemClock
import java.io.ByteArrayOutputStream

/**
 * §142 端到端延迟测量：每帧在第一个图像 NAL（slice）前插一个 SEI（user_data_unregistered，payloadType=5），
 * 带手机墙钟（System.currentTimeMillis）的两个时刻：
 *   - 采集时刻：摄像头传感器时间戳换算成墙钟
 *   - 编码完成时刻：编码器吐出这一帧的时刻（之后同步发送，不排队）
 * PC（gstplayer srtE2e*）按 [UUID] 找到它，配合 STOMP 往返测出的手机-PC 时钟差，算各段延迟。
 * 解码器遇到不认识的 SEI 会直接忽略，不影响画面；每帧多 ~45 字节。
 */
internal object SdLatencySei {
    /** 与 PC gstplayer.cpp kSrtE2eUuid 必须一致 */
    val UUID: ByteArray = byteArrayOf(
        0x59, 0x51, 0x4C, 0x2D, 0x53, 0x52, 0x54, 0x2D,   // "YQL-SRT-"
        0x45, 0x32, 0x45, 0x2D, 0x76, 0x31, 0x00, 0x01,   // "E2E-v1" 0x00 0x01
    )

    /** 0=未判定 1=elapsedRealtime（SENSOR_TIMESTAMP REALTIME）2=nanoTime（UNKNOWN/单调时钟） */
    @Volatile private var clockBase = 0

    /**
     * 摄像头时间戳（编码器输出的 presentationTimeUs，Surface 输入时就是传感器时间戳）→ 手机墙钟 ms。
     * 时间基在第一帧按「离哪个系统时钟更近」判定一次。算不出合理值（不在 0~5s 内）返回 -1。
     */
    fun captureWallMs(ptsUs: Long, nowWallMs: Long): Long {
        val ptsNs = ptsUs * 1000L
        if (clockBase == 0) {
            val dReal = Math.abs(SystemClock.elapsedRealtimeNanos() - ptsNs)
            val dMono = Math.abs(System.nanoTime() - ptsNs)
            clockBase = if (dReal <= dMono) 1 else 2
            SdLog.i("§142 延迟测量：摄像头时间基=${if (clockBase == 1) "elapsedRealtime" else "nanoTime"}" +
                    "（差 real=${dReal / 1_000_000}ms mono=${dMono / 1_000_000}ms）")
        }
        val nowNs = if (clockBase == 1) SystemClock.elapsedRealtimeNanos() else System.nanoTime()
        val ageMs = (nowNs - ptsNs) / 1_000_000L
        return if (ageMs in 0..5000) nowWallMs - ageMs else -1L
    }

    /** 在第一个 slice NAL（type 1/5）前插入 SEI；找不到 slice 就原样返回 */
    fun insert(frame: ByteArray, captureMs: Long, encodedMs: Long): ByteArray {
        val pos = firstVclStart(frame)
        if (pos < 0) return frame
        val sei = buildSei(captureMs, encodedMs)
        val out = ByteArray(frame.size + sei.size)
        System.arraycopy(frame, 0, out, 0, pos)
        System.arraycopy(sei, 0, out, pos, sei.size)
        System.arraycopy(frame, pos, out, pos + sei.size, frame.size - pos)
        return out
    }

    /** 第一个 slice 的起始码位置（含 4 字节起始码的前导 0） */
    private fun firstVclStart(b: ByteArray): Int {
        var i = 0
        while (i + 3 < b.size) {
            if (b[i].toInt() == 0 && b[i + 1].toInt() == 0 && b[i + 2].toInt() == 1) {
                val type = b[i + 3].toInt() and 0x1F
                if (type == 1 || type == 5) return if (i > 0 && b[i - 1].toInt() == 0) i - 1 else i
                i += 3
            } else {
                i++
            }
        }
        return -1
    }

    private fun buildSei(captureMs: Long, encodedMs: Long): ByteArray {
        // RBSP：payloadType=5, payloadSize=32, UUID(16) + 采集(8) + 编码完成(8), rbsp_trailing_bits
        val rbsp = ByteArrayOutputStream(40)
        rbsp.write(0x05)
        rbsp.write(32)
        rbsp.write(UUID)
        writeLong(rbsp, captureMs)
        writeLong(rbsp, encodedMs)
        rbsp.write(0x80)
        val out = ByteArrayOutputStream(56)
        out.write(byteArrayOf(0, 0, 0, 1, 0x06))
        // 防竞争字节：连续两个 0x00 后若是 0x00~0x03，先插 0x03
        var zeros = 0
        for (x in rbsp.toByteArray()) {
            val v = x.toInt() and 0xFF
            if (zeros >= 2 && v <= 3) {
                out.write(0x03)
                zeros = 0
            }
            out.write(v)
            zeros = if (v == 0) zeros + 1 else 0
        }
        return out.toByteArray()
    }

    private fun writeLong(o: ByteArrayOutputStream, v: Long) {
        for (s in 56 downTo 0 step 8) o.write(((v ushr s) and 0xFF).toInt())
    }
}
