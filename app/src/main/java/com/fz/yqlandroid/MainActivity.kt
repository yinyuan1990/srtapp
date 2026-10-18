package com.fz.yqlandroid

import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.fz.yqlandroid.manager.DebugLog
import com.fz.yqlandroid.navigation.AppNavHost
import com.fz.yqlandroid.ui.screen.DebugLogOverlay
import com.fz.yqlandroid.ui.screen.DebugLogOverlayState
import com.fz.yqlandroid.ui.theme.YqlandroidTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 🔥 固定竖屏，不随物理旋转切换
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        enableEdgeToEdge()
        DebugLog.start(this)
        DebugLogOverlayState.init(this)
        setContent {
            YqlandroidTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        AppNavHost()
                        if (DebugLogOverlayState.visible.value && !DebugLogOverlayState.systemWindow.value) {
                            DebugLogOverlay()
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        DebugLogOverlayState.sync(this)
    }
}
