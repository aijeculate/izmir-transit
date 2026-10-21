package com.aijec.izmirtransit.feature.settings

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.aijec.izmirtransit.core.data.repository.AppSettings
import com.aijec.izmirtransit.core.data.update.AppUpdate
import com.aijec.izmirtransit.core.data.update.UpdateCheck
import com.aijec.izmirtransit.core.data.update.UpdateChecker
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextAlign
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.aijec.izmirtransit.core.data.repository.SettingsRepository
import com.aijec.izmirtransit.core.model.AccentTheme
import com.aijec.izmirtransit.core.model.ThemeMode
import com.aijec.izmirtransit.core.model.UiStyle
import com.aijec.izmirtransit.core.ui.Calm
import com.aijec.izmirtransit.core.ui.Hairline
import com.aijec.izmirtransit.core.ui.Panel
import com.aijec.izmirtransit.core.ui.ScreenHeader
import com.aijec.izmirtransit.core.ui.SectionLabel
import com.aijec.izmirtransit.core.ui.SegmentedControl
import com.aijec.izmirtransit.core.ui.accentSwatch
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: SettingsRepository,
    private val updates: UpdateChecker,
) : ViewModel() {
    val appVersion: String get() = updates.currentVersion

    private val _updateStatus = MutableStateFlow<UpdateStatus>(updates.available.value?.let(UpdateStatus::Available) ?: UpdateStatus.Idle)
    val updateStatus: StateFlow<UpdateStatus> = _updateStatus.asStateFlow()

    fun checkForUpdates() {
        if (_updateStatus.value == UpdateStatus.Checking) return
        _updateStatus.value = UpdateStatus.Checking
        viewModelScope.launch {
            _updateStatus.value = when (val result = updates.check()) {
                UpdateCheck.UpToDate -> UpdateStatus.UpToDate
                is UpdateCheck.Available -> UpdateStatus.Available(result.update)
                UpdateCheck.Failed -> UpdateStatus.Failed
            }
        }
    }

    fun setAutoCheckUpdates(enabled: Boolean) = viewModelScope.launch { repository.setAutoCheckUpdates(enabled) }

    val settings: StateFlow<AppSettings> = repository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    fun setThemeMode(mode: ThemeMode) = viewModelScope.launch { repository.setThemeMode(mode) }

    fun setAccent(accent: AccentTheme) = viewModelScope.launch { repository.setAccent(accent) }

    fun setSearchAtBottom(bottom: Boolean) = viewModelScope.launch { repository.setSearchAtBottom(bottom) }

    fun setStyle(style: UiStyle) = viewModelScope.launch { repository.setStyle(style) }

    fun setShowBothDirections(both: Boolean) = viewModelScope.launch { repository.setShowBothDirections(both) }

    /** Shows the first-run tutorial again (the app switches to it at once). */
    fun replayTutorial() = viewModelScope.launch { repository.setOnboardingDone(false) }
}

@Composable
fun SettingsRoute(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val updateStatus by viewModel.updateStatus.collectAsStateWithLifecycle()
    SettingsScreen(
        settings = settings,
        onBack = onBack,
        onThemeMode = { viewModel.setThemeMode(it) },
        onAccent = { viewModel.setAccent(it) },
        onSearchAtBottom = { viewModel.setSearchAtBottom(it) },
        onStyle = { viewModel.setStyle(it) },
        onShowBothDirections = { viewModel.setShowBothDirections(it) },
        onReplayTutorial = { viewModel.replayTutorial() },
        appVersion = viewModel.appVersion,
        updateStatus = updateStatus,
        onAutoCheckUpdates = { viewModel.setAutoCheckUpdates(it) },
        onCheckForUpdates = viewModel::checkForUpdates,
    )
}

sealed interface UpdateStatus {
    data object Idle : UpdateStatus
    data object Checking : UpdateStatus
    data object UpToDate : UpdateStatus
    data class Available(val update: AppUpdate) : UpdateStatus
    data object Failed : UpdateStatus
}

