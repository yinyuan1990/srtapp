package com.fz.yqlandroid.ui.screen

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.fz.yqlandroid.manager.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** 微信单条消息放得下 */
private const val COPY_MAX_CHARS = 3000

/**
 * 悬浮窗开关：登录页连点 Logo 5 次切换，持久化。
 * 有「显示在其他应用上层」权限 → 系统悬浮窗（桌面也显示）；没权限 → App 内悬浮按钮兜底。
 */
object DebugLogOverlayState {
    val visible = mutableStateOf(false)
    /** 当前用的是系统悬浮窗（App 内按钮就不画了） */
    val systemWindow = mutableStateOf(false)

    fun init(context: Context) {
        visible.value = DebugLog.isOverlayEnabled(context)
    }

    /** Activity onResume 调：从授权页回来后切到系统悬浮窗 */
    fun sync(context: Context) {
        if (visible.value && FloatingLogWindow.canDraw(context)) FloatingLogWindow.show(context)
        if (!visible.value) FloatingLogWindow.hide()
        systemWindow.value = FloatingLogWindow.isShowing
    }

    fun toggle(context: Context) {
        val v = !visible.value
        visible.value = v
        DebugLog.setOverlayEnabled(context, v)
        if (v && !FloatingLogWindow.canDraw(context)) {
            Toast.makeText(context, "请允许「显示在其他应用上层」，退到桌面也能看日志", Toast.LENGTH_LONG).show()
            FloatingLogWindow.requestPermission(context)
        } else {
            Toast.makeText(context, if (v) "已打开日志悬浮窗" else "已关闭日志悬浮窗", Toast.LENGTH_SHORT).show()
        }
        sync(context)
    }
}

/** 可拖动的「日志」悬浮按钮，点开看诊断日志并复制。画在 NavHost 之上，盖得住推流页封面。 */
@Composable
fun DebugLogOverlay() {
    var open by remember { mutableStateOf(false) }
    val density = LocalDensity.current

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val bubble = 52.dp
        val maxX = with(density) { (maxWidth - bubble).toPx() }
        val maxY = with(density) { (maxHeight - bubble).toPx() }
        var pos by remember { mutableStateOf(androidx.compose.ui.geometry.Offset(maxX, maxY * 0.35f)) }

        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .offset { IntOffset(pos.x.roundToInt(), pos.y.roundToInt()) }
                .size(bubble)
                .clip(CircleShape)
                .background(Color(0xCC222222))
                .pointerInput(maxX, maxY) {
                    detectDragGestures { change, drag ->
                        change.consume()
                        pos = androidx.compose.ui.geometry.Offset(
                            (pos.x + drag.x).coerceIn(0f, maxX),
                            (pos.y + drag.y).coerceIn(0f, maxY)
                        )
                    }
                }
                .clickable { open = true }
        ) {
            Text("日志", color = Color.White, fontSize = 14.sp)
        }
    }

    if (open) DebugLogPanel(onClose = { open = false })
}

@Composable
private fun DebugLogPanel(onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    var lines by remember { mutableStateOf<List<String>>(emptyList()) }
    val listState = rememberLazyListState()

    LaunchedEffect(refresh) {
        lines = collapseRepeats(DebugLog.recentLines())
        if (lines.isNotEmpty()) listState.scrollToItem(lines.size - 1)
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF111111))
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                PanelButton("刷新") { refresh++ }
                PanelButton("复制") {
                    scope.launch {
                        val text = withContext(Dispatchers.IO) { buildCopyText(DebugLog.readLines()) }
                        copyToClipboard(context, text)
                    }
                }
                PanelButton("清空") {
                    scope.launch {
                        withContext(Dispatchers.IO) { DebugLog.clear() }
                        refresh++
                    }
                }
                PanelButton("关闭", onClose)
            }
            Text(
                text = "${lines.size} 行；复制取最新 $COPY_MAX_CHARS 字以内",
                color = Color(0xFF999999),
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 8.dp)
            )
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(horizontal = 6.dp, vertical = 4.dp)
            ) {
                items(lines) { line ->
                    Text(
                        text = line,
                        color = lineColor(line),
                        fontSize = 10.sp,
                        lineHeight = 12.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}

@Composable
private fun PanelButton(text: String, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Text(text, color = Color(0xFF65AEF7), fontSize = 14.sp)
    }
}

private fun lineColor(line: String): Color = when {
    line.contains("⚠️") || line.contains("❌") || line.contains("断") || line.contains("失败") -> Color(0xFFFFB74D)
    line.startsWith("====") -> Color(0xFF65AEF7)
    else -> Color(0xFFDDDDDD)
}

/** 连续内容相同（去掉开头时间）的行合并成一行并标次数，如 WS 每 5s 重连失败 */
internal fun collapseRepeats(lines: List<String>): List<String> {
    val out = ArrayList<String>()
    var prevBody: String? = null
    var count = 0
    var firstLine = ""
    var lastTime = ""
    fun flush() {
        if (prevBody != null) out.add(if (count > 1) "$firstLine (×$count 至$lastTime)" else firstLine)
    }
    for (line in lines) {
        val body = line.substringAfter(' ', line)
        if (body == prevBody) {
            count++
            lastTime = line.substringBefore(' ')
        } else {
            flush()
            prevBody = body
            count = 1
            firstLine = line
            lastTime = line.substringBefore(' ')
        }
    }
    flush()
    return out
}

/** 设备状态放最前，正文取最新的、合并重复后总长不超过微信单条上限 */
internal fun buildCopyText(lines: List<String>): String {
    val header = try { DebugLog.snapshot() } catch (_: Exception) { "" }
    val body = collapseRepeats(lines)
    var start = body.size
    var total = header.length + 1
    while (start > 0 && total + body[start - 1].length + 1 <= COPY_MAX_CHARS) {
        start--
        total += body[start].length + 1
    }
    return header + "\n" + body.subList(start, body.size).joinToString("\n")
}

internal fun copyToClipboard(context: Context, text: String) {
    try {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("诊断日志", text))
        Toast.makeText(context, "已复制 ${text.length} 字", Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
        Toast.makeText(context, "复制失败: ${e.message}", Toast.LENGTH_LONG).show()
    }
}
