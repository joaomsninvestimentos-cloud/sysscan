package com.sysscan.repair.security

import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.sysscan.repair.R
import com.sysscan.repair.databinding.ActivitySecurityBinding
import com.sysscan.repair.databinding.DialogSecurityAppBinding
import com.sysscan.repair.util.Formatters
import kotlinx.coroutines.launch

/**
 * Tela de varredura de segurança: mostra apps ocultos (sem ícone), apps com poderes
 * especiais, indícios de spyware/malware e permite desinstalar ou consultar o VirusTotal.
 */
class SecurityActivity : AppCompatActivity() {

    private enum class Filter { ALL, SUSPICIOUS, HIDDEN }

    private lateinit var binding: ActivitySecurityBinding
    private val viewModel: SecurityViewModel by viewModels()
    private val adapter = SecurityAppAdapter { app -> showAppDialog(app) }

    private var filter = Filter.ALL
    private var lastMessage: String? = null
    private var lastError: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySecurityBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.appList.layoutManager = LinearLayoutManager(this)
        binding.appList.adapter = adapter

        binding.btnBack.setOnClickListener { finish() }
        binding.btnRescan.setOnClickListener { viewModel.scan() }
        binding.btnVtSettings.setOnClickListener { showVtSettings() }
        binding.btnVt.setOnClickListener {
            if (VtSettings.isConfigured(this)) {
                if (viewModel.state.value.report?.suspiciousApps.isNullOrEmpty()) {
                    Toast.makeText(this, R.string.security_clean_title, Toast.LENGTH_SHORT).show()
                } else {
                    viewModel.verifySuspiciousWithVt()
                }
            } else {
                showVtSettings()
            }
        }
        binding.filterAll.setOnClickListener { setFilter(Filter.ALL) }
        binding.filterSuspicious.setOnClickListener { setFilter(Filter.SUSPICIOUS) }
        binding.filterHidden.setOnClickListener { setFilter(Filter.HIDDEN) }

        updateFilterButtons()
        observe()

