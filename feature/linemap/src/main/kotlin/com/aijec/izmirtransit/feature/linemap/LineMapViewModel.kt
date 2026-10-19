package com.aijec.izmirtransit.feature.linemap

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aijec.izmirtransit.core.data.repository.LineRepository
import com.aijec.izmirtransit.core.data.repository.LiveBusRepository
import com.aijec.izmirtransit.core.model.Line
import com.aijec.izmirtransit.core.model.LineRoute
import com.aijec.izmirtransit.core.model.LiveBus
import com.aijec.izmirtransit.core.model.LiveSnapshot
import com.aijec.izmirtransit.core.model.LiveStatus
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

data class LineMapUiState(
    val lineCode: String,
    val line: Line? = null,
    val routes: List<LineRoute> = emptyList(),
    val loadingRoute: Boolean = true,
    val query: String = "",
    val searchResults: List<Line> = emptyList(),
)

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class LineMapViewModel @Inject constructor(
    private val lines: LineRepository,
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
                val results = if (text.isBlank()) emptyList() else lines.search(text, limit = 20)
                _state.update { it.copy(searchResults = results) }
            }
        }
    }

    fun onQueryChange(text: String) {
        query.value = text
        _state.update { it.copy(query = text, searchResults = if (text.isBlank()) emptyList() else it.searchResults) }
    }

    fun selectLine(code: String) {
        savedState[KEY_LINE] = code
        onQueryChange("")
    }

    private companion object {
        const val KEY_LINE = "line"

        /** A busy cross-city line, so the first launch shows something moving. */
        const val DEFAULT_LINE = "5"
    }
}
