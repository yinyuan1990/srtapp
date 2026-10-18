package com.fz.yqlandroid.manager

import android.app.ActivityManager
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * 诊断日志：从本进程 logcat 里只挑保活相关的几类事件（WS 连断、SRT 连断、保活/前台服务启停、
 * 亮灭屏、网络、进程冻结、崩溃），压成「时间 + 内容」落盘，跨进程保留。复制结果要能直接发微信。
 */
object DebugLog {

    const val TAG = "DiagLog"
    private const val PREFS = "debug_log_prefs"
    private const val KEY_OVERLAY = "overlay_enabled"
    private const val MAX_FILE_BYTES = 256L * 1024
    private const val MAX_LINE_CHARS = 150
    private const val TICK_MS = 10_000L
    private const val ALIVE_EVERY_MS = 5 * 60_000L
    private const val RECENT_MAX = 500

    private val WS_KEEP = listOf("WebSocket已连接", "已关闭", "连接失败", "手动断开", "STOMP已连接")
    private val SRT_KEEP = listOf(
        "已连接 ", "断线", "连接失败", "没有帧送来", "停止发送", "主动重连", "帧恢复送来",
        "采集启动", "采集已停止", "重开摄像头", "摄像头:", "库加载失败"
    )

    /** threadtime 格式：MM-dd HH:mm:ss.SSS pid tid L tag: msg */
    private val LOGCAT_LINE = Regex("""^\d\d-\d\d (\d\d:\d\d:\d\d)\.\d+\s+\d+\s+\d+\s+([VDIWEF])\s+(.+?)\s*: (.*)$""")

    @Volatile private var started = false
    private lateinit var appContext: Context
    private lateinit var logFile: File
    private lateinit var oldFile: File
    private var writer: FileWriter? = null
    private val writeLock = Any()
    private val recent = ArrayDeque<String>()

    @Synchronized
    fun start(context: Context) {
        if (started) return
        started = true
        appContext = context.applicationContext
        val dir = File(appContext.filesDir, "debuglog").apply { mkdirs() }
        File(dir, "log.txt").delete()
        File(dir, "log.1.txt").delete()
        logFile = File(dir, "diag.txt")
        oldFile = File(dir, "diag.1.txt")
        rotateIfNeeded(force = false)
        preloadRecent()
        append("==== 进程启动 ${now()} ====")
        event(snapshot())
        logExitReasons()
        startLogcatReader()
        registerSystemEvents()
        startTicker()
    }

