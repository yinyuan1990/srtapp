package com.fz.yqlandroid.manager.srtdirect

/**
 * §134 峰值控制器（§133.1 第 4 条）：每秒看编码实际 kbps 与 SRT 发送缓冲。
 *
 * - **超标**：实际码率 > peakKbps，或发送缓冲 > 45% latency，连续 holdSec 秒 →
 *   先 P 帧 QP 上限 += qpStep（≤ qpCeil，Android 12+ 运行中 setParameters）；
 *   QP 到顶或系统不支持运行中改 QP → 目标码率降 10%/档（最多 5 档到 50%）。
 * - **平稳**：实际码率 ≤ 80% 峰值且发送缓冲 < 15% latency，连续 recoverSec 秒 → 撤销最近一步（后做的先撤）。
 */
internal class SdPeakController {
    sealed class Step {
        data class Qp(val from: Int, val to: Int) : Step()
        data class Rate(val fromPct: Int, val toPct: Int) : Step()
    }

    data class Result(val step: Step?, val raised: Boolean, val reason: String)

    private var plan: SrtDirectParams.Plan? = null
    private var qpRuntime = false
    private val steps = ArrayDeque<Step>()
    private var overSec = 0
    private var calmSec = 0

    /** 当前 P 帧 QP 上限（含控制器抬高量） */
    var curPMax = 0
        private set
    /** 当前码率系数（百分比） */
    var ratePct = 100
        private set

    val levelDesc: String
        get() = if (steps.isEmpty()) "平稳" else "已介入${steps.size}步(P≤$curPMax 码率${ratePct}%)"

    /** 新编码器/新参数：清零 */
    fun reset(p: SrtDirectParams.Plan, qpRuntimeSupported: Boolean) {
        plan = p
        qpRuntime = qpRuntimeSupported
        steps.clear()
        overSec = 0
        calmSec = 0
        curPMax = p.qpPMax
        ratePct = 100
    }

    fun tick(encKbps: Int, sndBufMs: Int, connected: Boolean): Result? {
        val p = plan ?: return null
        if (!p.peakEnable || !connected) {
            overSec = 0; calmSec = 0
            return null
        }
        val peakOver = p.peakKbps > 0 && encKbps > p.peakKbps
        val bufOver = sndBufMs > p.latencyMs * 45 / 100
        val calm = (p.peakKbps <= 0 || encKbps <= p.peakKbps * 8 / 10) && sndBufMs < p.latencyMs * 15 / 100
        when {
            peakOver || bufOver -> { overSec++; calmSec = 0 }
            calm -> { calmSec++; overSec = 0 }
            else -> { overSec = 0; calmSec = 0 }
        }
        val why = buildString {
            if (peakOver) append("实际${encKbps}kbps>峰值${p.peakKbps}")
            if (bufOver) { if (isNotEmpty()) append(" "); append("发送缓冲${sndBufMs}ms>45%延迟") }
        }
        if (overSec >= p.holdSec) {
            overSec = 0
            val s: Step? = when {
                qpRuntime && curPMax < p.qpCeil -> Step.Qp(curPMax, minOf(p.qpCeil, curPMax + p.qpStep))
                ratePct > 50 -> Step.Rate(ratePct, ratePct - 10)
                else -> null
            }
            if (s == null) return Result(null, true, "$why（已到 QP 上限 ${p.qpCeil} 且码率 50%，无可再降）")
            apply(s)
            steps.addLast(s)
            return Result(s, true, "$why 连续${p.holdSec}s")
        }
        if (calmSec >= p.recoverSec && steps.isNotEmpty()) {
            calmSec = 0
            val last = steps.removeLast()
            val back = when (last) {
                is Step.Qp -> Step.Qp(last.to, last.from)
                is Step.Rate -> Step.Rate(last.toPct, last.fromPct)
            }
            apply(back)
            return Result(back, false, "平稳${p.recoverSec}s（实际${encKbps}kbps 发送缓冲${sndBufMs}ms）")
        }
        return null
    }

    private fun apply(s: Step) {
        when (s) {
            is Step.Qp -> curPMax = s.to
            is Step.Rate -> ratePct = s.toPct
        }
    }
}
