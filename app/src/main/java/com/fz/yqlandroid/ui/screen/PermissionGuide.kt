package com.fz.yqlandroid.ui.screen

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.fz.yqlandroid.manager.OemPermissionHelper

/**
 * §130 登录前权限检查：推流要用的权限没开 → 先引导打开再登录。
 *
 * - 必需（不开就不能推流，开齐才放行登录）：相机、麦克风（推流页预览以这两项为门槛）。
 * - 建议（不挡登录，可跳过，跳过后 24h 内不再因它们弹框）：通知（Android 13+，前台服务常驻通知）、
 *   忽略电池优化（锁屏/切后台不断流）。
 * - 运行时权限被「拒绝且不再询问」时系统框弹不出来 → 按钮变「去设置」跳应用详情/通知设置页；
 *   从设置页回来（ON_RESUME）自动刷新状态，全部开好自动继续登录。
 */
object LoginPermissionCheck {

    enum class Kind { CAMERA, MIC, NOTIFICATION, BATTERY }

    data class Item(val kind: Kind, val title: String, val desc: String, val required: Boolean)

    private const val PREFS = "login_prefs"
    private const val KEY_OPTIONAL_SKIP_UNTIL = "perm_guide_optional_skip_until"
    private const val SKIP_MS = 24 * 3600 * 1000L

    fun items(): List<Item> = buildList {
        add(Item(Kind.CAMERA, "相机", "推流画面必须", required = true))
        add(Item(Kind.MIC, "麦克风", "推流采集链路需要", required = true))
        if (Build.VERSION.SDK_INT >= 33) {
            add(Item(Kind.NOTIFICATION, "通知", "后台推流常驻通知，防止被系统回收", required = false))
        }
        add(Item(Kind.BATTERY, "后台运行(忽略电池优化)", "锁屏/切后台时不断流", required = false))
    }

    fun permissionOf(kind: Kind): String? = when (kind) {
        Kind.CAMERA -> Manifest.permission.CAMERA
        Kind.MIC -> Manifest.permission.RECORD_AUDIO
        Kind.NOTIFICATION -> if (Build.VERSION.SDK_INT >= 33) Manifest.permission.POST_NOTIFICATIONS else null
        Kind.BATTERY -> null
    }

    fun isGranted(context: Context, kind: Kind): Boolean = when (kind) {
        Kind.CAMERA, Kind.MIC -> granted(context, permissionOf(kind)!!)
        Kind.NOTIFICATION -> NotificationManagerCompat.from(context).areNotificationsEnabled()
        Kind.BATTERY -> OemPermissionHelper.isIgnoringBatteryOptimizations(context)
    }

    private fun granted(context: Context, perm: String) =
        ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED

    fun requiredGranted(context: Context) = items().filter { it.required }.all { isGranted(context, it.kind) }

    /** 登录按钮调用：必需项没开 → 一定弹；只差建议项 → 未在跳过期内才弹 */
    fun shouldShowGuide(context: Context): Boolean {
        val missing = items().filterNot { isGranted(context, it.kind) }
        Log.d("meidui", "🔐 [登录权限检查] 未开启: ${missing.joinToString { it.title }.ifEmpty { "无" }}")
        if (missing.isEmpty()) return false
        if (missing.any { it.required }) return true
        val skipUntil = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_OPTIONAL_SKIP_UNTIL, 0)
        return System.currentTimeMillis() > skipUntil
    }

    fun markOptionalSkipped(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_OPTIONAL_SKIP_UNTIL, System.currentTimeMillis() + SKIP_MS).apply()
    }

    /** 申请过一次的运行时权限（用于区分「首次」与「拒绝且不再询问」——两者 shouldShowRationale 都是 false） */
    fun markRequested(context: Context, perm: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("perm_requested_$perm", true).apply()
    }

    fun wasRequested(context: Context, perm: String) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("perm_requested_$perm", false)

    fun openAppSettings(context: Context) {
        try {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Log.e("LoginPermissionCheck", "打开应用设置失败: ${e.message}")
        }
    }

    fun openNotificationSettings(context: Context) {
        try {
            val intent = if (Build.VERSION.SDK_INT >= 26) {
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            } else {
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
            }
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            openAppSettings(context)
        }
    }

    tailrec fun findActivity(context: Context): Activity? = when (context) {
        is Activity -> context
        is ContextWrapper -> findActivity(context.baseContext)
        else -> null
    }
}

