package com.fz.yqlandroid.manager

import android.content.Context
import com.fz.yqlandroid.network.ApiError
import com.fz.yqlandroid.network.NetworkService
import com.fz.yqlandroid.network.RegisterRequest
import kotlin.random.Random

/**
 * srtapp 自动账号：首启随机注册（一机一码），登录页只显示账号 + 一键登录。
 * 金凤凰（其它包名）不启用，登录/注册行为不变。
 *
 * 账号 = 10 位纯数字（首位非 0），客户端生成；后端要求账号、前 8 位、昵称均全库唯一，撞了换号重试。
 * 本机已注册过（清数据/重装）时后端拒绝注册并回传 existingUsername → 直接认领原账号。
 */
object AutoAccountManager {
    const val DEFAULT_PASSWORD = "888888"
    const val DEFAULT_SECONDARY_PASSWORD = "888888"

    private const val PREFS = "login_prefs"
    private const val MAX_REGISTER_ATTEMPTS = 5

    fun isEnabled(context: Context): Boolean = context.packageName == "com.fz.srtapp"

    fun savedUsername(context: Context): String? =
        prefs(context).getString("username", null)?.takeIf { it.isNotBlank() }

    /** 本地记住的真实密码（一键登录 / 手动输入成功后会写回）；没有则用默认密码 */
    fun savedPassword(context: Context): String =
        prefs(context).getString("password", null)?.takeIf { it.isNotEmpty() } ?: DEFAULT_PASSWORD

    fun saveAccount(context: Context, username: String, password: String = DEFAULT_PASSWORD) {
        prefs(context).edit()
            .putString("username", username)
            .putString("password", password)
            .putBoolean("remember", true)
            .apply()
    }

    fun clearAccount(context: Context) {
        prefs(context).edit().remove("username").remove("password").apply()
    }

    /** 返回本机账号：本地有就直接用，没有就自动注册（或认领本机已注册的账号） */
    suspend fun ensureAccount(context: Context): Result<String> {
        savedUsername(context)?.let { return Result.success(it) }

        val deviceId = DeviceIDManager.getDeviceID(context)
        var lastError: Throwable = Exception("注册失败")
        repeat(MAX_REGISTER_ATTEMPTS) {
            val username = randomUsername()
            val result = NetworkService.registerDevice(
                RegisterRequest(
                    username = username,
                    nickname = username.take(8),
                    deviceId = deviceId,
                    password = DEFAULT_PASSWORD,
                    secondaryPassword = DEFAULT_SECONDARY_PASSWORD,
                    securityQuestion1 = "您的出生年月日是？",
                    securityAnswer1 = "1",
                    securityQuestion2 = "您的老家是哪里？",
                    securityAnswer2 = "2",
                    securityQuestion3 = "您最喜欢干的事是？",
                    securityAnswer3 = "3"
                )
            )
            val registered = result.getOrNull()
            if (registered != null) {
                val name = registered.username ?: username
                saveAccount(context, name)
                println("jfh [AutoAccount] ✅ 自动注册成功: $name")
                return Result.success(name)
            }
            val error = result.exceptionOrNull() ?: Exception("注册失败")
            val existing = (error as? ApiError)?.fields?.get("existingUsername")
            if (!existing.isNullOrBlank()) {
                saveAccount(context, existing)
                println("jfh [AutoAccount] ✅ 本机已注册过，认领原账号: $existing")
                return Result.success(existing)
            }
            lastError = error
            if (!isUsernameConflict(error.message)) return Result.failure(error)
            println("jfh [AutoAccount] ⚠️ 账号 $username 冲突(${error.message})，换号重试")
        }
        return Result.failure(lastError)
    }

    private fun randomUsername(): String =
        Random.nextInt(1, 10).toString() + (1..9).joinToString("") { Random.nextInt(0, 10).toString() }

    private fun isUsernameConflict(message: String?): Boolean =
        message != null && (message.contains("用户名") || message.contains("昵称"))

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
