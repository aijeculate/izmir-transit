package com.aijec.izmirtransit.onboarding

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.aijec.izmirtransit.R
import com.aijec.izmirtransit.core.model.TransitMode
import com.aijec.izmirtransit.core.ui.Calm
import com.aijec.izmirtransit.core.ui.ModeIcon
import com.aijec.izmirtransit.core.ui.Panel
import com.aijec.izmirtransit.core.ui.PillButton
import com.aijec.izmirtransit.core.ui.TextAction
import com.aijec.izmirtransit.core.ui.calmSurface
import kotlinx.coroutines.launch

private data class Tip(val icon: ImageVector, val text: Int)

private sealed interface Page {
    data object Welcome : Page
    data class Tips(val title: Int, val body: Int, val tips: List<Tip>) : Page
    data object Location : Page
}

private val Pages = listOf(
    Page.Welcome,
    Page.Tips(
        R.string.onboarding_map_title,
        R.string.onboarding_map_body,
        listOf(
            Tip(Icons.Filled.Search, R.string.onboarding_map_tip_search),
            Tip(Icons.Filled.Layers, R.string.onboarding_map_tip_layers),
            Tip(Icons.Filled.MyLocation, R.string.onboarding_map_tip_stops),
        ),
    ),
    Page.Tips(
        R.string.onboarding_line_title,
        R.string.onboarding_line_body,
        listOf(
            Tip(Icons.Filled.SwapHoriz, R.string.onboarding_line_tip_direction),
            Tip(Icons.Filled.Favorite, R.string.onboarding_line_tip_favourite),
        ),
    ),
    Page.Tips(
        R.string.onboarding_plan_title,
        R.string.onboarding_plan_body,
        listOf(
            Tip(Icons.Filled.Directions, R.string.onboarding_plan_tip),
        ),
    ),
    Page.Location,
)

/**
 * A short first-run tour: what the app covers, the map, following a line, the trip planner,
 * and finally the location permission, asked for only after explaining why. Skippable at any
 * point; Settings can show it again.
 */
@Composable
fun Onboarding(onDone: () -> Unit) {
    val pager = rememberPagerState { Pages.size }
    val scope = rememberCoroutineScope()
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { onDone() }
    val last = pager.currentPage == Pages.lastIndex

    Column(
        Modifier
            .fillMaxSize()
            .background(Calm.colors.canvas)
            .windowInsetsPadding(WindowInsets.statusBars)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            if (!last) TextAction(stringResource(R.string.onboarding_skip), onDone)
        }
        HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth()) { index ->
            when (val page = Pages[index]) {
                Page.Welcome -> WelcomePage()
                is Page.Tips -> TipsPage(page)
                Page.Location -> LocationPage()
            }
        }
        Dots(count = Pages.size, current = pager.currentPage, modifier = Modifier.align(Alignment.CenterHorizontally).padding(vertical = 16.dp))
        if (last) {
            PillButton(
                stringResource(R.string.onboarding_allow_location),
                onClick = { permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            PillButton(stringResource(R.string.onboarding_not_now), onClick = onDone, modifier = Modifier.fillMaxWidth(), secondary = true)
        } else {
            PillButton(
                stringResource(R.string.onboarding_next),
                onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun WelcomePage() {
    PageColumn {
        // All five modes, in their operators' colours.
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TransitMode.entries.forEach { ModeIcon(it, size = 52.dp) }
        }
        Spacer(Modifier.height(36.dp))
        Title(stringResource(R.string.onboarding_welcome_title))
        Body(stringResource(R.string.onboarding_welcome_body))
    }
}

@Composable
private fun TipsPage(page: Page.Tips) {
    PageColumn {
        Title(stringResource(page.title))
        Body(stringResource(page.body))
        Spacer(Modifier.height(24.dp))
        Panel(Modifier.fillMaxWidth()) {
            page.tips.forEachIndexed { i, tip ->
                if (i > 0) Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(40.dp).calmSurface(CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(tip.icon, contentDescription = null, tint = Calm.colors.accent, modifier = Modifier.size(22.dp))
                    }
                    Spacer(Modifier.width(14.dp))
                    Text(stringResource(tip.text), style = MaterialTheme.typography.bodyMedium, color = Calm.colors.text)
                }
            }
        }
    }
}

@Composable
private fun LocationPage() {
    PageColumn {
        Box(Modifier.size(88.dp).calmSurface(CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.MyLocation, contentDescription = null, tint = Calm.colors.accent, modifier = Modifier.size(44.dp))
        }
        Spacer(Modifier.height(32.dp))
        Title(stringResource(R.string.onboarding_location_title))
        Body(stringResource(R.string.onboarding_location_body))
    }
}

@Composable
private fun PageColumn(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 8.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) { content() }
}

@Composable
private fun Title(text: String) =
    Text(text, style = MaterialTheme.typography.headlineSmall, color = Calm.colors.text, textAlign = TextAlign.Center)

@Composable
private fun Body(text: String) =
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        color = Calm.colors.textSecondary,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(top = 10.dp),
    )

@Composable
private fun Dots(count: Int, current: Int, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(count) { i ->
            val width by animateDpAsState(if (i == current) 22.dp else 7.dp, label = "dot")
            val color by animateColorAsState(if (i == current) Calm.colors.accent else Calm.colors.hairline, label = "dot")
            Box(Modifier.height(7.dp).width(width).clip(CircleShape).background(color))
        }
    }
}
