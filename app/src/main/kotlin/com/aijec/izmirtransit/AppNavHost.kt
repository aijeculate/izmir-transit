package com.aijec.izmirtransit

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.aijec.izmirtransit.feature.linemap.LineMapRoute
import com.aijec.izmirtransit.feature.linemap.LineMapViewModel
import com.aijec.izmirtransit.feature.stop.StopRoute
import com.aijec.izmirtransit.feature.stop.StopViewModel

private const val LINE_ROUTE = "line?${LineMapViewModel.KEY_LINE}={${LineMapViewModel.KEY_LINE}}"
private const val STOP_ROUTE = "stop/{${StopViewModel.STOP_CODE_ARG}}"

private fun lineDestination(code: String) = "line?${LineMapViewModel.KEY_LINE}=${Uri.encode(code)}"
private fun stopDestination(code: String) = "stop/${Uri.encode(code)}"

/** Line map is home; stops and other lines stack on top of it, so Back always retraces. */
@Composable
fun AppNavHost() {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = LINE_ROUTE) {
        composable(
            LINE_ROUTE,
            arguments = listOf(
                navArgument(LineMapViewModel.KEY_LINE) {
                    type = NavType.StringType
                    defaultValue = LineMapViewModel.DEFAULT_LINE
                },
            ),
        ) {
            LineMapRoute(onOpenStop = { nav.navigate(stopDestination(it)) })
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
    }
}
