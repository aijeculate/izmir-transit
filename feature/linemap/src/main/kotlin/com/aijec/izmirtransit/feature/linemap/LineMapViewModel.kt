package com.aijec.izmirtransit.feature.linemap

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aijec.izmirtransit.core.data.repository.LineRepository
import com.aijec.izmirtransit.core.data.repository.LiveBusRepository
import com.aijec.izmirtransit.core.data.repository.StopRepository
import com.aijec.izmirtransit.core.model.Line
import com.aijec.izmirtransit.core.model.LineRoute
import com.aijec.izmirtransit.core.model.LiveBus
import com.aijec.izmirtransit.core.model.LiveSnapshot
import com.aijec.izmirtransit.core.model.LiveStatus
import com.aijec.izmirtransit.core.model.Stop
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Many stops share a name ("Bahribaba" has six), so results carry their lines to tell them apart. */
data class StopResult(val stop: Stop, val lineCodes: List<String>)

data class LineMapUiState(
    val lineCode: String,
    val line: Line? = null,
    val routes: List<LineRoute> = emptyList(),
    val loadingRoute: Boolean = true,
    val query: String = "",
    val lineResults: List<Line> = emptyList(),
    val stopResults: List<StopResult> = emptyList(),
) {
    val hasResults: Boolean get() = lineResults.isNotEmpty() || stopResults.isNotEmpty()
}

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class LineMapViewModel @Inject constructor(
    private val lines: LineRepository,
    private val stops: StopRepository,
    live: LiveBusRepository,
    private val savedState: SavedStateHandle,
) : ViewModel() {
    private val lineCode = savedState.getStateFlow(KEY_LINE, DEFAULT_LINE)
    private val query = MutableStateFlow("")

    private val _state = MutableStateFlow(LineMapUiState(lineCode.value))
    val state: StateFlow<LineMapUiState> = _state.asStateFlow()

    /**
     * Live positions for the selected line. WhileSubscribed means polling runs only while
     * the screen collects it (collectAsStateWithLifecycle stops in the background) and
     * survives a rotation.
     */
    val liveBuses: StateFlow<LiveSnapshot<List<LiveBus>>> = lineCode
        .flatMapLatest { live.observeLine(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LiveSnapshot(null, LiveStatus.LOADING))

    init {
        viewModelScope.launch {
            lineCode.collectLatest { code ->
                _state.update { it.copy(lineCode = code, loadingRoute = true) }
                val line = lines.line(code)
                val routes = lines.routes(code)
                _state.update { it.copy(line = line, routes = routes, loadingRoute = false) }
            }
        }
        viewModelScope.launch {
            query.debounce(120).collectLatest { text ->
                val lineResults = if (text.isBlank()) emptyList() else lines.search(text, limit = 15)
                val stopResults = if (text.isBlank()) emptyList() else stops.search(text, limit = 15).map { stop ->
                    StopResult(stop, stops.linesAtStop(stop.code).map { it.code })
                }
                _state.update { it.copy(lineResults = lineResults, stopResults = stopResults) }
            }
        }
    }

    fun onQueryChange(text: String) {
        query.value = text
        _state.update {
            if (text.isBlank()) it.copy(query = text, lineResults = emptyList(), stopResults = emptyList()) else it.copy(query = text)
        }
    }

    fun selectLine(code: String) {
        savedState[KEY_LINE] = code
        onQueryChange("")
    }

    companion object {
        /** Navigation argument; also where the selected line survives process death. */
        const val KEY_LINE = "lineCode"

        /** A busy cross-city line, so the first launch shows something moving. */
        const val DEFAULT_LINE = "5"
    }
}