@Composable
fun SettingsScreen(
    settings: AppSettings,
    onBack: () -> Unit,
    onThemeMode: (ThemeMode) -> Unit,
    onAccent: (AccentTheme) -> Unit,
    onSearchAtBottom: (Boolean) -> Unit,
    onStyle: (UiStyle) -> Unit = {},
    onShowBothDirections: (Boolean) -> Unit = {},
    onReplayTutorial: () -> Unit = {},
    appVersion: String = "",
    updateStatus: UpdateStatus = UpdateStatus.Idle,
    onAutoCheckUpdates: (Boolean) -> Unit = {},
    onCheckForUpdates: () -> Unit = {},
) {
    val colors = Calm.colors
    val uriHandler = LocalUriHandler.current
    LazyColumn(
        Modifier
            .fillMaxSize()
            .background(colors.canvas)
            .windowInsetsPadding(WindowInsets.statusBars),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            bottom = 24.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { ScreenHeader(stringResource(R.string.settings_title), onBack = onBack, horizontalPadding = 0.dp) }

        item { SectionLabel(stringResource(R.string.settings_appearance)) }
        item {
            SegmentedControl(
                options = ThemeMode.entries,
                selected = settings.themeMode,
                onSelect = onThemeMode,
                label = { mode ->
                    stringResource(
                        when (mode) {
                            ThemeMode.SYSTEM -> R.string.settings_theme_system
                            ThemeMode.LIGHT -> R.string.settings_theme_light
                            ThemeMode.DARK -> R.string.settings_theme_dark
                            ThemeMode.AMOLED -> R.string.settings_theme_amoled
                        },
                    )
                },
            )
        }

        item { SectionLabel(stringResource(R.string.settings_language)) }
        item { LanguageControl() }

        item { SectionLabel(stringResource(R.string.settings_style)) }
        item {
            SegmentedControl(
                options = UiStyle.entries,
                selected = settings.style,
                onSelect = onStyle,
                label = { style ->
                    stringResource(if (style == UiStyle.CALM) R.string.settings_style_calm else R.string.settings_style_glass)
                },
            )
        }

        item { SectionLabel(stringResource(R.string.settings_colour)) }
        item {
            Panel(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    AccentTheme.entries.forEach { accent ->
                        AccentSwatch(accent, selected = accent == settings.accent, onClick = { onAccent(accent) })
                    }
                }
            }
        }

        item { SectionLabel(stringResource(R.string.settings_map)) }
        item {
            Panel(Modifier.fillMaxWidth(), padding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
                SettingSwitch(
                    title = stringResource(R.string.settings_both_directions),
                    hint = stringResource(R.string.settings_both_directions_hint),
                    checked = settings.showBothDirections,
                    onChange = onShowBothDirections,
                )
                Hairline(inset = 0.dp)
                SettingSwitch(
                    title = stringResource(R.string.settings_search_bottom),
                    hint = stringResource(R.string.settings_search_bottom_hint),
                    checked = settings.searchAtBottom,
                    onChange = onSearchAtBottom,
                )
            }
        }

        item { SectionLabel(stringResource(R.string.settings_updates)) }
        item {
            Panel(Modifier.fillMaxWidth(), padding = PaddingValues(0.dp)) {
                Box(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    SettingSwitch(
                        title = stringResource(R.string.settings_updates_auto),
                        hint = stringResource(R.string.settings_updates_auto_hint),
                        checked = settings.autoCheckUpdates,
                        onChange = onAutoCheckUpdates,
                    )
                }
                Hairline()
                val available = (updateStatus as? UpdateStatus.Available)?.update
                LinkRow(
                    title = if (available != null) {
                        stringResource(R.string.settings_updates_download, available.version)
                    } else {
                        stringResource(R.string.settings_updates_check)
                    },
                    body = when (updateStatus) {
                        UpdateStatus.Idle -> stringResource(R.string.settings_updates_current, appVersion)
                        UpdateStatus.Checking -> stringResource(R.string.settings_updates_checking)
                        UpdateStatus.UpToDate -> stringResource(R.string.settings_updates_up_to_date, appVersion)
                        is UpdateStatus.Available -> stringResource(R.string.settings_updates_available, appVersion)
                        UpdateStatus.Failed -> stringResource(R.string.settings_updates_failed)
                    },
                    onClick = { if (available != null) uriHandler.openUri(available.downloadUrl) else onCheckForUpdates() },
                )
            }
        }

        item { SectionLabel(stringResource(R.string.settings_about)) }
        item {
            Panel(Modifier.fillMaxWidth(), padding = PaddingValues(vertical = 4.dp)) {
                AboutRow(stringResource(R.string.settings_about_data_title), stringResource(R.string.settings_about_data))
                Hairline()
                AboutRow(stringResource(R.string.settings_about_map_title), stringResource(R.string.settings_about_map))
                Hairline()
                AboutRow(stringResource(R.string.settings_about_font_title), stringResource(R.string.settings_about_font))
                Hairline()
                AboutRow(stringResource(R.string.settings_about_source_title), stringResource(R.string.settings_about_source))
            }
        }
        item {
            Panel(Modifier.fillMaxWidth(), padding = PaddingValues(0.dp)) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Button, onClick = onReplayTutorial)
                        .padding(horizontal = 16.dp, vertical = 16.dp),
                ) {
                    Text(stringResource(R.string.settings_replay_tutorial), style = MaterialTheme.typography.bodyLarge, color = colors.accent)
                }
            }
        }
        item {
            Panel(Modifier.fillMaxWidth(), padding = PaddingValues(0.dp)) {
                LinkRow(
                    title = stringResource(R.string.settings_repo),
                    body = "github.com/aijeculate/izmir-transit",
                    onClick = { uriHandler.openUri(UpdateChecker.REPOSITORY_URL) },
                )
            }
        }
        item {
            Text(
                stringResource(R.string.settings_version, appVersion),
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun AccentSwatch(accent: AccentTheme, selected: Boolean, onClick: () -> Unit) {
    val colors = Calm.colors
    val name = stringResource(
        when (accent) {
            AccentTheme.OCEAN -> R.string.settings_accent_ocean
            AccentTheme.TEAL -> R.string.settings_accent_teal
            AccentTheme.VIOLET -> R.string.settings_accent_violet
            AccentTheme.CORAL -> R.string.settings_accent_coral
            AccentTheme.GRAPHITE -> R.string.settings_accent_graphite
        },
    )
    Column(
        Modifier
            .clip(Calm.CellShape)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics { this.selected = selected }
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(accentSwatch(accent))
                // A hairline keeps dark swatches (Graphite) visible on the dark panel.
                .border(if (selected) 3.dp else 1.dp, if (selected) colors.panel else colors.hairline, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Icon(Icons.Filled.Check, contentDescription = null, tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(name, style = MaterialTheme.typography.labelSmall, color = if (selected) colors.text else colors.textSecondary)
    }
}

private enum class AppLanguage(val tag: String) { SYSTEM(""), TURKISH("tr"), ENGLISH("en") }

/**
 * The app's own language, separate from the phone's. Android stores the choice (and on 13+
 * shows it in the system's per-app language settings) and recreates the screen in it.
 */
@Composable
private fun LanguageControl() {
    val current = AppCompatDelegate.getApplicationLocales().toLanguageTags().substringBefore('-').substringBefore(',')
    val selected = AppLanguage.entries.firstOrNull { it.tag == current && it != AppLanguage.SYSTEM } ?: AppLanguage.SYSTEM
    SegmentedControl(
        options = AppLanguage.entries,
        selected = selected,
        onSelect = { language ->
            AppCompatDelegate.setApplicationLocales(
                if (language == AppLanguage.SYSTEM) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(language.tag),
            )
        },
        // Each language named in itself, so it's findable whichever one is showing.
        label = { language ->
            when (language) {
                AppLanguage.SYSTEM -> stringResource(R.string.settings_theme_system)
                AppLanguage.TURKISH -> "Türkçe"
                AppLanguage.ENGLISH -> "English"
            }
        },
    )
}

@Composable
private fun SettingSwitch(title: String, hint: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val colors = Calm.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Switch) { onChange(!checked) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = colors.text)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = colors.accent,
                checkedThumbColor = colors.onAccent,
                uncheckedTrackColor = colors.hairline,
                uncheckedBorderColor = colors.hairline,
                uncheckedThumbColor = colors.textSecondary,
            ),
        )
    }
}

/** A tappable row: an accent title over a secondary line. */
@Composable
private fun LinkRow(title: String, body: String, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge, color = Calm.colors.accent)
        Text(body, style = MaterialTheme.typography.bodySmall, color = Calm.colors.textSecondary)
    }
}

@Composable
private fun AboutRow(title: String, body: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = Calm.colors.text)
        Text(body, style = MaterialTheme.typography.bodySmall, color = Calm.colors.textSecondary)
    }
}
