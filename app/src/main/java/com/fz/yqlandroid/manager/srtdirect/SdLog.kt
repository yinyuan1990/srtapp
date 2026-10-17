package com.fz.yqlandroid.manager.srtdirect

import android.util.Log

/**
 * §134 SRT直推统一日志：`meidui` 标签 + `[SRT直推]` 前缀。
 * §143 同时直接进 [SrtLogReporter]（总后台「SRT管理 → SRT日志」开关打开时上报，文件 Android-srt.log）。
 */
internal object SdLog {
    private const val TAG = "meidui"

    fun i(msg: String) {
        Log.d(TAG, "📡 [SRT直推] $msg")
        SrtLogReporter.line(msg)
    }

    fun w(msg: String) {
        Log.d(TAG, "⚠️ [SRT直推] $msg")
        SrtLogReporter.line("⚠️ $msg")
    }

    /** streamid 末段是一次性推流 token / 拉流 JWT，不进日志 */
    fun maskStreamId(streamId: String): String {
        val parts = streamId.split(":")
        return if (parts.size >= 4 && parts[3].isNotEmpty()) {
            (parts.take(3) + "***").joinToString(":")
        } else streamId
    }
}
