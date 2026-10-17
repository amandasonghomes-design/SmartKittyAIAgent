package com.myagent.app

import android.graphics.Color as AndroidColor
import android.content.Intent
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

class MainActivity : ComponentActivity() {
    private val vm: AgentViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vm.auth = GoogleAuth(this) // must be created before the screen starts
        if (savedInstanceState == null) handleMlsRedirect(intent)
        // Dark status-bar icons, readable on the light-blue sky in any phone theme
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT),
        )
        setContent {
            // Always the dreamy light-blue look, even when the phone is in dark mode.
            val colors = lightColorScheme(
                primary = Color(0xFF2F7FD1),
                secondary = Color(0xFF5BA8E0),
                primaryContainer = Color(0xFFD6ECFF),
                secondaryContainer = Color(0xFFE3F2FF),
                tertiaryContainer = Color(0xFFE8F0FF),
                surface = Color(0xFFF4FAFF),
                onSurface = Color(0xFF1B2B45),
                onSurfaceVariant = Color(0xFF3D6E9E),
                background = Color(0xFFE6F5FF),
                surfaceVariant = Color(0xFFDDEFFF),
                outline = Color(0xFF8CC2EE),
                outlineVariant = Color(0xFFBFDFF8),
                surfaceTint = Color(0xFF2F7FD1),
                surfaceBright = Color(0xFFF4FAFF),
                surfaceDim = Color(0xFFD3E9FB),
                surfaceContainerLowest = Color(0xFFFFFFFF),
                surfaceContainerLow = Color(0xFFF0F8FF),
                surfaceContainer = Color(0xFFEAF5FF),
                surfaceContainerHigh = Color(0xFFE3F2FF),
                surfaceContainerHighest = Color(0xFFDCEEFF),
                inverseSurface = Color(0xFF1E4F80),
                scrim = Color(0xFF0E2A4A),
            )
            MaterialTheme(colorScheme = colors) {
                ChatScreen(vm)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleMlsRedirect(intent)
    }

    /** Flexmls login finished and sent the browser back to the app. */
    private fun handleMlsRedirect(intent: Intent?) {
        val uri = intent?.data ?: return
        // The "Back to 猫猫小助手" button on the sign-in page just brings the app forward;
        // the sign-in itself was already finished by the app's little listener.
        if (uri.scheme == "com.smartkitty.app" && uri.host == "oauth") intent.data = null
    }

    override fun onStop() {
        vm.saveRecentActivity() // jot down what this chat was about
        vm.stopVoiceMode() // never listen in the background
        super.onStop()
    }

    override fun onDestroy() {
        vm.auth = null
        super.onDestroy()
    }
}