/**
 * §130 登录前权限引导弹框。每项显示状态 + 「去开启」；必需项开齐后「继续登录」可点；
 * 全部开好自动继续登录。
 */
@Composable
fun PermissionGuideDialog(onContinue: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }

    // 从系统设置页/授权框回来刷新状态
    @Suppress("DEPRECATION")
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        Log.d("meidui", "🔐 [登录权限检查] 系统授权框结果=${if (ok) "允许" else "拒绝"}")
        refresh++
    }

    val items = remember { LoginPermissionCheck.items() }
    // refresh 变（授权框返回/从设置页回来）即重查
    val status = remember(refresh) { items.associateWith { LoginPermissionCheck.isGranted(context, it.kind) } }
    val requiredOk = items.filter { it.required }.all { status[it] == true }
    val allOk = status.values.all { it }
    var continued by remember { mutableStateOf(false) }
    val proceed = {
        if (!continued) {
            continued = true
            onContinue()
        }
    }

    LaunchedEffect(allOk) {
        if (allOk) {
            Log.d("meidui", "🔐 [登录权限检查] 全部已开启 → 继续登录")
            proceed()
        }
    }

    fun openItem(item: LoginPermissionCheck.Item) {
        val activity = LoginPermissionCheck.findActivity(context)
        when (item.kind) {
            LoginPermissionCheck.Kind.BATTERY -> OemPermissionHelper.requestIgnoreBatteryOptimizations(context)
            else -> {
                val perm = LoginPermissionCheck.permissionOf(item.kind)
                if (perm == null) {
                    LoginPermissionCheck.openNotificationSettings(context)
                    return
                }
                val blocked = LoginPermissionCheck.wasRequested(context, perm) && activity != null &&
                        !ActivityCompat.shouldShowRequestPermissionRationale(activity, perm)
                if (blocked) {
                    // 「拒绝且不再询问」：系统框不会再弹，只能去设置页手动开
                    Log.d("meidui", "🔐 [登录权限检查] ${item.title} 已被永久拒绝 → 跳设置页")
                    if (item.kind == LoginPermissionCheck.Kind.NOTIFICATION) LoginPermissionCheck.openNotificationSettings(context)
                    else LoginPermissionCheck.openAppSettings(context)
                } else {
                    LoginPermissionCheck.markRequested(context, perm)
                    launcher.launch(perm)
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("请先开启以下权限") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("推流需要用到这些权限，未开启会导致没有画面或后台断流。", fontSize = 13.sp, color = Color(0xFF666666))
                items.forEach { item ->
                    val ok = status[item] == true
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = item.title + if (item.required) "（必需）" else "（建议）",
                                fontSize = 15.sp,
                                color = Color(0xFF1A1A1A)
                            )
                            Text(item.desc, fontSize = 12.sp, color = Color(0xFF999999))
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        if (ok) {
                            Text("已开启", fontSize = 13.sp, color = Color(0xFF4CAF50))
                        } else {
                            TextButton(onClick = { openItem(item) }) {
                                Text("去开启", fontSize = 13.sp, color = Color(0xFF65AEF7))
                            }
                        }
                    }
                }
                if (!requiredOk) {
                    Text("相机和麦克风开启后才能登录推流。若系统不再弹授权框，请在「设置-应用-权限」里手动打开。",
                        fontSize = 12.sp, color = Color(0xFFE57373), modifier = Modifier.padding(top = 4.dp))
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (!allOk) LoginPermissionCheck.markOptionalSkipped(context)
                    Log.d("meidui", "🔐 [登录权限检查] 用户继续登录（建议项未全开=${!allOk}）")
                    proceed()
                },
                enabled = requiredOk,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF65AEF7))
            ) {
                Text(if (allOk) "继续登录" else "跳过建议项，继续登录")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
