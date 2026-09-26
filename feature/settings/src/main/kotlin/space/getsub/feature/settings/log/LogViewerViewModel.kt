// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.settings.log

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import space.getsub.core.data.LogRepository
import javax.inject.Inject

@HiltViewModel
internal class LogViewerViewModel
@Inject
constructor(
    private val logs: LogRepository,
) : ViewModel() {
    /**
     * M8.5 spec §3.4 (amended): live while collected. `WhileSubscribed(0)` stops the
     * 1 Hz poll the moment the screen stops collecting (it collects with
     * `collectAsStateWithLifecycle`), so nothing reads the ring while the screen is
     * not visible.
     */
    val state: StateFlow<LogViewerState> =
        logs.tail()
            .map { LogViewerState(lines = it, loading = false) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(0), LogViewerState())

    /** The next poll (within a second) sees both files gone and empties the view. */
    fun clear() {
        viewModelScope.launch { logs.clear() }
    }
}
