package com.fz.yqlandroid.manager.srt

import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import org.webrtc.EglBase
import org.webrtc.GlRectDrawer
import org.webrtc.VideoFrame
import org.webrtc.VideoFrameDrawer
import org.webrtc.VideoSink
import java.util.concurrent.atomic.AtomicBoolean

/**
 * §129 SRT 推流（方案 A 混合）：挂在 WebRTC 本地视频轨上的 [VideoSink]。
 *
 * 采集/预览/相机控制全部沿用 WebRTC（Camera2Capturer + Camera2ParamApplier），本类只替换
 * 「PeerConnection + 内置编码器」：
 *   纹理帧 → 令牌桶按推送 fps 丢帧 → 独立 GL 线程（共享 EGL）用 VideoFrameDrawer 画进
 *   MediaCodec 输入 Surface（应用 frame.rotation，与 WebRTC 推 SRS 时 PC 看到的方向一致；按档位 scaleDown 缩放）
 *   → H.264（silu 参数）→ TS → SRT → SRS（srt_to_rtmp + rtmp_to_rtc 桥接成 WebRTC，PC 照旧 WHEP 拉）。
 *
 * 防碎（§129.1）：SRT 发送缓冲/迟到丢弃驱动码率档位 1.0/0.8/0.65/0.5/0.4/0.3。
 */
