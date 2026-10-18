package com.fz.yqlandroid.ui.screen

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fz.yqlandroid.R

/**
 * 横屏封面转正后铺满竖屏。原图右侧「开始运动」按钮在转正后落在画面偏下的一条横条上。
 */
@Composable
fun HealthHomeCover(modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        Image(
            painter = painterResource(id = R.drawable.home_screen),
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            modifier = Modifier
                .align(Alignment.Center)
                .wrapContentSize(unbounded = true)
                .size(maxHeight, maxWidth)
                .rotate(90f)
        )
    }
}

/** 授权码：验证过一次存本地，之后不再输入 */
object AuthCodeStore {
    private const val PREFS = "auth_code_prefs"
    private const val KEY_VERIFIED = "verified"
    private const val CODE = "121314"

    fun isVerified(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_VERIFIED, false)

    /** 输对返回 true 并记住 */
    fun verify(context: Context, input: String): Boolean {
        if (input.trim() != CODE) return false
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_VERIFIED, true).apply()
        return true
    }
}

/**
 * 启动页之后的首页。点封面里的「开始运动」：未验证授权码先弹框输入，验证过直接进入（随后自动登录）。
 */
@Composable
fun StartExerciseScreen(onStart: () -> Unit) {
    val context = LocalContext.current
    var showAuthDialog by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        HealthHomeCover()
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            // 原图按钮 x 0.76–0.80、y 0.11–0.89，顺时针转 90° 后的位置，略放大方便点中
            Box(
                modifier = Modifier
                    .offset(x = maxWidth * 0.08f, y = maxHeight * 0.72f)
                    .size(width = maxWidth * 0.84f, height = maxHeight * 0.12f)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {
                            if (AuthCodeStore.isVerified(context)) onStart() else showAuthDialog = true
                        }
                    )
            )
        }
    }

    if (showAuthDialog) {
        AuthCodeDialog(
            onVerified = {
                showAuthDialog = false
                onStart()
            },
            onDismiss = { showAuthDialog = false }
        )
    }
}

@Composable
private fun AuthCodeDialog(onVerified: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var input by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    fun submit() {
        if (AuthCodeStore.verify(context, input)) onVerified() else error = "授权码错误，请重新输入"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("请输入授权码") },
        text = {
            Column {
                OutlinedTextField(
                    value = input,
                    onValueChange = {
                        input = it.filter(Char::isDigit).take(12)
                        error = null
                    },
                    singleLine = true,
                    placeholder = { Text("授权码") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                error?.let {
                    Text(it, color = Color(0xFFE57373), fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = ::submit, enabled = input.isNotBlank()) { Text("验证") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
