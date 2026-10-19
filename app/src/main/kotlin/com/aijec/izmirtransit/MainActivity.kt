package com.aijec.izmirtransit

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.aijec.izmirtransit.core.ui.IzmirTheme
import com.aijec.izmirtransit.feature.linemap.LineMapRoute
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            IzmirTheme {
                LineMapRoute()
            }
        }
    }
}
