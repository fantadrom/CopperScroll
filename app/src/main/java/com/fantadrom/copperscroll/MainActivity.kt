package com.fantadrom.copperscroll

import android.graphics.Color as AndroidColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import com.fantadrom.copperscroll.ui.theme.CopperScrollTheme
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
        )
        window.isNavigationBarContrastEnforced = false
        setContent {
            CopperScrollTheme {
                StartupScreen()
            }
        }
    }
}

@Composable
private fun StartupScreen() {
    var ready by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        // Present the startup frame before completing this minimal launch.
        withFrameNanos { }
        // Keep the startup message readable even on a fast device.
        delay(500)
        ready = true
    }

    Box(
        modifier = Modifier.fillMaxSize().background(colorResource(R.color.screen_background)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = stringResource(if (ready) R.string.startup_done else R.string.startup_loading),
            color = Color.Black
        )
    }
}
