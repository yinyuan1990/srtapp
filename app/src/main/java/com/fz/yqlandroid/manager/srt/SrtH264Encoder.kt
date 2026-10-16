package com.fz.yqlandroid.manager.srt

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface

/**
 * SRT 模式（§129）的 H.264 硬编码器，Surface 输入（GL 线程把摄像头纹理画进来）。
 * 拷自 test-android `H264Encoder`，默认参数对齐 silu：VBR + High + Level 自动 + QP 上限 + 1s 关键帧。
 * 编码器不认某些键时 configure 会抛异常 → 按 [TIERS] 逐级去掉可选项重试，[tierName] 记录最终生效档。
 */
internal class SrtH264Encoder(
    val width: Int,
    val height: Int,
    private val fps: Int,
    private val bitrateKbps: Int,
    private val qpMax: Int,
    /** MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_*：1=VBR 2=CBR */
    private val bitrateMode: Int,
    private val keyIntervalSec: Int,
    /** 每个编码输出（Annex-B，含起始码）；isKey=IDR 帧。在编码线程回调。 */
    private val onFrame: (data: ByteArray, ptsUs: Long, isKey: Boolean) -> Unit,
) {
    companion object {
        private const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC
        private const val OPT_VENDOR_QP = 1
        private const val OPT_QP = 2
        private const val OPT_PROFILE = 4
        private const val OPT_EXTRA = 8   // priority / operating-rate / color / prepend-sps-pps / 统计

        private val TIERS = listOf(
            "1 全部(含厂商QP键)" to (OPT_VENDOR_QP or OPT_QP or OPT_PROFILE or OPT_EXTRA),
            "2 全部(标准QP键)" to (OPT_QP or OPT_PROFILE or OPT_EXTRA),
            "3 去掉QP上限" to (OPT_PROFILE or OPT_EXTRA),
            "4 去掉Profile/Level" to OPT_EXTRA,
            "5 最简配置" to 0,
        )
    }

    private val thread = HandlerThread("srt-h264-enc").apply { start() }
    private val handler = Handler(thread.looper)
    @Volatile private var codec: MediaCodec? = null

    var inputSurface: Surface? = null
        private set
    var codecName: String = ""
        private set
    var tierName: String = ""
        private set
    @Volatile var outputFormatDesc: String = ""
        private set
    /** 编码器报错（onError）后置 true，由 SrtPublisher 重建 */
    @Volatile var failed = false
        private set

    @Volatile private var csd: ByteArray? = null

    // —— 每秒统计（编码线程写，统计线程读快照）——
    private val statLock = Any()
    private var secBytes = 0L
    private var secFrames = 0
    private var secQpSum = 0L
    private var secQpCount = 0
    private var secI = 0
    private var secIBytes = 0L
    private var secPCount = 0
    private var secPBytes = 0L
    private var secPMax = 0
    private var secStartMs = 0L
    @Volatile var statKbps = 0
        private set
    @Volatile var statFps = 0
        private set
    /** -1 = 编码器不报 QP（Android 13 以下或编码器不支持） */
    @Volatile var statQp = -1
        private set
    /** 上一秒 I/P 帧摘要（日志用） */
    @Volatile var statFrames = "-"
        private set
    @Volatile var keyFrames = 0L
        private set
    /** 最近一次有编码输出的时刻（统计判断「编码器没出帧」用） */
    @Volatile var lastOutputMs = 0L
        private set

    /** @throws IllegalStateException 所有降级档都配置失败 */
    fun start() {
        var lastError: Throwable? = null
        for ((name, opts) in TIERS) {
            val c = try { MediaCodec.createEncoderByType(MIME) } catch (t: Throwable) {
                throw IllegalStateException("创建 H.264 编码器失败: ${t.message}", t)
            }
            val format = buildFormat(c.name, opts)
            try {
                c.setCallback(callback, handler)
                c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                inputSurface = c.createInputSurface()
                c.start()
                codec = c
                codecName = c.name
                tierName = name
                SrtLog.i("编码器 ${c.name} 档位[$name] ${width}x${height}@${fps} ${bitrateKbps}kbps " +
                        "${if (bitrateMode == 2) "CBR" else "VBR"} QP≤$qpMax GOP=${keyIntervalSec}s")
                return
            } catch (t: Throwable) {
                lastError = t
                SrtLog.w("编码器档位[$name] 配置失败: ${t.message}")
                try { c.release() } catch (_: Throwable) {}
                inputSurface?.release()
                inputSurface = null
            }
        }
        thread.quitSafely()
        throw IllegalStateException("编码器所有降级档都配置失败: ${lastError?.message}", lastError)
    }

    private fun buildFormat(name: String, opts: Int): MediaFormat {
        val f = MediaFormat.createVideoFormat(MIME, width, height)
        f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        f.setInteger(MediaFormat.KEY_BIT_RATE, bitrateKbps * 1000)
        f.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
        if (Build.VERSION.SDK_INT >= 25) {
            f.setFloat(MediaFormat.KEY_I_FRAME_INTERVAL, keyIntervalSec.toFloat())
        } else {
            f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, keyIntervalSec)
        }
        f.setInteger(MediaFormat.KEY_BITRATE_MODE, bitrateMode)

        if (opts and OPT_PROFILE != 0) {
            f.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh)
            f.setInteger(MediaFormat.KEY_LEVEL, avcLevelFor(width, height, fps))
        }
        if (opts and OPT_EXTRA != 0) {
            f.setInteger(MediaFormat.KEY_PRIORITY, 0)
            f.setInteger(MediaFormat.KEY_OPERATING_RATE, fps)
            // 与 WebRTC 模式 ColorTaggingVideoEncoderFactory 同口径：BT.709 + full range
            f.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_FULL)
            f.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
            f.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
            f.setInteger("prepend-sps-pps-to-idr-frames", 1)
            if (Build.VERSION.SDK_INT >= 33) {
                f.setInteger("video-encoding-statistics-level", 1)
            }
        }
        if (qpMax > 0 && opts and OPT_QP != 0) {
            f.setInteger("video-qp-i-max", qpMax)
            f.setInteger("video-qp-p-max", qpMax)
            f.setInteger("video-qp-b-max", qpMax)
        }
        if (qpMax > 0 && opts and OPT_VENDOR_QP != 0 && isQualcomm(name)) {
            f.setInteger("vendor.qti-ext-enc-qp-range.qp-i-max", qpMax)
            f.setInteger("vendor.qti-ext-enc-qp-range.qp-p-max", qpMax)
            f.setInteger("vendor.qti-ext-enc-qp-range.qp-b-max", qpMax)
        }
        return f
    }

    /** 按 H.264 附录 A 宏块数/宏块速率选最低够用的 Level：1080p30=4.0、1080p60=4.2、1440p30=5.0、4K30=5.1 */
    private fun avcLevelFor(w: Int, h: Int, fps: Int): Int {
        val mbs = ((w + 15) / 16) * ((h + 15) / 16)
        val mbps = mbs.toLong() * fps
        return when {
            mbs <= 8192 && mbps <= 245_760 -> MediaCodecInfo.CodecProfileLevel.AVCLevel4
            mbs <= 8704 && mbps <= 522_240 -> MediaCodecInfo.CodecProfileLevel.AVCLevel42
            mbs <= 22_080 && mbps <= 589_824 -> MediaCodecInfo.CodecProfileLevel.AVCLevel5
            mbs <= 36_864 && mbps <= 983_040 -> MediaCodecInfo.CodecProfileLevel.AVCLevel51
            else -> MediaCodecInfo.CodecProfileLevel.AVCLevel52
        }
    }

    private fun isQualcomm(name: String): Boolean {
        val n = name.lowercase()
        return n.contains("qcom") || n.contains("qti")
    }

    fun requestKeyFrame() {
        val c = codec ?: return
        handler.post {
            try {
                c.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
            } catch (_: Throwable) {}
        }
    }

    /** 运行中改目标码率（不重建编码器） */
    fun setBitrate(kbps: Int) {
        val c = codec ?: return
        handler.post {
            try {
                c.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, kbps * 1000) })
            } catch (t: Throwable) {
                SrtLog.w("改码率失败: ${t.message}")
            }
        }
    }

    fun stop() {
        val c = codec
        codec = null
        handler.post {
            try { c?.stop() } catch (_: Throwable) {}
            try { c?.release() } catch (_: Throwable) {}
            inputSurface?.release()
            inputSurface = null
            thread.quitSafely()
        }
    }

    private val callback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {}

        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            try {
                val buf = codec.getOutputBuffer(index)
                if (buf != null && info.size > 0) {
                    buf.position(info.offset)
                    buf.limit(info.offset + info.size)
                    val data = ByteArray(info.size)
                    buf.get(data)
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        csd = data
                    } else {
                        val isKey = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                        if (isKey) keyFrames++
                        if (Build.VERSION.SDK_INT >= 33) readQp(codec, index)
                        val out = if (isKey && !containsSps(data)) (csd ?: ByteArray(0)) + data else data
                        accountStats(out.size, isKey)
                        onFrame(out, info.presentationTimeUs, isKey)
                    }
                }
            } catch (t: Throwable) {
                SrtLog.w("处理编码输出失败: ${t.message}")
            } finally {
                try { codec.releaseOutputBuffer(index, false) } catch (_: Throwable) {}
            }
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            failed = true
            SrtLog.w("编码器错误: ${e.diagnosticInfo} recoverable=${e.isRecoverable} transient=${e.isTransient}")
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            val p = if (format.containsKey(MediaFormat.KEY_PROFILE)) format.getInteger(MediaFormat.KEY_PROFILE) else -1
            val l = if (format.containsKey(MediaFormat.KEY_LEVEL)) format.getInteger(MediaFormat.KEY_LEVEL) else -1
            outputFormatDesc = "profile=$p level=$l"
            SrtLog.i("编码器输出格式: $format")
        }
    }

    private fun readQp(codec: MediaCodec, index: Int) {
        try {
            val f = codec.getOutputFormat(index)
            if (f.containsKey("video-qp-average")) {
                synchronized(statLock) {
                    secQpSum += f.getInteger("video-qp-average")
                    secQpCount++
                }
            }
        } catch (_: Throwable) {}
    }

    private fun accountStats(bytes: Int, isKey: Boolean) {
        val now = System.currentTimeMillis()
        lastOutputMs = now
        synchronized(statLock) {
            if (secStartMs == 0L) secStartMs = now
            secBytes += bytes
            secFrames++
            if (isKey) {
                secI++; secIBytes += bytes
            } else {
                secPCount++; secPBytes += bytes
                if (bytes > secPMax) secPMax = bytes
            }
            val elapsed = now - secStartMs
            if (elapsed >= 1000) {
                statKbps = (secBytes * 8 / elapsed).toInt()
                statFps = (secFrames * 1000 / elapsed).toInt()
                statQp = if (secQpCount > 0) (secQpSum / secQpCount).toInt() else -1
                statFrames = "I=${secI}×${if (secI > 0) secIBytes / secI / 1024 else 0}KB " +
                        "P均=${if (secPCount > 0) secPBytes / secPCount / 1024 else 0}KB P峰=${secPMax / 1024}KB"
                secBytes = 0; secFrames = 0; secQpSum = 0; secQpCount = 0
                secI = 0; secIBytes = 0; secPCount = 0; secPBytes = 0; secPMax = 0
                secStartMs = now
            }
        }
    }

    /** 码流里是否有 SPS（部分机型 IDR 前不带，需要补 csd） */
    private fun containsSps(data: ByteArray): Boolean {
        var i = 0
        while (i + 3 < data.size) {
            if (data[i].toInt() == 0 && data[i + 1].toInt() == 0) {
                val start = when {
                    data[i + 2].toInt() == 1 -> i + 3
                    data[i + 2].toInt() == 0 && data[i + 3].toInt() == 1 -> i + 4
                    else -> -1
                }
                if (start in 0 until data.size) {
                    if ((data[start].toInt() and 0x1F) == 7) return true
                    i = start
                    continue
                }
            }
            i++
        }
        return false
    }
}
