package com.fz.yqlandroid.manager.srt

/**
 * 令牌桶按推送帧率放行（照 silu `UvcFpsPolicy.FrameGate` / test-android `FrameGate`）：
 * 摄像头采集帧率高于推送帧率时在入口丢帧，后面的 GL 绘制/编码/封装/发送全部省掉（最多攒 2 帧突发）。
 */
internal class SrtFrameGate {
    private var targetFps = 0
    private var available = 0.0
    private var lastNs = Long.MIN_VALUE

    @Volatile var dropped = 0L
        private set

    @Synchronized
    fun shouldAccept(nowNs: Long, fps: Int): Boolean {
        val f = fps.coerceIn(1, 120)
        if (lastNs != Long.MIN_VALUE && nowNs > lastNs && f == targetFps) {
            available = minOf(MAX_BURST, available + (nowNs - lastNs) * f / 1e9)
            lastNs = nowNs
            if (available + 1e-6 < 1.0) {
                dropped++
                return false
            }
            available = maxOf(0.0, available - 1.0)
            return true
        }
        targetFps = f
        lastNs = nowNs
        available = 0.0
        return true
    }

    private companion object {
        const val MAX_BURST = 2.0
    }
}
