package com.sysscan.repair.security

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SecurityUiState(
    val scanning: Boolean = false,
    val label: String = "",
    val report: SecurityScanReport? = null,
    val error: String? = null,
    val vtBusy: Boolean = false,
    val message: String? = null
)

class SecurityViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(SecurityUiState())
    val state: StateFlow<SecurityUiState> = _state.asStateFlow()

    fun scan() {
        if (_state.value.scanning) return
        _state.value = _state.value.copy(scanning = true, error = null, message = null)
        viewModelScope.launch {
            val context = getApplication<Application>()
            val result = withContext(Dispatchers.Default) {
                try {
                    SecurityScanner(context).scan(hashApks = false) { _, _, label ->
                        _state.value = _state.value.copy(label = label)
                    }
                } catch (e: Exception) {
                    null
                }
            }
            if (result == null) {
                _state.value = _state.value.copy(
                    scanning = false,
                    error = "Não foi possível concluir a varredura."
                )
                return@launch
            }

            // Reaproveita laudos do VirusTotal já recebidos (válidos por 7 dias).
            val hashes = SecurityHashStore.hashes(context)
            val verdicts = SecurityHashStore.verdicts(context)
            val withVerdicts = result.copy(
                apps = result.apps.map { app ->
                    val sha = hashes[app.packageName]?.sha256
                    val verdict = sha?.let { verdicts[it] }
                    if (verdict != null) app.withVerdict(verdict) else app
                }
            )

            persist(withVerdicts)
            _state.value = _state.value.copy(scanning = false, report = withVerdicts)
        }
    }

    /** Verifica no VirusTotal os apps com indícios mais fortes (limitado por causa da cota). */
    fun verifySuspiciousWithVt(limit: Int = 3) {
        val report = _state.value.report ?: return
        val targets = report.suspiciousApps.take(limit).map { it.packageName }
        runVt(targets)
    }

    fun verifySingle(packageName: String) {
        runVt(listOf(packageName))
    }

    fun clearMessage() {
        _state.value = _state.value.copy(message = null)
    }

    private fun runVt(packages: List<String>) {
        if (packages.isEmpty() || _state.value.vtBusy) return
        val context = getApplication<Application>()
        if (!VtSettings.isConfigured(context)) {
            _state.value = _state.value.copy(message = MESSAGE_NO_KEY)
            return
        }
        _state.value = _state.value.copy(vtBusy = true, message = null)
        viewModelScope.launch {
            val scanner = SecurityScanner(context)
            var failures = 0
            for (packageName in packages) {
                val sha = hashFor(context, packageName)
                if (sha == null) {
                    failures++
                    continue
                }
                when (val result = withContext(Dispatchers.IO) {
                    VtClient.lookup(context, sha)
                }) {
                    is VtLookupResult.Done -> {
                        val current = _state.value.report ?: continue
                        val updated = scanner.applyVerdict(current, packageName, result.verdict)
                        persist(updated)
                        _state.value = _state.value.copy(report = updated)
                    }
                    is VtLookupResult.Failed -> {
                        failures++
                        _state.value = _state.value.copy(message = result.reason)
                        if (result.quotaExceeded) break
                    }
                }
                // Cota gratuita do VirusTotal: espaça as consultas.
                delay(4_000)
            }
            _state.value = _state.value.copy(
                vtBusy = false,
                message = if (failures > 0 && _state.value.message == null) {
                    "Algumas consultas não puderam ser feitas."
                } else {
                    _state.value.message
                }
            )
        }
    }

    /**
     * Hash do APK do app: usa o cache ou calcula na hora. O cálculo é feito apenas para o
     * app que o usuário pediu para verificar (não na varredura toda), para a análise
     * continuar rápida e econômica.
     */
    private suspend fun hashFor(context: android.content.Context, packageName: String): String? {
        val cached = SecurityHashStore.hashes(context)[packageName]
        if (cached != null) return cached.sha256
        val apkPath = _state.value.report?.apps
            ?.firstOrNull { it.packageName == packageName }
            ?.apkPath
            ?: return null
        if (apkPath.isBlank()) return null
        return withContext(Dispatchers.IO) {
            val file = java.io.File(apkPath)
            val sha = ApkInspector.sha256(file) ?: return@withContext null
            val entries = SecurityHashStore.hashes(context)
            entries[packageName] = SecurityHashStore.HashEntry(sha, file.length(), apkPath)
            SecurityHashStore.saveHashes(context, entries)
            sha
        }
    }

    private fun persist(report: SecurityScanReport) {
        runCatching {
            val context = getApplication<Application>()
            val hashes = SecurityHashStore.hashes(context).mapValues { it.value.sha256 }
            SecurityResultsStore.save(context, report, hashes)
        }
    }

    companion object {
        /** Mensagem interna usada pela tela para abrir a configuração da chave. */
        const val MESSAGE_NO_KEY = "SemChave"
    }
}
