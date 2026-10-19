package com.aijec.izmirtransit.core.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aijec.izmirtransit.core.model.Announcement
import com.aijec.izmirtransit.core.model.LiveError
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeFormatter


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

/** A route change or disruption notice, with its line and how long it lasts. */
@Composable
fun AnnouncementCard(announcement: Announcement, showLine: Boolean, modifier: Modifier = Modifier) {
    Card(
        modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            Icon(
                Icons.Filled.Warning,
                contentDescription = stringResource(R.string.ui_announcement),
                tint = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                if (showLine) {
                    Text(
                        stringResource(R.string.ui_announcement_line, announcement.lineCode),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
                Text(announcement.title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onTertiaryContainer)
                announcement.endsAt?.let { end ->
                    Text(
                        stringResource(R.string.ui_announcement_until, end.format(DateTimeFormatter.ofPattern("d MMMM HH:mm", LocalConfiguration.current.locales[0]))),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
            }
        }
    }
}
