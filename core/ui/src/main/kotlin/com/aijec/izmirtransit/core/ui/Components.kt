package com.aijec.izmirtransit.core.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aijec.izmirtransit.core.model.LiveError
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant

/** Line number in a filled badge, used wherever a line is listed. */
@Composable
fun LineBadge(code: String, modifier: Modifier = Modifier) {
    Surface(color = MaterialTheme.colorScheme.primary, shape = RoundedCornerShape(8.dp), modifier = modifier) {
        Text(
            code,
            color = MaterialTheme.colorScheme.onPrimary,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

/** The current time, ticking every second, for "updated N s ago" labels. */
@Composable
fun rememberNow(): State<Instant> = produceState(Instant.now()) {
    while (true) {
        delay(1_000)
        value = Instant.now()
    }
}

/** "12 sn" / "3 dk" since [since]. */
@Composable
fun formatAge(since: Instant, now: Instant): String {
    val seconds = Duration.between(since, now).seconds.coerceAtLeast(0)
    return if (seconds < 60) stringResource(R.string.ui_age_seconds, seconds.toInt())
    else stringResource(R.string.ui_age_minutes, (seconds / 60).toInt())
}

@Composable
fun liveErrorText(error: LiveError?): String = stringResource(
    when (error) {
        LiveError.RATE_LIMITED -> R.string.ui_error_busy
        LiveError.SERVER, LiveError.MALFORMED -> R.string.ui_error_server
        LiveError.NETWORK, null -> R.string.ui_error_network
    },
)