        if (viewModel.state.value.report == null) {
            viewModel.scan()
        }
    }

    private fun observe() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect(::render)
            }
        }
    }

    private fun setFilter(newFilter: Filter) {
        filter = if (filter == newFilter) Filter.ALL else newFilter
        updateFilterButtons()
        viewModel.state.value.report?.let(::renderReport)
    }

    private fun updateFilterButtons() {
        val selectedBg = ContextCompat.getColor(this, R.color.sysscan_primary)
        val unselectedBg = ContextCompat.getColor(this, R.color.card_bg)
        val selectedText = ContextCompat.getColor(this, R.color.white)
        val unselectedText = ContextCompat.getColor(this, R.color.text_primary)
        listOf(
            binding.filterAll to (filter == Filter.ALL),
            binding.filterSuspicious to (filter == Filter.SUSPICIOUS),
            binding.filterHidden to (filter == Filter.HIDDEN)
        ).forEach { (button, selected) ->
            button.backgroundTintList =
                ColorStateList.valueOf(if (selected) selectedBg else unselectedBg)
            button.setTextColor(if (selected) selectedText else unselectedText)
        }
    }

    private fun render(state: SecurityUiState) {
        binding.loadingBox.visibility = if (state.scanning) View.VISIBLE else View.GONE
        binding.loadingLabel.text = state.label.ifBlank { getString(R.string.security_scanning) }
        binding.btnRescan.isEnabled = !state.scanning
        binding.btnVt.isEnabled = !state.scanning && !state.vtBusy
        binding.btnVt.text = getString(
            if (state.vtBusy) R.string.security_vt_running else R.string.security_vt_button
        )

        state.error?.takeIf { it != lastError }?.let { message ->
            lastError = message
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }

        state.message?.takeIf { it != lastMessage }?.let { message ->
            lastMessage = message
            if (message == SecurityViewModel.MESSAGE_NO_KEY) {
                showVtSettings()
            } else {
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }
        }

        state.report?.let(::renderReport)
    }

    private fun renderReport(report: SecurityScanReport) {
        val worst = report.worstLevel
        val color = ContextCompat.getColor(this, RiskRules.colorResFor(worst))
        binding.summaryIcon.setColorFilter(color)

        val suspicious = report.suspiciousApps
        if (suspicious.isEmpty()) {
            binding.summaryTitle.text = getString(R.string.security_clean_title)
            binding.summaryBody.text = getString(R.string.security_clean_body)
        } else {
            val top = suspicious.first()
            binding.summaryTitle.text = getString(R.string.security_found_title, suspicious.size)
            binding.summaryTitle.setTextColor(color)
            binding.summaryBody.text =
                getString(R.string.security_found_body, top.label, top.score)
        }
        binding.summaryTotal.text = getString(R.string.security_total, report.totalApps)

        val visible = when (filter) {
            Filter.ALL -> report.apps.filter {
                it.score > 0 || it.isHidden || it.accessibilityService ||
                    it.notificationListener || it.deviceAdmin
            }
            Filter.SUSPICIOUS -> suspicious
            Filter.HIDDEN -> report.hiddenApps
        }
        adapter.submit(visible)
        binding.emptyText.visibility =
            if (visible.isEmpty()) View.VISIBLE else View.GONE
        binding.emptyText.text = when (filter) {
            Filter.HIDDEN -> getString(R.string.security_clean_body)
            Filter.SUSPICIOUS -> getString(R.string.security_clean_body)
            Filter.ALL -> getString(R.string.security_empty)
        }
    }

    private fun showAppDialog(app: AppSecurityReport) {
        val dialogBinding = DialogSecurityAppBinding.inflate(layoutInflater)
        dialogBinding.dialogTitle.text = app.label
        dialogBinding.dialogBody.text = detailsText(app)

        // Limita a altura do texto para o diálogo caber na tela em qualquer aparelho.
        dialogBinding.dialogBody.post {
            runCatching {
                val maxHeight = (resources.displayMetrics.heightPixels * 0.42).toInt()
                val measured = dialogBinding.dialogBody.height
                if (measured > maxHeight) {
                    val params = dialogBinding.bodyScroll.layoutParams
                    params.height = maxHeight
                    dialogBinding.bodyScroll.layoutParams = params
                }
            }
        }

        val dialog = AlertDialog.Builder(this)
            .setView(dialogBinding.root)
            .create()

        dialogBinding.btnVt.setOnClickListener {
            if (VtSettings.isConfigured(this)) {
                viewModel.verifySingle(app.packageName)
                Toast.makeText(this, R.string.security_vt_running, Toast.LENGTH_SHORT).show()
            } else {
                showVtSettings()
            }
            dialog.dismiss()
        }
        dialogBinding.btnUninstall.setOnClickListener {
            uninstall(app)
            dialog.dismiss()
        }
        dialogBinding.btnAppSettings.setOnClickListener {
            openAppSettings(app)
            dialog.dismiss()
        }
        dialogBinding.btnClose.setOnClickListener { dialog.dismiss() }

        dialog.show()
    }

    private fun detailsText(app: AppSecurityReport): String = buildString {
        append("Risco: ").append(app.score).append("/100 (").append(app.level.label).append(")\n")
        append("Pacote: ").append(app.packageName).append("\n")
        append("Versão: ").append(app.versionName.ifBlank { "—" })
        append(" · Android alvo: ").append(app.targetSdk).append("\n")
        append("Instalado por: ").append(app.installerLabel)
        app.installerPackage?.takeIf { it.isNotBlank() }?.let { append(" (").append(it).append(")") }
        append("\n")
        append("Ícone na tela: ").append(if (app.hasLauncher) "sim" else "não — app oculto")
        append("\n")
        if (app.apkPath.isNotBlank()) {
            append("APK: ").append(app.apkPath).append("\n")
        }
        if (app.apkSizeBytes > 0) {
            append("Tamanho: ").append(Formatters.bytes(app.apkSizeBytes)).append("\n")
        }
        if (app.debuggable) append("Build de depuração: sim\n")

        val powers = buildList {
            if (app.accessibilityService) add("acessibilidade")
            if (app.notificationListener) add("ler notificações")
            if (app.deviceAdmin) add("administrador do aparelho")
            if (app.drawOverlays) add("desenhar sobre outros apps")
            if (app.keyboard) add("teclado")
        }
        append("Poderes especiais: ")
        append(if (powers.isEmpty()) "nenhum" else powers.joinToString(", "))
        append("\n")
        append("Permissões sensíveis: ").append(app.permissionsLabel()).append("\n")
        app.batteryLabel()?.let { append("Bateria: ").append(it).append("\n") }

        append("\nIndícios encontrados:\n")
        if (app.signals.isEmpty()) {
            append("• nenhum indício relevante\n")
        } else {
            app.signals.forEach { signal ->
                append("• ").append(signal.title).append(" (+").append(signal.points).append(")\n")
                append("   ").append(signal.detail).append("\n")
            }
        }

        app.vt?.let { verdict ->
            append("\nVirusTotal: ").append(verdict.summary()).append("\n")
            if (verdict.permalink.isNotBlank()) append(verdict.permalink).append("\n")
        }

        append("\nO que fazer:\n")
        when (app.level) {
            RiskLevel.CRITICAL, RiskLevel.HIGH ->
                append("• Se você não reconhece este app, remova agora.\n")
            RiskLevel.MEDIUM ->
                append("• Confirme se foi você quem instalou.\n")
            else -> append("• Nada urgente: mantenha atualizado.\n")
        }
        if (app.accessibilityService || app.deviceAdmin) {
            append("• Antes de desinstalar, revogue acessibilidade/administrador em Configurações.\n")
        }
        append("• Não desinstale apps do sistema: pode quebrar o aparelho.\n")
    }

    private fun uninstall(app: AppSecurityReport) {
        if (app.isSystemApp) {
            Toast.makeText(this, R.string.security_uninstall_failed, Toast.LENGTH_LONG).show()
            return
        }
        val intent = Intent(
            Intent.ACTION_DELETE,
            Uri.parse("package:${app.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(this, R.string.security_uninstall_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun openAppSettings(app: AppSecurityReport) {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${app.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(this, R.string.security_uninstall_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun showVtSettings() {
        val input = EditText(this).apply {
            hint = getString(R.string.security_vt_key)
            setText(VtSettings.apiKey(this@SecurityActivity))
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        input.setPadding(pad, pad / 2, pad, 0)

        AlertDialog.Builder(this)
            .setTitle(R.string.security_vt_settings)
            .setMessage(R.string.security_vt_help)
            .setView(input)
            .setPositiveButton(R.string.chat_save) { _, _ ->
                VtSettings.save(this, input.text.toString())
                Toast.makeText(this, R.string.security_vt_saved, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
