package com.fz.yqlandroid.manager.srtdirect

import android.util.Log

/**
 * §134 SRT直推统一日志：`meidui` 标签 + `[SRT直推]` 前缀，
 * 由 P2PLogReporter 按推流ID上报（总后台「P2P日志」开关，文件 Android-p2p.log）。
 */
internal object SdLog {
    private const val TAG = "meidui"

    fun i(msg: String) {
        Log.d(TAG, "📡 [SRT直推] $msg")
    }

    fun w(msg: String) {
        Log.d(TAG, "⚠️ [SRT直推] $msg")
    }

    /** streamid 末段是一次性推流 token / 拉流 JWT，不进日志 */
    fun maskStreamId(streamId: String): String {
        val parts = streamId.split(":")
        return if (parts.size >= 4 && parts[3].isNotEmpty()) {
            (parts.take(3) + "***").joinToString(":")
        } else streamId
    }
}
