package com.sysscan.repair

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sysscan.repair.diag.SystemDiagnostics
import com.sysscan.repair.history.ScanHistoryStore
import com.sysscan.repair.model.ScanCheck
import com.sysscan.repair.model.ScanSummary
import com.sysscan.repair.repair.FixResult
import com.sysscan.repair.repair.RepairEngine
import com.sysscan.repair.root.RootChecker
import com.sysscan.repair.root.RootStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface ScanUiState {
    data object Idle : ScanUiState
    data class Scanning(val done: Int, val total: Int, val label: String) : ScanUiState
    data class Done(
        val summary: ScanSummary,
        val fixResults: Map<String, FixResult> = emptyMap()
    ) : ScanUiState
    data class Error(val message: String) : ScanUiState
}

class ScanViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow<ScanUiState>(ScanUiState.Idle)
    val uiState: StateFlow<ScanUiState> = _uiState.asStateFlow()

    private val _rootInfo = MutableStateFlow<String>("")
    val rootInfo: StateFlow<String> = _rootInfo.asStateFlow()

    private val _fixingAll = MutableStateFlow(false)
    val fixingAll: StateFlow<Boolean> = _fixingAll.asStateFlow()

    private val _lastRootStatus = MutableStateFlow<RootStatus?>(null)
    val lastRootStatus: StateFlow<RootStatus?> = _lastRootStatus.asStateFlow()

    private val repairEngine = RepairEngine(application)

    init {
        viewModelScope.launch(Dispatchers.Default) {
            val status = RootChecker.status()
            _lastRootStatus.value = status
            _rootInfo.value = status.describe()
        }
    }

    fun startScan() {
        if (_uiState.value is ScanUiState.Scanning) return
        _uiState.value = ScanUiState.Scanning(0, 0, "Iniciando varredura...")
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val rootStatus = RootChecker.status()
                _lastRootStatus.value = rootStatus
                _rootInfo.value = rootStatus.describe()
                val hasRoot = rootStatus.hasRoot
                val started = System.currentTimeMillis()
                val diagnostics = SystemDiagnostics(getApplication(), hasRoot)
                val checks = diagnostics.runAll { done, total, label ->
                    _uiState.value = ScanUiState.Scanning(done, total, label)
                }
                val finished = System.currentTimeMillis()
                val summary = ScanSummary(
                    checks = checks,
                    hasRoot = hasRoot,
                    startedAt = started,
                    finishedAt = finished,
                    rootRepairAvailable = hasRoot
                )
                _uiState.value = ScanUiState.Done(summary)
                ScanHistoryStore.add(getApplication(), summary)
            } catch (e: Exception) {
                _uiState.value = ScanUiState.Error(e.message ?: "Erro durante a varredura.")
            }
        }
    }

    fun runFix(check: ScanCheck, onResult: (FixResult) -> Unit) {
        viewModelScope.launch(Dispatchers.Default) {
            val result = repairEngine.execute(check.fixId ?: return@launch)
            onResult(result)
            val current = _uiState.value
            if (current is ScanUiState.Done) {
                _uiState.value = current.copy(
                    fixResults = current.fixResults + (check.id to result)
                )
            }
        }
    }

    fun fixAll(onProgress: (done: Int, total: Int) -> Unit, onFinished: () -> Unit) {
        val current = _uiState.value as? ScanUiState.Done ?: return
        val fixable = current.summary.fixableChecks
        if (fixable.isEmpty() || _fixingAll.value) return
        _fixingAll.value = true
        viewModelScope.launch(Dispatchers.Default) {
            try {
                var done = 0
                var results = current.fixResults
                for (check in fixable) {
                    val result = repairEngine.execute(check.fixId ?: continue)
                    results = results + (check.id to result)
                    done++
                    _uiState.value = current.copy(fixResults = results)
                    onProgress(done, fixable.size)
                    delay(150)
                }
            } finally {
                _fixingAll.value = false
                onFinished()
            }
        }
    }

    fun reset() {
        _uiState.value = ScanUiState.Idle
    }
}
