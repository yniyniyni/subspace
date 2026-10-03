// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.settings.log

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import space.getsub.core.data.LogRepository
import space.getsub.feature.settings.SettingsSource
import javax.inject.Inject

@HiltViewModel
internal class LogViewerViewModel(
    private val logs: LogRepository,
    lightweightMode: Flow<Boolean>,
) : ViewModel() {
    /** Hilt's entry point; tests pass the lightweight-mode flow directly. */
    @Inject
    constructor(logs: LogRepository, settings: SettingsSource) : this(logs, settings.lightweightMode)

    /**
     * M8.5 spec §3.4 (amended): live while collected. `WhileSubscribed(0)` stops the
     * 1 Hz poll the moment the screen stops collecting (it collects with
     * `collectAsStateWithLifecycle`), so nothing reads the ring while the screen is
     * not visible.
     */
    val state: StateFlow<LogViewerState> =
        logs.tail()
            .combine(lightweightMode) { lines, lightweight ->
                LogViewerState(lines = lines, loading = false, lightweightMode = lightweight)
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(0), LogViewerState())

    /** The next poll (within a second) sees `log.0` emptied and `log.1` gone, and empties the view. */
    fun clear() {
        viewModelScope.launch { logs.clear() }
    }
}
