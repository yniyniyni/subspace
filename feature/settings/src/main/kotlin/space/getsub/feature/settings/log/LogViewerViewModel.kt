// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.settings.log

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import space.getsub.core.data.LogRepository
import javax.inject.Inject

@HiltViewModel
internal class LogViewerViewModel
@Inject
constructor(
    private val logs: LogRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(LogViewerState())
    val state: StateFlow<LogViewerState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true)
            val lines = logs.lines()
            _state.value = LogViewerState(lines = lines, loading = false)
        }
    }

    fun clear() {
        viewModelScope.launch {
            logs.clear()
            _state.value = LogViewerState(lines = emptyList(), loading = false)
        }
    }
}
