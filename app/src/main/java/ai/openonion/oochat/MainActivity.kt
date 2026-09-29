package ai.openonion.oochat

import ai.openonion.oochat.data.local.ThemePreferences
import ai.openonion.oochat.domain.model.ThemeMode
import ai.openonion.oochat.ui.theme.ConnectOnionTheme
import ai.openonion.oochat.ui.theme.LocalThemeController
import ai.openonion.oochat.ui.theme.ThemeController
import ai.openonion.oochat.util.FileLogger
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Install splash screen BEFORE super.onCreate() so the system
        // can show the windowSplashScreen* attributes defined on
        // Theme.ConnectOnion.Starting until the first frame is ready.
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        FileLogger.init(this)
        // We could call splashScreen.setKeepOnScreenCondition { ... } here
        // if we needed to gate on async work. Our Compose SplashScreen
        // already handles the brief delay before startDestination is known,
        // so we don't need an additional gate.
        splashScreen.setKeepOnScreenCondition { false }
        // Draw behind the (now transparent) system bars. The window no longer
        // resizes for the IME once decorFitsSystemWindows is false, so every
        // screen takes its own insets — see ChatScreen's windowInsetsPadding.
        enableEdgeToEdge()
        setContent {
            // Persisted theme mode drives the palette; SYSTEM until DataStore emits.
            val themePrefs = remember { ThemePreferences(applicationContext) }
            val scope = rememberCoroutineScope()
            val themeMode by themePrefs.themeMode.collectAsState(initial = ThemeMode.SYSTEM)

            CompositionLocalProvider(
                LocalThemeController provides ThemeController(
                    mode = themeMode,
                    setMode = { mode -> scope.launch { themePrefs.setThemeMode(mode) } }
                )
            ) {
                ConnectOnionTheme(themeMode = themeMode) {
                    ConnectOnionApp()
                }
            }
        }
    }
}
