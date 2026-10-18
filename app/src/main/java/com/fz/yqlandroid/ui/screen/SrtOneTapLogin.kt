package com.fz.yqlandroid.ui.screen

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** srtapp 登录页账号卡片：只读显示本机账号（自动注册中 / 失败可重试） */
@Composable
fun SrtAccountCard(
    username: String,
    preparing: Boolean,
    error: String?,
    onRetry: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .border(0.6.dp, Color(0xFFB3B3B3), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Person,
                        contentDescription = null,
                        modifier = Modifier.size(12.dp),
                        tint = Color(0xFF1A1A1A)
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Text(text = "账号", fontSize = 16.sp, color = Color(0xFF666666))
                Spacer(modifier = Modifier.width(16.dp))
                when {
                    preparing -> {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = Color(0xFF65AEF7),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = "正在生成账号…", fontSize = 16.sp, color = Color(0xFFA3A3A3))
                    }
                    username.isNotEmpty() -> Text(
                        text = username,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFF1A1A1A)
                    )
                    else -> Text(text = "未获取到账号", fontSize = 16.sp, color = Color(0xFFA3A3A3))
                }
            }
            if (!preparing && error != null) {
                Spacer(modifier = Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "账号生成失败：$error",
                        fontSize = 14.sp,
                        color = Color.Red,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onRetry) {
                        Text("重试", color = Color(0xFF65AEF7), fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }
}

/** 默认密码登录被拒（密码已被改过）时的兜底：手动输入登录密码 */
@Composable
fun SrtPasswordPromptDialog(
    username: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var input by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("请输入登录密码") },
        text = {
            Column {
                Text(
                    text = "账号 $username 的登录密码已不是默认密码，请输入当前登录密码。",
                    fontSize = 14.sp,
                    color = Color(0xFF333333)
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text("登录密码") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(enabled = input.isNotEmpty(), onClick = { onConfirm(input) }) {
                Text("登录", color = Color(0xFF65AEF7), fontWeight = FontWeight.Medium)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
