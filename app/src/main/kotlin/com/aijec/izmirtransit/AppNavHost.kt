package com.aijec.izmirtransit

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
import androidx.navigation.NavGraph.Companion.findStartDestination
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
import com.aijec.izmirtransit.feature.linemap.LineMapViewModel
import com.aijec.izmirtransit.feature.stop.StopRoute
import com.aijec.izmirtransit.feature.stop.StopViewModel
import com.aijec.izmirtransit.feature.timetable.TimetableRoute
import com.aijec.izmirtransit.feature.timetable.TimetableViewModel

private const val HOME_ROUTE = "home"
private const val LINE_ROUTE = "line?${LineMapViewModel.KEY_LINE}={${LineMapViewModel.KEY_LINE}}"
private const val STOP_ROUTE = "stop/{${StopViewModel.STOP_CODE_ARG}}"
private const val TIMETABLE_ROUTE = "timetable/{${TimetableViewModel.LINE_CODE_ARG}}"

private fun lineDestination(code: String) = "line?${LineMapViewModel.KEY_LINE}=${Uri.encode(code)}"
private fun stopDestination(code: String) = "stop/${Uri.encode(code)}"
private fun timetableDestination(code: String) = "timetable/${Uri.encode(code)}"

private enum class Tab(val route: String) { HOME(HOME_ROUTE), MAP(LINE_ROUTE) }

/**
 * Home (favourites and nearby stops) is the start screen; the map is the second tab.
 * Stops, timetables and other lines stack on top, so Back always retraces. The floating
 * tab bar shows only on the two main screens.
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
    val showTabs = currentRoute == HOME_ROUTE || currentRoute == LINE_ROUTE

    Box(Modifier.fillMaxSize().background(Calm.colors.canvas)) {
        NavHost(navController = nav, startDestination = HOME_ROUTE, modifier = Modifier.fillMaxSize()) {
            composable(HOME_ROUTE) {
                HomeRoute(
                    onOpenStop = { nav.navigate(stopDestination(it)) },
                    onOpenLine = { nav.navigate(lineDestination(it)) },
                )
            }
            composable(
                LINE_ROUTE,
                arguments = listOf(
                    navArgument(LineMapViewModel.KEY_LINE) {
                        type = NavType.StringType
                        defaultValue = LineMapViewModel.DEFAULT_LINE
                    },
                ),
            ) {
                LineMapRoute(
                    onOpenStop = { nav.navigate(stopDestination(it)) },
                    onOpenTimetable = { nav.navigate(timetableDestination(it)) },
                )
            }
            composable(
                STOP_ROUTE,
                arguments = listOf(navArgument(StopViewModel.STOP_CODE_ARG) { type = NavType.StringType }),
            ) {
                StopRoute(
                    onBack = { nav.popBackStack() },
                    onOpenLine = { nav.navigate(lineDestination(it)) },
                )
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
                    FloatingTab(stringResource(R.string.tab_home), Icons.Filled.Home, currentRoute == HOME_ROUTE) {
                        // Re-tapping the current tab does nothing rather than rebuilding it.
                        if (currentRoute != HOME_ROUTE) nav.switchTab(Tab.HOME)
                    },
                    FloatingTab(stringResource(R.string.tab_map), Icons.Filled.Place, currentRoute == LINE_ROUTE) {
                        if (currentRoute != LINE_ROUTE) nav.switchTab(Tab.MAP)
                    },
                ),
            )
        }
    }
}

/** Standard tab behaviour: one copy of each tab, state kept when switching. */
private fun NavHostController.switchTab(tab: Tab) {
    val destination = if (tab == Tab.MAP) "line" else tab.route
    navigate(destination) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
