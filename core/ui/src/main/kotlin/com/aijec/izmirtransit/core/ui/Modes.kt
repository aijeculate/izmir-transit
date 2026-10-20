package com.aijec.izmirtransit.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.aijec.izmirtransit.core.model.Stop
import com.aijec.izmirtransit.core.model.TransitMode

/** "Metro", "Tramvay", "Vapur", … */
@Composable
fun modeLabel(mode: TransitMode): String = stringResource(
    when (mode) {
        TransitMode.BUS -> R.string.ui_mode_bus
        TransitMode.METRO -> R.string.ui_mode_metro
        TransitMode.TRAM -> R.string.ui_mode_tram
        TransitMode.IZBAN -> R.string.ui_mode_izban
        TransitMode.FERRY -> R.string.ui_mode_ferry
    },
)

/** What kind of place a stop is: "Otobüs durağı 10007", "Metro istasyonu", "İskele". */
@Composable
fun stopKindLabel(stop: Stop): String = when (stop.mode) {
    TransitMode.BUS -> stringResource(R.string.ui_stop_bus, stop.code)
    TransitMode.METRO -> stringResource(R.string.ui_stop_metro)
    TransitMode.TRAM -> stringResource(R.string.ui_stop_tram)
    TransitMode.IZBAN -> stringResource(R.string.ui_stop_izban)
    TransitMode.FERRY -> stringResource(R.string.ui_stop_ferry)
}
