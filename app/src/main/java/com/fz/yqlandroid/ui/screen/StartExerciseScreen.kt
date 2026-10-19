package com.fz.yqlandroid.ui.screen

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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fz.yqlandroid.R
import com.fz.yqlandroid.network.NetworkService
import kotlinx.coroutines.launch

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

/**
 * 启动页之后的首页。点封面里的「开始运动」直接进登录页自动登录；
 * 是否开通 SRT全码 以登录结果为准，没开通在登录页弹 [SrtCodeDialog] 输 SRT码。
 */
@Composable
fun StartExerciseScreen(onStart: () -> Unit) {
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
                        onClick = onStart
                    )
            )
        }
    }
}

/** SRT码输入：后端兑换成功（开通 SRT全码）才算通过；失败显示后端原因 */
@Composable
fun SrtCodeDialog(jwtToken: String, onVerified: () -> Unit, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun submit() {
        busy = true
        error = null
        scope.launch {
            NetworkService.activateCode(input.trim(), jwtToken).fold(
                onSuccess = {
                    if (it.srtFull == true) onVerified() else error = "这不是 SRT码，请确认后重新输入"
                },
                onFailure = { error = it.message ?: "验证失败，请重试" }
            )
            busy = false
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("请输入SRT码") },
        text = {
            Column {
                OutlinedTextField(
                    value = input,
                    onValueChange = {
                        input = it.uppercase().filter { c -> c.isLetterOrDigit() || c == '-' }.take(19)
                        error = null
                    },
                    singleLine = true,
                    enabled = !busy,
                    placeholder = { Text("XXXX-XXXX-XXXX-XXXX") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii)
                )
                error?.let {
                    Text(it, color = Color(0xFFE57373), fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = ::submit, enabled = input.isNotBlank() && !busy) {
                Text(if (busy) "验证中…" else "验证")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") }
        }
    )
}
