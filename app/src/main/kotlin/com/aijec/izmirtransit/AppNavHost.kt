package com.aijec.izmirtransit

import android.net.Uri
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
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

private enum class Tab(val route: String, val label: Int, val icon: ImageVector) {
    HOME(HOME_ROUTE, R.string.tab_home, Icons.Filled.Home),
    MAP(LINE_ROUTE, R.string.tab_map, Icons.Filled.Place),
}

/**
 * Home (favourites and nearby stops) is the start screen; the map is the second tab.
 * Stops and lines opened from either stack on top, so Back always retraces.
 */
@Composable
fun AppNavHost() {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val currentRoute = entry?.destination?.route

    Scaffold(
        // Screens handle the status bar themselves (the map draws under it).
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = currentRoute == tab.route,
                        // Re-tapping the current tab does nothing rather than rebuilding it.
                        onClick = { if (currentRoute != tab.route) nav.switchTab(tab) },
                        icon = { Icon(tab.icon, contentDescription = null) },
                        label = { Text(stringResource(tab.label)) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = HOME_ROUTE,
            modifier = Modifier.padding(padding).consumeWindowInsets(padding),
        ) {
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
    }
}

/** Standard bottom-bar behaviour: one copy of each tab, state kept when switching. */
private fun NavHostController.switchTab(tab: Tab) {
    val destination = if (tab == Tab.MAP) "line" else tab.route
    navigate(destination) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
