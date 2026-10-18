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
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * 诊断日志：读本进程 logcat 落盘（跨进程保留，被杀重启后仍能看到上一次的日志），
 * 并每 10s 记一条状态心跳；心跳间隔异常拉长 = 期间进程被冻结。日志悬浮窗读这里的文件。
 */
object DebugLog {

    const val TAG = "DiagLog"
    private const val PREFS = "debug_log_prefs"
    private const val KEY_OVERLAY = "overlay_enabled"
    private const val MAX_FILE_BYTES = 3L * 1024 * 1024
    private const val TICK_MS = 10_000L

    private val KEY_MARKERS = listOf(
        TAG, "meidui", "KeepAliveManager", "StreamingFgService", "[WS]", "AndroidRuntime", "FATAL"
    )

    @Volatile private var started = false
    private lateinit var appContext: Context
    private lateinit var logFile: File
    private lateinit var oldFile: File
    private var writer: FileWriter? = null
    private val writeLock = Any()
    private const val RECENT_ALL = 400
    private const val RECENT_KEY = 800
    private val recentAll = ArrayDeque<String>()
    private val recentKey = ArrayDeque<String>()

    @Synchronized
    fun start(context: Context) {
        if (started) return
        started = true
        appContext = context.applicationContext
        val dir = File(appContext.filesDir, "debuglog").apply { mkdirs() }
        logFile = File(dir, "log.txt")
        oldFile = File(dir, "log.1.txt")
        rotateIfNeeded(force = false)
        appendRaw("\n========== 进程启动 pid=${Process.myPid()} ${now()} ==========")
        Log.i(TAG, snapshot())
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

    fun isKeyLine(line: String): Boolean = KEY_MARKERS.any { line.contains(it) } || line.startsWith("====")

    /** 内存里的最近日志，给悬浮窗定时刷新用；完整历史用 [readLines] */
    fun recentLines(keyOnly: Boolean): List<String> =
        synchronized(writeLock) { (if (keyOnly) recentKey else recentAll).toList() }

    private fun remember(line: String) {
        recentAll.addLast(line)
        if (recentAll.size > RECENT_ALL) recentAll.removeFirst()
        if (isKeyLine(line)) {
            recentKey.addLast(line)
            if (recentKey.size > RECENT_KEY) recentKey.removeFirst()
        }
    }

    /** 把上一个进程留在文件里的日志补进内存缓存的前面 */
    private fun preloadRecent() {
        val old = ArrayList<String>()
        try {
            if (oldFile.exists()) old.addAll(oldFile.readLines())
            if (logFile.exists()) old.addAll(logFile.readLines())
        } catch (_: Exception) {
            return
        }
        synchronized(writeLock) {
            val currentAll = recentAll.toList()
            val currentKey = recentKey.toList()
            recentAll.clear()
            recentKey.clear()
            for (line in old.dropLast(currentAll.size)) remember(line)
            currentAll.forEach { recentAll.addLast(it); if (recentAll.size > RECENT_ALL) recentAll.removeFirst() }
            currentKey.forEach { recentKey.addLast(it); if (recentKey.size > RECENT_KEY) recentKey.removeFirst() }
        }
    }

    /** 读最近的日志（含上一个文件），在 IO 线程调用 */
    fun readLines(keyOnly: Boolean, maxLines: Int): List<String> {
        if (!started) return emptyList()
        synchronized(writeLock) { writer?.flush() }
        val all = buildList {
            if (oldFile.exists()) addAll(oldFile.readLines())
            if (logFile.exists()) addAll(logFile.readLines())
        }
        val lines = if (keyOnly) all.filter(::isKeyLine) else all
        return lines.takeLast(maxLines)
    }

    fun clear() {
        if (!started) return
        synchronized(writeLock) {
            writer?.close()
            writer = null
            oldFile.delete()
            logFile.delete()
            recentAll.clear()
            recentKey.clear()
        }
        appendRaw("========== 已清空 ${now()} ==========")
        Log.i(TAG, snapshot())
    }

    /** 设备与后台限制状态，复制日志时放在最前面 */
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
                5 -> "exempted"; 10 -> "active"; 20 -> "working_set"; 30 -> "frequent"
                40 -> "rare"; 45 -> "restricted"; 50 -> "never"; else -> b.toString()
            }
        } else "n/a"
        val bgRestricted = if (Build.VERSION.SDK_INT >= 28) am.isBackgroundRestricted.toString() else "n/a"
        return "[诊断] 设备 ${Build.MANUFACTURER} ${Build.MODEL} Android ${Build.VERSION.RELEASE}(API ${Build.VERSION.SDK_INT})" +
                " OneUI=$oneUi 包=${ctx.packageName} 版本=$version" +
                " 忽略电池优化=${OemPermissionHelper.isIgnoringBatteryOptimizations(ctx)}" +
                " 后台受限=$bgRestricted 待机分组=$bucket 省电模式=${pm.isPowerSaveMode}"
    }

    private fun logExitReasons() {
        if (Build.VERSION.SDK_INT < 30) return
        try {
            val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val reasons = am.getHistoricalProcessExitReasons(appContext.packageName, 0, 5)
            if (reasons.isEmpty()) {
                Log.i(TAG, "[诊断] 无历史进程退出记录")
                return
            }
            reasons.forEach {
                Log.i(TAG, "[诊断] 历史退出 ${fmt(it.timestamp)} 原因=${exitReasonName(it.reason)}(${it.reason})" +
                        " 重要性=${it.importance} status=${it.status} 说明=${it.description ?: ""}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "[诊断] 读取历史退出原因失败: ${e.message}")
        }
    }

    private fun exitReasonName(reason: Int) = when (reason) {
        1 -> "主动退出"; 2 -> "被信号杀"; 3 -> "低内存"; 4 -> "崩溃"; 5 -> "Native崩溃"; 6 -> "ANR"
        7 -> "初始化失败"; 8 -> "权限变更"; 9 -> "资源占用过度"; 10 -> "用户请求"; 11 -> "用户停止"
        12 -> "依赖进程死亡"; 13 -> "其他"; 14 -> "冻结器"; 15 -> "包状态变更"; 16 -> "包更新"
        else -> "未知"
    }

    private fun startLogcatReader() {
        thread(name = "DiagLogcat", isDaemon = true) {
            preloadRecent()
            while (true) {
                try {
                    val proc = Runtime.getRuntime()
                        .exec(arrayOf("logcat", "-v", "threadtime", "--pid=${Process.myPid()}"))
                    proc.inputStream.bufferedReader().forEachLine { appendRaw(it) }
                    proc.destroy()
                } catch (e: Exception) {
                    appendRaw("${now()} [诊断] logcat 读取异常: ${e.message}")
                }
                SystemClock.sleep(1000)
            }
        }
    }

    private fun startTicker() {
        thread(name = "DiagTicker", isDaemon = true) {
            var last = SystemClock.elapsedRealtime()
            while (true) {
                SystemClock.sleep(TICK_MS)
                val nowMs = SystemClock.elapsedRealtime()
                val gap = nowMs - last
                last = nowMs
                if (gap > TICK_MS * 2) {
                    Log.w(TAG, "[诊断] ⚠️ 心跳间隔 ${gap / 1000}s（应为 ${TICK_MS / 1000}s）：期间进程被冻结或 CPU 休眠")
                }
                try {
                    Log.i(TAG, "[诊断] 心跳 ${statusLine()}")
                } catch (e: Exception) {
                    Log.w(TAG, "[诊断] 心跳异常: ${e.message}")
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
        return "屏幕=${if (pm.isInteractive) "亮" else "灭"} 进程重要性=${info.importance}" +
                " Doze=${pm.isDeviceIdleMode} 省电=${pm.isPowerSaveMode} 网络=${networkDesc()}" +
                " WS=${if (WebSocketManager.instance.isConnected) "已连" else "断开"} 前台服务=$fgService"
    }

    private fun networkDesc(): String {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return "无"
        val type = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WiFi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "蜂窝"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "以太网"
            else -> "其他"
        }
        val dataSaver = when (cm.restrictBackgroundStatus) {
            ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED -> " 流量节省=开"
            else -> ""
        }
        return type + dataSaver
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
                Log.i(TAG, "[诊断] 系统事件 ${intent.action?.substringAfterLast('.')} | ${statusLine()}")
            }
        }
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                appContext.registerReceiver(receiver, filter)
            }
        } catch (e: Exception) {
            Log.w(TAG, "[诊断] 注册系统事件失败: ${e.message}")
        }
        try {
            val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    Log.i(TAG, "[诊断] 网络可用 ${networkDesc()}")
                }

                override fun onLost(network: Network) {
                    Log.w(TAG, "[诊断] 网络断开")
                }

                override fun onBlockedStatusChanged(network: Network, blocked: Boolean) {
                    Log.w(TAG, "[诊断] 网络被系统${if (blocked) "封锁（后台限制）" else "解除封锁"}")
                }
            })
        } catch (e: Exception) {
            Log.w(TAG, "[诊断] 注册网络回调失败: ${e.message}")
        }
    }

    private fun appendRaw(line: String) {
        synchronized(writeLock) {
            try {
                val w = writer ?: FileWriter(logFile, true).also { writer = it }
                remember(line)
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

    private fun fmt(ms: Long) = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date(ms))
}