class SrtPublisher(
    private val sharedContext: EglBase.Context,
    private val cfg: Config,
    streamIdProvider: (attempt: Int) -> String,
    /** 某次连接首个关键帧已发出（attempt=第几次连接），在编码线程回调 */
    onLive: (attempt: Int) -> Unit,
) : VideoSink {

    data class Config(
        val host: String,
        val port: Int = 10080,
        val latencyMs: Int = 500,
        val qpMax: Int = 30,
        /** 1=VBR 2=CBR */
        val bitrateMode: Int = 1,
        val keyIntervalSec: Int = 1,
        /** >0 = 固定基准码率（kbps），0 = 按编码分辨率自动 */
        val fixedKbps: Int = 0,
    )

    /** 每秒统计快照（WebRTCManager 写进 WebSocketManager 上报 PC） */
    data class Snapshot(
        val connected: Boolean,
        val live: Boolean,
        val kbps: Int,
        val fps: Int,
        val rttMs: Int,
        val lossRate: Double,
        val state: String,
    )

    // —— 由 WebRTCManager 设置（任意线程）——
    @Volatile var pushFps: Int = 30
    @Volatile var qualityPct: Int = 100
    @Volatile var scaleDown: Double = 1.0

    private val glThread = HandlerThread("srt-gl").apply { start() }
    private val gl = Handler(glThread.looper)
    private var egl: EglBase? = null
    private var drawer: GlRectDrawer? = null
    private var frameDrawer: VideoFrameDrawer? = null
    @Volatile private var encoder: SrtH264Encoder? = null
    private var lastRebuildMs = 0L
    private var rebuildFailures = 0

    private val muxLock = Any()
    private val muxer = SrtTsMuxer()
    private val gate = SrtFrameGate()
    private val busy = AtomicBoolean(false)
    @Volatile private var running = false
    @Volatile private var live = false

    private val sender = SrtSender(
        host = cfg.host,
        port = cfg.port,
        latencyMs = cfg.latencyMs,
        streamIdProvider = streamIdProvider,
        onNeedKeyFrame = { encoder?.requestKeyFrame() },
        onLive = { attempt -> live = true; onLive(attempt) },
    )

    // —— 统计 ——
    @Volatile private var sinkFrames = 0L
    @Volatile private var busyDrops = 0L
    @Volatile private var drawErrors = 0L
    private var lastSinkFrames = 0L
    private var lastGateDrops = 0L
    private var lastBusyDrops = 0L
    private var lastSent = 0L
    private var lastLoss = 0L
    private var lastRetrans = 0L
    private var lastDrop = 0L
    private var lastTickMs = 0L

    // —— 防碎：码率档位 ——
    private val scaleLadder = doubleArrayOf(1.0, 0.8, 0.65, 0.5, 0.4, 0.3)
    @Volatile private var scaleIdx = 0
    private var badSec = 0
    private var goodSec = 0
    private var lastAdaptMs = 0L

    fun start() {
        running = true
        SrtLog.i("启动 SRT 推流 → ${cfg.host}:${cfg.port} latency=${cfg.latencyMs}ms " +
                "${if (cfg.bitrateMode == 2) "CBR" else "VBR"} QP≤${cfg.qpMax} GOP=${cfg.keyIntervalSec}s " +
                "码率=${if (cfg.fixedKbps > 0) "${cfg.fixedKbps}k(固定)" else "按分辨率自动"} 推送${pushFps}fps 清晰度${qualityPct}%")
        sender.start()
    }

    fun stop() {
        running = false
        sender.stop()
        gl.post {
            releaseEncoder()
            try { egl?.release() } catch (_: Throwable) {}
            egl = null
            try { drawer?.release() } catch (_: Throwable) {}
            try { frameDrawer?.release() } catch (_: Throwable) {}
            drawer = null
            frameDrawer = null
            glThread.quitSafely()
        }
        SrtLog.i("SRT 推流已停止")
    }

    /** 观看端/切档要关键帧 */
    fun requestKeyFrame() {
        encoder?.requestKeyFrame()
    }

    /** 清晰度/推送帧率/档位变化后调用：按当前编码尺寸重算目标码率 */
    fun applyBitrate() {
        val enc = encoder ?: return
        enc.setBitrate(targetKbps(enc.width, enc.height))
    }

    // ---------------- VideoSink（采集线程） ----------------

    override fun onFrame(frame: VideoFrame) {
        if (!running) return
        sinkFrames++
        if (!gate.shouldAccept(frame.timestampNs, pushFps)) return
        if (!busy.compareAndSet(false, true)) {
            busyDrops++
            return
        }
        frame.retain()
        val posted = gl.post {
            var released = false
            try {
                released = drawFrame(frame)
            } catch (t: Throwable) {
                drawErrors++
                if (drawErrors % 30 == 1L) SrtLog.w("GL 绘制失败(#$drawErrors): ${t.message}")
            } finally {
                if (!released) frame.release()
                busy.set(false)
            }
        }
        if (!posted) {
            frame.release()
            busy.set(false)
        }
    }

    /** GL 线程：按需(重)建编码器 → 画帧 → glFinish 后先释放摄像头纹理 → swap 送编码器。返回帧是否已释放 */
    private fun drawFrame(frame: VideoFrame): Boolean {
        if (!running) return false
        val (w, h) = targetSize(frame.rotatedWidth, frame.rotatedHeight)
        var enc = encoder
        if (enc == null || enc.width != w || enc.height != h || enc.failed) {
            val now = System.currentTimeMillis()
            // 失败重建节流 2s，防编码器反复报错空转
            if (enc != null && enc.failed && now - lastRebuildMs < 2000) return false
            if (enc == null && rebuildFailures > 0 && now - lastRebuildMs < 2000) return false
            lastRebuildMs = now
            enc = rebuildEncoder(w, h, frame.rotatedWidth, frame.rotatedHeight) ?: return false
        }
        val e = egl ?: return false
        e.makeCurrent()
        GLES20.glViewport(0, 0, enc.width, enc.height)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        frameDrawer!!.drawFrame(frame, drawer!!, null, 0, 0, enc.width, enc.height)
        GLES20.glFinish()
        val ts = frame.timestampNs
        frame.release()   // 纹理已采样完：尽早还给 SurfaceTextureHelper，编码器背压时不卡预览
        e.swapBuffers(ts)
        return true
    }

    private fun rebuildEncoder(w: Int, h: Int, srcW: Int, srcH: Int): SrtH264Encoder? {
        val old = encoder
        if (old != null) {
            SrtLog.i("编码尺寸变化/编码器异常 → 重建：${old.width}x${old.height}(failed=${old.failed}) → ${w}x${h}（源 ${srcW}x${srcH} scaleDown=${"%.2f".format(scaleDown)}）")
        }
        releaseEncoder()
        val fps = pushFps.coerceIn(1, 120)
        val candidates = listOf(w to h, (w / 16 * 16) to (h / 16 * 16)).distinct().filter { it.first >= 16 && it.second >= 16 }
        for ((cw, ch) in candidates) {
            lateinit var created: SrtH264Encoder
            val enc = SrtH264Encoder(cw, ch, fps, targetKbps(cw, ch), cfg.qpMax, cfg.bitrateMode, cfg.keyIntervalSec) { data, ptsUs, isKey ->
                // 换编码器时新旧短暂并存：只收当前编码器的输出（muxer 非线程安全）
                if (encoder === created) {
                    synchronized(muxLock) { sender.offer(muxer.mux(data, ptsUs, isKey), isKey) }
                }
            }
            created = enc
            try {
                enc.start()
            } catch (t: Throwable) {
                SrtLog.w("编码器启动失败 ${cw}x${ch}: ${t.message}")
                continue
            }
            try {
                ensureEgl()
                egl!!.createSurface(enc.inputSurface!!)
                egl!!.makeCurrent()
            } catch (t: Throwable) {
                SrtLog.w("EGL 绑定编码器 Surface 失败: ${t.message}")
                try { egl?.releaseSurface() } catch (_: Throwable) {}
                enc.stop()
                continue
            }
            encoder = enc
            rebuildFailures = 0
            SrtLog.i("编码器就绪 ${enc.codecName} 降级档=${enc.tierName} ${cw}x${ch}@${fps} 目标${targetKbps(cw, ch)}kbps")
            enc.requestKeyFrame()
            return enc
        }
        rebuildFailures++
        SrtLog.w("编码器创建失败（第${rebuildFailures}次），2s 后再试")
        return null
    }

    private fun ensureEgl() {
        if (egl != null) return
        egl = EglBase.create(sharedContext, EglBase.CONFIG_RECORDABLE)
        drawer = GlRectDrawer()
        frameDrawer = VideoFrameDrawer()
    }

    private fun releaseEncoder() {
        val enc = encoder
        encoder = null
        try { egl?.releaseSurface() } catch (_: Throwable) {}
        enc?.stop()
    }

    /** 编码尺寸 = 旋转后的采集尺寸 / scaleDown，取偶数 */
    private fun targetSize(rw: Int, rh: Int): Pair<Int, Int> {
        val s = scaleDown.coerceAtLeast(1.0)
        val w = ((rw / s).toInt() and 1.inv()).coerceAtLeast(16)
        val h = ((rh / s).toInt() and 1.inv()).coerceAtLeast(16)
        return w to h
    }

    /** 基准码率（按分辨率或固定）× 清晰度百分比 × 防碎档位 */
    private fun targetKbps(w: Int, h: Int): Int {
        val base = if (cfg.fixedKbps > 0) cfg.fixedKbps else autoKbps(w, h)
        val pct = qualityPct.coerceIn(10, 100) / 100.0
        return (base * pct * scaleLadder[scaleIdx]).toInt().coerceAtLeast(500)
    }

    private fun autoKbps(w: Int, h: Int): Int {
        val px = w.toLong() * h
        return when {
            px <= 640L * 480 -> 3000
            px <= 1280L * 720 -> 6000
            px <= 1920L * 1080 -> 8000
            px <= 2560L * 1440 -> 12000
            else -> 16000
        }
    }

    // ---------------- 每秒统计 + 防碎自适应（统计线程调用） ----------------

    /** @param batteryC 电池温度（℃，读不到为 null），§132 写进统计行便于与 WebRTC 版/3840 版对比发热 */
    fun tick(batteryC: Float? = null): Snapshot {
        val now = System.currentTimeMillis()
        val dt = if (lastTickMs > 0) (now - lastTickMs).coerceAtLeast(1) else 1000L
        lastTickMs = now
        val enc = encoder
        val encAlive = enc != null && now - enc.lastOutputMs < 2000
        val kbps = if (encAlive) enc!!.statKbps else 0
        val fps = if (encAlive) enc!!.statFps else 0

        // SRT 统计是每条连接的累计值，重连后归零 → 基线跟着归零
        val sent = sender.sentTotal; val loss = sender.lossTotal
        val retrans = sender.retransTotal; val drop = sender.sndDropTotal
        if (sent < lastSent || loss < lastLoss || retrans < lastRetrans || drop < lastDrop) {
            lastSent = 0; lastLoss = 0; lastRetrans = 0; lastDrop = 0
        }
        val sentD = sent - lastSent
        val lossD = loss - lastLoss
        val retransD = retrans - lastRetrans
        val dropD = drop - lastDrop
        lastSent = sent; lastLoss = loss; lastRetrans = retrans; lastDrop = drop
        val lossRate = if (sentD > 0) lossD.toDouble() / sentD.toDouble() else 0.0

        val sinkD = sinkFrames - lastSinkFrames; lastSinkFrames = sinkFrames
        val gateD = gate.dropped - lastGateDrops; lastGateDrops = gate.dropped
        val busyD = busyDrops - lastBusyDrops; lastBusyDrops = busyDrops
        val inFps = (sinkD * 1000 / dt).toInt()

        adapt(now, dropD)

        val tgt = enc?.let { targetKbps(it.width, it.height) } ?: 0
        SrtLog.i("统计 ${sender.state}${if (live) "" else "(未出流)"} | 采集入${inFps}fps 限帧丢${gateD} GL忙丢${busyD} 推送上限${pushFps}fps" +
                " | 编码 ${enc?.width ?: 0}x${enc?.height ?: 0} ${kbps}kbps/目标${tgt}k ${fps}fps QP${enc?.statQp ?: -1}" +
                " 帧=${enc?.statFrames ?: "-"} 关键帧累计${enc?.keyFrames ?: 0}" +
                " | SRT ${sender.statsLine()} 本秒丢+$lossD 重传+$retransD 迟到丢+$dropD 断线丢帧${sender.droppedFrames} 重连${sender.reconnects}" +
                " | 码率档${(scaleLadder[scaleIdx] * 100).toInt()}% 清晰度${qualityPct}%" +
                " | 电池${batteryC?.let { "%.1f℃".format(it) } ?: "?"}")
        if (enc == null && running && now - lastRebuildMs > 3000 && sinkD == 0L) {
            SrtLog.w("没有画面送进来（采集帧=0）：查摄像头/预览是否在跑")
        }
        return Snapshot(
            connected = sender.connected,
            live = live && sender.connected,
            kbps = kbps,
            fps = fps,
            rttMs = sender.rttMs.toInt(),
            lossRate = lossRate,
            state = sender.state,
        )
    }

    /**
     * 防碎：SRT live 不让速，VBR 运动尖峰会塞满上行 → 发送缓冲涨到延迟窗口 → 成批迟到丢弃 → 马赛克（§119.7）。
     * 缓冲 >45% 延迟连续 2s 或本秒出现迟到丢弃 → 降一档；缓冲 <15% 连续 5s → 升一档。
     */
    private fun adapt(now: Long, dropD: Long) {
        if (!sender.connected) {
            badSec = 0; goodSec = 0
            return
        }
        val buf = sender.sndBufMs
        val lat = cfg.latencyMs.coerceAtLeast(100)
        when {
            dropD > 0 || buf > lat * 0.45 -> { badSec++; goodSec = 0 }
            buf < lat * 0.15 -> { goodSec++; badSec = 0 }
            else -> { badSec = 0; goodSec = 0 }
        }
        if ((dropD > 0 || badSec >= 2) && scaleIdx < scaleLadder.size - 1 && now - lastAdaptMs >= 2000) {
            scaleIdx++
            lastAdaptMs = now
            badSec = 0
            applyBitrate()
            SrtLog.w("防碎降码率 → ${(scaleLadder[scaleIdx] * 100).toInt()}%档（发送缓冲${buf}ms/延迟${lat}ms，本秒迟到丢弃$dropD）")
        } else if (goodSec >= 5 && scaleIdx > 0 && now - lastAdaptMs >= 3000) {
            scaleIdx--
            lastAdaptMs = now
            goodSec = 0
            applyBitrate()
            SrtLog.i("网络恢复升码率 → ${(scaleLadder[scaleIdx] * 100).toInt()}%档（发送缓冲${buf}ms）")
        }
    }
}
