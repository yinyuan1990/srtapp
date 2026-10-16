package com.fz.yqlandroid.manager.srt

import android.util.Log

/**
 * SRT 推流模式（§129）统一日志：全部打到 `meidui` 标签（带 `[SRT]` 前缀），
 * 由 P2PLogReporter 按推流ID上报到后端（总后台「P2P日志」开关，文件 Android-p2p.log）。
 */
internal object SrtLog {
    private const val TAG = "meidui"

    fun i(msg: String) {
        Log.d(TAG, "📡 [SRT] $msg")
    }

    fun w(msg: String) {
        Log.d(TAG, "⚠️ [SRT] $msg")
    }

    /** streamid 里的一次性 token 不进日志 */
    fun maskStreamId(streamId: String): String =
        streamId.replace(Regex("token=[^&,]*"), "token=***")
}