    fun isOverlayEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_OVERLAY, false)

    fun setOverlayEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_OVERLAY, enabled).apply()
    }

    /** 内存里的最近日志，悬浮窗刷新用 */
    fun recentLines(): List<String> = synchronized(writeLock) { recent.toList() }

    /** 文件里的完整日志（含上一个文件），在 IO 线程调用 */
    fun readLines(): List<String> {
        if (!started) return emptyList()
        synchronized(writeLock) {
            writer?.flush()
            val all = ArrayList<String>()
            if (oldFile.exists()) all.addAll(oldFile.readLines())
            if (logFile.exists()) all.addAll(logFile.readLines())
            return all
        }
    }

    fun clear() {
        if (!started) return
        synchronized(writeLock) {
            writer?.close()
            writer = null
            oldFile.delete()
            logFile.delete()
            recent.clear()
        }
        append("==== 已清空 ${now()} ====")
        event(snapshot())
    }

    /** 设备与后台限制状态，复制时放最前面 */
    fun snapshot(): String {
        val ctx = appContext
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val version = try {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
        } catch (_: Exception) { "?" }
        val oneUi = try {
            Build.VERSION::class.java.getField("SEM_PLATFORM_INT").getInt(null).toString()
        } catch (_: Exception) { "无" }
        val bucket = if (Build.VERSION.SDK_INT >= 28) {
            val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            when (val b = usm.appStandbyBucket) {
                5 -> "豁免"; 10 -> "活跃"; 20 -> "工作集"; 30 -> "常用"
                40 -> "少用"; 45 -> "受限"; 50 -> "从不"; else -> b.toString()
            }
        } else "-"
        val bgRestricted = if (Build.VERSION.SDK_INT >= 28) am.isBackgroundRestricted.toString() else "-"
        return "${Build.MODEL} A${Build.VERSION.RELEASE} OneUI=$oneUi v$version" +
                " 忽略电池优化=${OemPermissionHelper.isIgnoringBatteryOptimizations(ctx)}" +
                " 后台受限=$bgRestricted 待机=$bucket 省电=${pm.isPowerSaveMode}"
    }

    private fun logExitReasons() {
        if (Build.VERSION.SDK_INT < 30) return
        try {
            val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.getHistoricalProcessExitReasons(appContext.packageName, 0, 3).forEach {
                val desc = it.description?.takeIf { d -> d.isNotBlank() }?.let { d -> " $d" } ?: ""
                event("上次退出 ${fmt(it.timestamp)} ${exitReasonName(it.reason)} 重要性=${it.importance}$desc")
            }
        } catch (e: Exception) {
            event("读取退出原因失败: ${e.message}")
        }
    }

    private fun exitReasonName(reason: Int) = when (reason) {
        1 -> "主动退出"; 2 -> "被信号杀"; 3 -> "低内存"; 4 -> "崩溃"; 5 -> "Native崩溃"; 6 -> "ANR"
        7 -> "初始化失败"; 8 -> "权限变更"; 9 -> "资源占用过度"; 10 -> "用户请求"; 11 -> "用户停止"
        12 -> "依赖进程死亡"; 13 -> "其他"; 14 -> "冻结器"; 15 -> "包状态变更"; 16 -> "包更新"
        else -> "未知($reason)"
    }

    /** 只留白名单里的行，返回压缩后的文本；不要的返回 null */
    private fun filter(raw: String): String? {
        val m = LOGCAT_LINE.matchEntire(raw) ?: return null
        val (time, level, tag, msg) = m.destructured
        val keep = when {
            tag == TAG -> true
            tag == "AndroidRuntime" -> level == "E"
            tag == "StreamingFgService" -> true
            msg.contains("[保活]") || msg.contains("后台保活已停止") -> true
            msg.contains("[WS]") -> WS_KEEP.any { msg.contains(it) }
            msg.contains("[SRT直推]") -> SRT_KEEP.any { msg.contains(it) }
            else -> false
        }
        if (!keep) return null
        val text = msg.removePrefix("wb ").replace("[SRT直推] ", "SRT ").replace("[WS] ", "WS ")
        return "$time $text".take(MAX_LINE_CHARS)
    }

    private fun startLogcatReader() {
        thread(name = "DiagLogcat", isDaemon = true) {
            while (true) {
                try {
                    val proc = Runtime.getRuntime()
                        .exec(arrayOf("logcat", "-v", "threadtime", "-T", "1", "--pid=${Process.myPid()}"))
                    proc.inputStream.bufferedReader().forEachLine { line -> filter(line)?.let(::append) }
                    proc.destroy()
                } catch (e: Exception) {
                    append("${now()} logcat 读取异常: ${e.message}")
                }
                SystemClock.sleep(1000)
            }
        }
    }

    /** 状态变化才记；每 5 分钟记一次「存活」；心跳间隔拉长说明期间进程被冻结 */
    private fun startTicker() {
        thread(name = "DiagTicker", isDaemon = true) {
            var last = SystemClock.elapsedRealtime()
            var lastAlive = last
            var lastStatus = ""
            while (true) {
                SystemClock.sleep(TICK_MS)
                val nowMs = SystemClock.elapsedRealtime()
                val gap = nowMs - last
                last = nowMs
                if (gap > TICK_MS * 2) event("⚠️ 卡住 ${gap / 1000}s（进程被冻结或CPU休眠）")
                val status = try { statusLine() } catch (e: Exception) { "状态读取失败 ${e.message}" }
                if (status != lastStatus) {
                    event("状态 $status")
                    lastStatus = status
                    lastAlive = nowMs
                } else if (nowMs - lastAlive >= ALIVE_EVERY_MS) {
                    event("存活")
                    lastAlive = nowMs
                }
            }
        }
    }

    private fun statusLine(): String {
        val pm = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.RunningAppProcessInfo().also { ActivityManager.getMyMemoryState(it) }
        @Suppress("DEPRECATION")
        val fgService = am.getRunningServices(50).any {
            it.service.className.endsWith("StreamingForegroundService") && it.foreground
        }
        return (if (pm.isInteractive) "亮屏" else "熄屏") +
                " ${networkDesc()}" +
                " WS${if (WebSocketManager.instance.isConnected) "连" else "断"}" +
                " 前台服务${if (fgService) "在" else "无"}" +
                " 重要性${info.importance}" +
                (if (pm.isDeviceIdleMode) " Doze" else "") +
                (if (pm.isPowerSaveMode) " 省电" else "")
    }

    private fun networkDesc(): String {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return "无网"
        val type = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WiFi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "蜂窝"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "有线"
            else -> "其他网"
        }
        val saver = cm.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED
        return if (saver) "$type(省流量)" else type
    }

    private fun registerSystemEvents() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                val name = when (intent.action) {
                    Intent.ACTION_SCREEN_ON -> "亮屏"
                    Intent.ACTION_SCREEN_OFF -> "熄屏"
                    Intent.ACTION_USER_PRESENT -> "解锁"
                    PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> "省电模式=${pm.isPowerSaveMode}"
                    PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED -> "Doze=${pm.isDeviceIdleMode}"
                    else -> intent.action ?: "?"
                }
                event("事件 $name")
            }
        }
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                appContext.registerReceiver(receiver, filter)
            }
        } catch (e: Exception) {
            event("注册系统事件失败: ${e.message}")
        }
        try {
            val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    event("网络可用 ${networkDesc()}")
                }

                override fun onLost(network: Network) {
                    event("网络断开")
                }

                override fun onBlockedStatusChanged(network: Network, blocked: Boolean) {
                    event(if (blocked) "网络被系统封锁" else "网络解除封锁")
                }
            })
        } catch (e: Exception) {
            event("注册网络回调失败: ${e.message}")
        }
    }

    private fun preloadRecent() {
        try {
            val old = ArrayList<String>()
            if (oldFile.exists()) old.addAll(oldFile.readLines())
            if (logFile.exists()) old.addAll(logFile.readLines())
            synchronized(writeLock) { old.takeLast(RECENT_MAX).forEach { recent.addLast(it) } }
        } catch (_: Exception) {
        }
    }

    /** 诊断模块自己的事件直接落盘，不绕 logcat（读取器启动前的也不会丢） */
    private fun event(msg: String) = append("${hms()} $msg".take(MAX_LINE_CHARS))

    private fun append(line: String) {
        synchronized(writeLock) {
            try {
                recent.addLast(line)
                if (recent.size > RECENT_MAX) recent.removeFirst()
                val w = writer ?: FileWriter(logFile, true).also { writer = it }
                w.write(line)
                w.write("\n")
                w.flush()
                if (logFile.length() > MAX_FILE_BYTES) rotateIfNeeded(force = true)
            } catch (_: Exception) {
            }
        }
    }

    private fun rotateIfNeeded(force: Boolean) {
        synchronized(writeLock) {
            if (!force && logFile.length() <= MAX_FILE_BYTES) return
            writer?.close()
            writer = null
            oldFile.delete()
            logFile.renameTo(oldFile)
        }
    }

    private fun now() = fmt(System.currentTimeMillis())

    private fun hms() = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())

    private fun fmt(ms: Long) = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date(ms))
}
