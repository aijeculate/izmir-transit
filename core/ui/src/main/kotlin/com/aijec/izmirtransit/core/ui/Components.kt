package com.aijec.izmirtransit.core.ui

import com.aijec.izmirtransit.core.model.UiStyle
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.luminance
import com.aijec.izmirtransit.core.model.Line
import com.aijec.izmirtransit.core.model.TransitMode
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.layout.offset
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import com.aijec.izmirtransit.core.model.Announcement
import com.aijec.izmirtransit.core.model.ConnectionType
import com.aijec.izmirtransit.core.model.LiveError
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeFormatter

// The "calm glass" building blocks. Screens compose these instead of stock Material
// components, so the look stays consistent and doesn't read as default Android.

/** A white rounded panel. [glass] makes it slightly see-through for use over the map. */
@Composable
fun Panel(
    modifier: Modifier = Modifier,
    glass: Boolean = false,
    padding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = Calm.colors
    Column(
        modifier
            .calmSurface(Calm.PanelShape, overMap = glass)
            .padding(padding),
        content = content,
    )
}

/** A full-width tappable row with a soft press state, at least 48 dp tall. */
@Composable
fun CalmRow(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    padding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(padding),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** A thin divider inset to align with row text. */
@Composable
fun Hairline(modifier: Modifier = Modifier, inset: Dp = 16.dp) {
    Box(modifier.fillMaxWidth().padding(start = inset).height(0.5.dp).background(Calm.colors.hairline))
}

/** Small grey label above a group, iOS-style. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, trailing: @Composable (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().heightIn(min = 40.dp).padding(start = 4.dp, end = 4.dp, top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = Calm.colors.textSecondary, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

/** Line number as a soft pill. [strong] fills it with the accent (for "this line"). */
@Composable
fun LineBadge(code: String, modifier: Modifier = Modifier, strong: Boolean = false, mode: TransitMode? = null) {
    val colors = Calm.colors
    // With a mode, the operator's colours: strong is its main colour with the second as the
    // rim (Metro blue ringed red); soft is a tint with the colour as text.
    val brand = mode?.let(ModeBrands::of)
    val shape = Calm.PillShape
    val background = when {
        brand != null && strong -> brand.primary
        brand != null -> brand.primary.copy(alpha = if (colors.isDark) 0.22f else 0.12f)
        strong -> colors.accent
        else -> colors.accentSoft
    }
    val content = when {
        brand != null && strong -> brand.onPrimary
        brand != null -> brand.ink()
        strong -> colors.onAccent
        else -> colors.onAccentSoft
    }
    Box(
        modifier
            .clip(shape)
            .background(background)
            .then(if (brand != null && strong && brand.secondary != Color.White) Modifier.border(1.5.dp, brand.secondary, shape) else Modifier)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            code,
            style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = TabularNumbers),
            color = content,
            maxLines = 1,
        )
    }
}

/** Badge for a known line: its display code ("M1", "Konak", "Vapur") in its operator's colours. */
@Composable
fun LineBadge(line: Line, modifier: Modifier = Modifier, strong: Boolean = false) =
    LineBadge(line.displayCode, modifier, strong, line.mode)

/** Round icon button on a panel-coloured disc; 48 dp touch target. */
@Composable
fun CircleIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Calm.colors.text,
    glass: Boolean = false,
    /** No background: for icon buttons that sit inside a panel. */
    plain: Boolean = false,
) {
    val colors = Calm.colors
    Box(
        modifier
            .size(48.dp)
            .then(if (plain) Modifier.clip(CircleShape) else Modifier.calmSurface(CircleShape, overMap = glass))
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(22.dp))
    }
}

@Composable
fun FavouriteButton(isFavourite: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier, glass: Boolean = false, plain: Boolean = false) {
    CircleIconButton(
        icon = if (isFavourite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
        contentDescription = stringResource(if (isFavourite) R.string.ui_favourite_remove else R.string.ui_favourite_add),
        onClick = onToggle,
        tint = if (isFavourite) Calm.colors.heart else Calm.colors.textSecondary,
        modifier = modifier,
        glass = glass,
        plain = plain,
    )
}

/** Header for pushed screens: round back button, large title, optional subtitle and action. */
@Composable
fun ScreenHeader(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leading: @Composable (() -> Unit)? = null,
    action: @Composable (() -> Unit)? = null,
    horizontalPadding: Dp = 16.dp,
) {
    Column(modifier.fillMaxWidth().padding(horizontal = horizontalPadding, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircleIconButton(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.ui_back), onBack)
            Spacer(Modifier.weight(1f))
            action?.invoke()
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
            leading?.let {
                it()
                Spacer(Modifier.width(10.dp))
            }
            Text(title, style = MaterialTheme.typography.headlineSmall, color = Calm.colors.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        subtitle?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = Calm.colors.textSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 4.dp, top = 2.dp),
            )
        }
    }
}

