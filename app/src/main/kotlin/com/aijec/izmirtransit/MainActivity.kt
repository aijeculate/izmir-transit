package com.aijec.izmirtransit

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.aijec.izmirtransit.core.ui.IzmirTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        openStopRequest = intent?.getStringExtra(EXTRA_OPEN_STOP)
        setContent {
            IzmirTheme {
                AppNavHost(openStop = openStopRequest, onOpenStopHandled = { openStopRequest = null })
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
