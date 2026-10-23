package com.fantadrom.copperscroll

import android.graphics.Color as AndroidColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.fantadrom.copperscroll.ui.theme.CopperScrollTheme

class MainActivity : ComponentActivity() {
    private val workspace by lazy { androidx.lifecycle.ViewModelProvider(this)[WorkspaceState::class.java] }
    override fun onStop() {
        if (!isChangingConfigurations) workspace.note?.checkpoint(SaveReason.BACKGROUND)
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
        )
        window.isNavigationBarContrastEnforced = false
        setContent {
            CopperScrollTheme {
                WorkspaceScreen(workspace)
            }
        }
    }
}