/** Pill search field with a leading magnifier and a clear button. */
@Composable
fun PillSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    glass: Boolean = false,
) {
    val colors = Calm.colors
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .calmSurface(Calm.PillShape, overMap = glass)
            .padding(start = 18.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Search, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) {
                Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = colors.textSecondary, maxLines = 1)
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.text),
                cursorBrush = SolidColor(colors.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (value.isNotEmpty()) {
            Box(
                Modifier.size(40.dp).clip(CircleShape).clickable(role = Role.Button) { onValueChange("") },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.ui_clear), tint = colors.textSecondary, modifier = Modifier.size(18.dp))
            }
        } else {
            Spacer(Modifier.width(12.dp))
        }
    }
}

/** iOS-style segmented control: a soft track with the selected option on a white pill. */
@Composable
fun <T> SegmentedControl(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: @Composable (T) -> String,
    modifier: Modifier = Modifier,
) {
    val colors = Calm.colors
    Row(
        modifier
            .fillMaxWidth()
            .then(if (Calm.style == UiStyle.LIQUID_GLASS) Modifier.calmSurface(Calm.PillShape) else Modifier.clip(Calm.PillShape).background(colors.hairline))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = 40.dp)
                    .clip(Calm.PillShape)
                    .background(if (isSelected) Calm.selectedFill() else Color.Transparent)
                    .clickable(role = Role.Tab) { onSelect(option) }
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label(option),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isSelected) colors.text else colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** ETA like "6 dk": big tabular number, small unit. */
@Composable
fun EtaText(minutes: Int, modifier: Modifier = Modifier, uncertain: Boolean = false) {
    val colors = Calm.colors
    val color = if (uncertain) colors.textSecondary else colors.accent
    if (minutes <= 0) {
        Text(stringResource(R.string.ui_eta_now), style = MaterialTheme.typography.titleSmall, color = color, modifier = modifier)
        return
    }
    Row(verticalAlignment = Alignment.Bottom, modifier = modifier) {
        // Live minutes roll as they count down.
        RollingText((if (uncertain) "~" else "") + minutes) { text ->
            Text(
                text,
                style = MaterialTheme.typography.titleLarge.copy(fontFeatureSettings = TabularNumbers),
                color = color,
            )
        }
        Spacer(Modifier.width(3.dp))
        Text(stringResource(R.string.ui_eta_unit), style = MaterialTheme.typography.labelMedium, color = color, modifier = Modifier.padding(bottom = 3.dp))
    }
}

/** A route change or disruption notice: soft amber panel. */
/**
 * Today is a public holiday or a bayram eve: timetables show the Sunday plan, or the usual
 * one on an eve, and the operators may run a special one.
 */
@Composable
fun HolidayNotice(name: String, eve: Boolean, modifier: Modifier = Modifier) {
    val text = stringResource(if (eve) R.string.ui_holiday_eve else R.string.ui_holiday, name)
    AnnouncementCard(Announcement(lineCode = "", title = text, startsAt = null, endsAt = null), showLine = false, modifier = modifier)
}

@Composable
fun AnnouncementCard(announcement: Announcement, showLine: Boolean, modifier: Modifier = Modifier) {
    val colors = Calm.colors
    Row(
        modifier
            .fillMaxWidth()
            .clip(Calm.PanelShape)
            .background(colors.warningSoft)
            .padding(14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(Icons.Filled.Warning, contentDescription = stringResource(R.string.ui_announcement), tint = colors.onWarningSoft, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            if (showLine) {
                Text(stringResource(R.string.ui_announcement_line, announcement.lineCode), style = MaterialTheme.typography.labelMedium, color = colors.onWarningSoft)
            }
            Text(announcement.title, style = MaterialTheme.typography.bodyMedium, color = colors.onWarningSoft)
            announcement.endsAt?.let { end ->
                Text(
                    stringResource(R.string.ui_announcement_until, end.format(DateTimeFormatter.ofPattern("d MMMM HH:mm", LocalConfiguration.current.locales[0]))),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onWarningSoft.copy(alpha = 0.8f),
                )
            }
        }
    }
}

/** "Metro", "İZBAN", … as quiet outlined pills. */
@Composable
fun ConnectionChips(types: List<ConnectionType>, modifier: Modifier = Modifier) {
    if (types.isEmpty()) return
    val colors = Calm.colors
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.ui_connects_to), style = MaterialTheme.typography.labelSmall, color = colors.textSecondary)
        types.forEach { type ->
            Box(
                Modifier
                    .clip(Calm.PillShape)
                    .border(1.dp, colors.hairline, Calm.PillShape)
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            ) {
                Text(
                    stringResource(
                        when (type) {
                            ConnectionType.METRO -> R.string.ui_connection_metro
                            ConnectionType.IZBAN -> R.string.ui_connection_izban
                            ConnectionType.FERRY -> R.string.ui_connection_ferry
                            ConnectionType.AIRPORT -> R.string.ui_connection_airport
                            ConnectionType.BUS_TERMINAL -> R.string.ui_connection_terminal
                        },
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.text,
                )
            }
        }
    }
}

