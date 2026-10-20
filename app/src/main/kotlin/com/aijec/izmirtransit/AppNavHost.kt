package com.aijec.izmirtransit

import com.aijec.izmirtransit.core.ui.style
import com.aijec.izmirtransit.core.ui.glassBackdrop
import com.aijec.izmirtransit.core.model.UiStyle
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Place
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.aijec.izmirtransit.core.ui.Calm
import com.aijec.izmirtransit.core.ui.FloatingTab
import com.aijec.izmirtransit.core.ui.FloatingTabBar
import com.aijec.izmirtransit.feature.home.HomeRoute
import com.aijec.izmirtransit.feature.linemap.LineMapRoute
import com.aijec.izmirtransit.core.map.PlanDestination
import com.aijec.izmirtransit.feature.linemap.LineMapViewModel
import com.aijec.izmirtransit.feature.planner.PlannerRoute
import com.aijec.izmirtransit.feature.planner.PlannerViewModel
import com.aijec.izmirtransit.feature.settings.SettingsRoute
import com.aijec.izmirtransit.feature.stop.StopRoute
import com.aijec.izmirtransit.feature.stop.StopViewModel
import com.aijec.izmirtransit.feature.timetable.TimetableRoute
import com.aijec.izmirtransit.feature.timetable.TimetableViewModel

private const val HOME_ROUTE = "home"
private const val MAP_ROUTE = "map"
private const val SETTINGS_ROUTE = "settings"
private const val LINE_ROUTE = "line/{${LineMapViewModel.KEY_LINE}}"
private const val STOP_ROUTE = "stop/{${StopViewModel.STOP_CODE_ARG}}"
private const val TIMETABLE_ROUTE = "timetable/{${TimetableViewModel.LINE_CODE_ARG}}"

private const val PLAN_ROUTE =
    "plan?${PlannerViewModel.ARG_LAT}={${PlannerViewModel.ARG_LAT}}&${PlannerViewModel.ARG_LON}={${PlannerViewModel.ARG_LON}}&${PlannerViewModel.ARG_NAME}={${PlannerViewModel.ARG_NAME}}"

private fun planDestination(to: PlanDestination?) =
    if (to == null) "plan" else "plan?lat=${to.location.latitude}&lon=${to.location.longitude}&name=${Uri.encode(to.name.orEmpty())}"

private fun lineDestination(code: String) = "line/${Uri.encode(code)}"
private fun stopDestination(code: String) = "stop/${Uri.encode(code)}"
private fun timetableDestination(code: String) = "timetable/${Uri.encode(code)}"

/**
 * Two tabs: Home (start) and Map (the all-lines overview, or the line chosen there).
 * Lines opened from Home or a stop, stops and timetables stack on top, so Back retraces.
 * The tab bar shows on Home, the Map tab and line maps; Home always returns to Home and
 * Map always returns to the Map tab, whatever is stacked on top.
 */
@Composable
fun AppNavHost(openStop: String? = null, onOpenStopHandled: () -> Unit = {}) {
    val nav = rememberNavController()
    // Deep link from an arrival alert notification.
    LaunchedEffect(openStop) {
        if (openStop != null) {
            nav.navigate(stopDestination(openStop))
            onOpenStopHandled()
        }
    }
    val entry by nav.currentBackStackEntryAsState()
    val currentRoute = entry?.destination?.route
    val showTabs = currentRoute == HOME_ROUTE || currentRoute == MAP_ROUTE || currentRoute == LINE_ROUTE
    // A line map belongs to whichever tab it was opened from.
    // Re-read whenever the current entry changes; getBackStackEntry throws when absent.
    val onMapTab = entry.let { runCatching { nav.getBackStackEntry(MAP_ROUTE) }.isSuccess }

    val glass = Calm.style == UiStyle.LIQUID_GLASS
    Box(Modifier.fillMaxSize().then(if (glass) Modifier.glassBackdrop() else Modifier.background(Calm.colors.canvas))) {
        NavHost(navController = nav, startDestination = HOME_ROUTE, modifier = Modifier.fillMaxSize()) {
            composable(HOME_ROUTE) {
                HomeRoute(
                    onOpenStop = { nav.navigate(stopDestination(it)) },
                    onOpenLine = { nav.navigate(lineDestination(it)) },
                    onOpenSettings = { nav.navigate(SETTINGS_ROUTE) },
                    onPlanTrip = { nav.navigate(planDestination(null)) },
                )
            }
            composable(MAP_ROUTE) {
                LineMapRoute(
                    onOpenStop = { nav.navigate(stopDestination(it)) },
                    onOpenTimetable = { nav.navigate(timetableDestination(it)) },
                    onPlanTrip = { nav.navigate(planDestination(it)) },
                )
            }
            composable(
                LINE_ROUTE,
                arguments = listOf(navArgument(LineMapViewModel.KEY_LINE) { type = NavType.StringType }),
            ) {
                LineMapRoute(
                    onOpenStop = { nav.navigate(stopDestination(it)) },
                    onOpenTimetable = { nav.navigate(timetableDestination(it)) },
                    onBack = { nav.popBackStack() },
                    onPlanTrip = { nav.navigate(planDestination(it)) },
                )
            }
            composable(
                PLAN_ROUTE,
                arguments = listOf(PlannerViewModel.ARG_LAT, PlannerViewModel.ARG_LON, PlannerViewModel.ARG_NAME).map { name ->
                    navArgument(name) {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    }
                },
            ) {
                PlannerRoute(onBack = { nav.popBackStack() }, onOpenStop = { nav.navigate(stopDestination(it)) })
            }
            composable(
                STOP_ROUTE,
                arguments = listOf(navArgument(StopViewModel.STOP_CODE_ARG) { type = NavType.StringType }),
            ) {
                StopRoute(
                    onBack = { nav.popBackStack() },
                    onOpenLine = { nav.navigate(lineDestination(it)) },
                    onOpenStop = { nav.navigate(stopDestination(it)) },
                )
            }
            composable(SETTINGS_ROUTE) {
                SettingsRoute(onBack = { nav.popBackStack() })
            }
            composable(
                TIMETABLE_ROUTE,
                arguments = listOf(navArgument(TimetableViewModel.LINE_CODE_ARG) { type = NavType.StringType }),
            ) {
                TimetableRoute(onBack = { nav.popBackStack() })
            }
        }

        AnimatedVisibility(
            visible = showTabs,
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut() + slideOutVertically { it / 2 },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(bottom = 12.dp),
        ) {
            FloatingTabBar(
                listOf(
                    FloatingTab(stringResource(R.string.tab_home), Icons.Filled.Home, !onMapTab) {
                        if (currentRoute != HOME_ROUTE) nav.popBackStack(HOME_ROUTE, inclusive = false)
                    },
                    FloatingTab(stringResource(R.string.tab_map), Icons.Filled.Place, onMapTab) {
                        if (currentRoute != MAP_ROUTE) nav.openMapTab()
                    },
                ),
            )
        }
    }
}

/** Back to the Map tab itself: drop anything stacked on top, keeping one copy. */
private fun NavHostController.openMapTab() {
    // Only called when the map isn't on top, so a successful pop means it was in the stack.
    if (!popBackStack(MAP_ROUTE, inclusive = false)) {
        popBackStack(HOME_ROUTE, inclusive = false)
        navigate(MAP_ROUTE) { launchSingleTop = true }
    }
}
