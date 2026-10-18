package com.fz.yqlandroid.ui.screen

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
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

/**
 * 启动页之后的首页。点封面里的「开始运动」才进入登录。
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
