package gr.elfantasy.advisor.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import gr.elfantasy.advisor.data.DataSnapshot
import gr.elfantasy.advisor.data.HistoryEntry
import gr.elfantasy.advisor.data.Prefs
import gr.elfantasy.advisor.data.RecSet
import gr.elfantasy.advisor.data.Store
import gr.elfantasy.advisor.data.Sync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class UiState(
    val loading: Boolean = true,
    val syncing: Boolean = false,
    val progress: String? = null,
    val error: String? = null,
    val recs: RecSet? = null,
    val snapshot: DataSnapshot? = null,
    val history: Map<Int, HistoryEntry> = emptyMap()
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val store = Store(app)
    val prefs = Prefs(app)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    init {
        viewModelScope.launch { reload() }
    }

    private suspend fun reload() {
        val (recs, snap, hist) = withContext(Dispatchers.IO) {
            Triple(store.loadRecs(), store.loadSnapshot(), store.loadHistory())
        }
        _state.update { it.copy(loading = false, recs = recs, snapshot = snap, history = hist) }
    }

    fun refresh() {
        if (_state.value.syncing) return
        viewModelScope.launch {
            _state.update { it.copy(syncing = true, progress = "Ξεκινάει…", error = null) }
            try {
                val r = withContext(Dispatchers.IO) {
                    Sync.run(getApplication(), force = true) { msg ->
                        _state.update { s -> s.copy(progress = msg) }
                    }
                }
                reload()
                _state.update { it.copy(syncing = false, progress = r.message) }
            } catch (e: Exception) {
                _state.update { it.copy(syncing = false, progress = null, error = e.message ?: e.toString()) }
            }
        }
    }

    fun saveToken(raw: String) {
        prefs.token = raw.trim().removePrefix("Bearer").trim().ifEmpty { null }
    }
}
