package com.aijec.izmirtransit

import kotlinx.coroutines.launch
import com.aijec.izmirtransit.onboarding.Onboarding
import com.aijec.izmirtransit.core.ui.style
import com.aijec.izmirtransit.core.ui.glassBackdrop
import com.aijec.izmirtransit.core.model.UiStyle
import androidx.compose.ui.Modifier
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aijec.izmirtransit.core.data.repository.SettingsRepository
import com.aijec.izmirtransit.core.ui.Calm
import com.aijec.izmirtransit.core.ui.IzmirTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var settingsRepository: SettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        openStopRequest = intent?.getStringExtra(EXTRA_OPEN_STOP)
        // Read once before the first frame so a chosen theme never flashes the wrong one;
        // a tiny local file.
        val initial = runBlocking { settingsRepository.settings.first() }
        setContent {
            val settings by settingsRepository.settings.collectAsStateWithLifecycle(initial)
            IzmirTheme(themeMode = settings.themeMode, accentTheme = settings.accent, style = settings.style) {
                // Status and navigation bar icons follow the app's theme, not the system's:
                // dark icons on the light theme even when the phone is in dark mode.
                val dark = Calm.colors.isDark
                DisposableEffect(dark) {
                    val style = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                    enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                    onDispose { }
                }
                if (settings.onboardingDone) {
                    AppNavHost(openStop = openStopRequest, onOpenStopHandled = { openStopRequest = null })
                } else {
                    val scope = rememberCoroutineScope()
                    val glass = Calm.style == UiStyle.LIQUID_GLASS
                    Box(Modifier.fillMaxSize().then(if (glass) Modifier.glassBackdrop() else Modifier)) {
                        Onboarding(onDone = { scope.launch { settingsRepository.setOnboardingDone(true) } })
                    }
                }
            }
        }
    }

    /** An alert notification tapped while the app is open: show that stop. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_OPEN_STOP)?.let { openStopRequest = it }
    }

    private var openStopRequest by mutableStateOf<String?>(null)

    companion object {
        const val EXTRA_OPEN_STOP = "openStop"
    }
}