/** A small coloured dot, e.g. for direction legends. */
@Composable
fun Dot(color: Color, modifier: Modifier = Modifier, size: Dp = 10.dp) {
    Box(modifier.size(size).clip(CircleShape).background(color))
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

/** For text styles that need tabular digits. */
fun TextStyle.tabular(): TextStyle = copy(fontFeatureSettings = TabularNumbers)

/** The one primary action on a panel: a filled accent pill. */
@Composable
fun PillButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, secondary: Boolean = false) {
    val colors = Calm.colors
    Box(
        modifier
            .heightIn(min = 44.dp)
            .clip(Calm.PillShape)
            .background(if (secondary) colors.accentSoft else colors.accent)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = if (secondary) colors.onAccentSoft else colors.onAccent, maxLines = 1)
    }
}

/** A quiet text action in the accent colour, e.g. "Timetable". */
@Composable
fun TextAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .heightIn(min = 40.dp)
            .clip(Calm.PillShape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = Calm.colors.accent)
    }
}

/**
 * Space the floating bottom bar takes above the system navigation bar. Screens shown with
 * the bar add this (plus the navigation bar inset) to their bottom content padding.
 */
val FloatingBarClearance: Dp = 84.dp

/** One destination in [FloatingTabBar]. */
data class FloatingTab(val label: String, val icon: ImageVector, val selected: Boolean, val onClick: () -> Unit)

/**
 * A glass pill holding the main destinations, floating above content and the map. The
 * selected tab's highlight slides to the tapped tab on a spring; its leading edge moves
 * faster than its trailing one, so it stretches on the way like a drop of liquid.
 */
@Composable
fun FloatingTabBar(tabs: List<FloatingTab>, modifier: Modifier = Modifier) {
    val colors = Calm.colors
    val glass = Calm.style == UiStyle.LIQUID_GLASS
    val pillColor = if (glass) Calm.selectedFill() else colors.accentSoft
    val reduced = rememberReducedMotion()
    // Each tab's left and right edge inside the bar, in px, once laid out.
    val bounds = remember { mutableStateMapOf<Int, Pair<Float, Float>>() }
    val selected = tabs.indexOfFirst { it.selected }
    val left = remember { Animatable(0f) }
    val right = remember { Animatable(0f) }
    var placed by remember { mutableStateOf(false) }
    val target = bounds[selected]
    LaunchedEffect(target) {
        val (l, r) = target ?: return@LaunchedEffect
        if (!placed || reduced) {
            left.snapTo(l)
            right.snapTo(r)
            placed = true
            return@LaunchedEffect
        }
        val movingRight = l > left.value
        val fast = spring<Float>(dampingRatio = 0.7f, stiffness = 700f)
        val slow = spring<Float>(dampingRatio = 0.75f, stiffness = 260f)
        launch { left.animateTo(l, if (movingRight) slow else fast) }
        launch { right.animateTo(r, if (movingRight) fast else slow) }
    }
    Box(
        modifier
            .calmSurface(Calm.PillShape, overMap = true)
            .padding(6.dp),
    ) {
        if (placed && selected >= 0) {
            val density = LocalDensity.current
            Box(
                Modifier
                    .offset { IntOffset(left.value.roundToInt(), 0) }
                    .width(with(density) { (right.value - left.value).coerceAtLeast(0f).toDp() })
                    .height(48.dp)
                    .clip(Calm.PillShape)
                    .background(pillColor),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            tabs.forEachIndexed { index, tab ->
                val tint by animateColorAsState(if (tab.selected) colors.onAccentSoft else colors.textSecondary, tween(300), label = "tab")
                Row(
                    Modifier
                        .onGloballyPositioned { bounds[index] = it.positionInParent().x to it.positionInParent().x + it.size.width }
                        .heightIn(min = 48.dp)
                        .clip(Calm.PillShape)
                        .clickable(role = Role.Tab, onClick = tab.onClick)
                        .padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(tab.icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(tab.label, style = MaterialTheme.typography.labelLarge, color = tint)
                }
            }
        }
    }
}
