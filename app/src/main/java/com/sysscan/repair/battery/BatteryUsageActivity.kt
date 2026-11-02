package com.sysscan.repair.battery

import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.sysscan.repair.R
import com.sysscan.repair.databinding.ActivityBatteryUsageBinding
import com.sysscan.repair.diag.battery.AppBatteryUsage
import com.sysscan.repair.diag.battery.BatteryUsageAnalyzer
import com.sysscan.repair.diag.battery.BatteryUsageReport
import com.sysscan.repair.diag.battery.BatteryUsageStore
import com.sysscan.repair.util.Formatters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Tela "Consumo de bateria": mostra a corrente real do aparelho e o ranking dos
 * aplicativos que mais consomem, com atalho para restringir cada um.
 */
class BatteryUsageActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBatteryUsageBinding
    private val adapter = AppBatteryUsageAdapter { app -> showAppOptions(app) }

    private var windowHours = 24
    private var report: BatteryUsageReport? = null
    private var loading = false
    private var queuedLoad = false
    private var firstResume = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBatteryUsageBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.appList.layoutManager = LinearLayoutManager(this)
        binding.appList.adapter = adapter

        binding.btnBack.setOnClickListener { finish() }
        binding.btnMeasure.setOnClickListener { load(measure = true) }
        binding.btnGrant.setOnClickListener {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
        binding.window1h.setOnClickListener { selectWindow(1) }
        binding.window6h.setOnClickListener { selectWindow(6) }
        binding.window24h.setOnClickListener { selectWindow(24) }

        // Mostra imediatamente o último relatório salvo e depois atualiza do sistema.
        report = BatteryUsageStore.load(this)
        report?.let { render(it) }

        updateWindowButtons()
        load()
    }

    override fun onResume() {
        super.onResume()
        if (firstResume) {
            firstResume = false
            return
        }
        // Voltando da tela de permissão: tenta de novo se ainda faltava acesso.
        if (report?.usageAccessGranted != true) {
            load()
        }
    }

    private fun selectWindow(hours: Int) {
        if (windowHours == hours) return
        windowHours = hours
        updateWindowButtons()
        if (loading) {
            queuedLoad = true
            return
        }
        load()
    }

    private fun updateWindowButtons() {
        val selectedBg = ContextCompat.getColor(this, R.color.sysscan_primary)
        val unselectedBg = ContextCompat.getColor(this, R.color.card_bg)
        val selectedText = ContextCompat.getColor(this, R.color.white)
        val unselectedText = ContextCompat.getColor(this, R.color.text_primary)
        val pairs = listOf(
            binding.window1h to (windowHours == 1),
            binding.window6h to (windowHours == 6),
            binding.window24h to (windowHours == 24)
        )
        pairs.forEach { (button, selected) ->
            button.backgroundTintList =
                ColorStateList.valueOf(if (selected) selectedBg else unselectedBg)
            button.setTextColor(if (selected) selectedText else unselectedText)
        }
    }

    private fun load(measure: Boolean = false) {
        if (loading) return
        loading = true
        showLoading(true)
        val hours = windowHours
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val analyzer = BatteryUsageAnalyzer(applicationContext)
                    if (measure) {
                        analyzer.analyzeWithMeasurement(hours, 2)
                    } else {
                        analyzer.analyze(hours)
                    }
                } catch (_: Exception) {
                    null
                }
            }
            loading = false
            showLoading(false)
            if (result != null) {
                report = result
                runCatching { BatteryUsageStore.save(applicationContext, result) }
                render(result)
            } else {
                Toast.makeText(
                    this@BatteryUsageActivity, R.string.battery_error, Toast.LENGTH_LONG
                ).show()
            }
            if (queuedLoad) {
                queuedLoad = false
                load()
            }
        }
    }

    private fun showLoading(show: Boolean) {
        binding.loadingBox.visibility = if (show) View.VISIBLE else View.GONE
        binding.btnMeasure.isEnabled = !show
        binding.btnMeasure.text = getString(
            if (show) R.string.battery_measuring else R.string.battery_measure
        )
    }

    private fun render(result: BatteryUsageReport) {
        val live = result.live
        binding.liveDrain.text = if (live.charging) "⚡" else live.drainLabel()
        binding.liveDetail.text = getString(
            R.string.battery_live_detail,
            live.autonomyLabel(),
            live.levelLabel(),
            live.temperatureLabel()
        )

        val note = when {
            !result.usageAccessGranted -> getString(R.string.battery_live_unavailable)
            live.charging -> getString(R.string.battery_live_charging)
            !live.hasDrain -> getString(R.string.battery_live_unavailable)
            else -> null
        }
        if (note.isNullOrBlank()) {
            binding.liveNote.visibility = View.GONE
        } else {
            binding.liveNote.visibility = View.VISIBLE
            binding.liveNote.text = note
        }

        binding.permissionCard.visibility =
            if (result.usageAccessGranted) View.GONE else View.VISIBLE

        val apps = result.apps.filter { it.weight >= 0.5 || it.foregroundMs > 0 || it.totalBytes > 0 }
        if (result.usageAccessGranted) {
            binding.summaryText.visibility = View.VISIBLE
            val mahPerHour = result.observedMahPerHour
            val counts = if (mahPerHour != null) {
                getString(
                    R.string.battery_summary_observed,
                    apps.size,
                    result.windowLabel(),
                    String.format(Locale.US, "%.0f", mahPerHour)
                )
            } else {
                getString(R.string.battery_summary, apps.size, result.windowLabel())
            }
            binding.summaryText.text =
                getString(R.string.battery_ranking_title) + "\n" + counts
        } else {
            binding.summaryText.visibility = View.GONE
        }

        val notes = result.notes.filter { it.isNotBlank() }
        if (notes.isEmpty()) {
            binding.notesText.visibility = View.GONE
        } else {
            binding.notesText.visibility = View.VISIBLE
            binding.notesText.text = notes.joinToString("\n")
        }

        adapter.submit(apps)
        binding.emptyText.visibility =
            if (result.usageAccessGranted && apps.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun showAppOptions(app: AppBatteryUsage) {
        val message = buildString {
            append("Peso de consumo: ")
            append(String.format(Locale.US, "%.0f", app.weight)).append("/100 (")
            append(app.weightLabel()).append(")\n")
            append("Fatia estimada: ")
            append(String.format(Locale.US, "%.0f", app.sharePercent)).append("% do consumo\n")
            append("Tempo de tela: ").append(app.usageLabel()).append("\n")
            if (app.sessions > 0) append("Aberturas: ").append(app.sessions).append("\n")
            if (app.totalBytes > 0) {
                append("Dados: ").append(Formatters.bytes(app.mobileBytes)).append(" móvel · ")
                append(Formatters.bytes(app.wifiBytes)).append(" Wi-Fi\n")
            }
            app.estimatedMah?.let {
                append("Estimativa: ≈ ").append(Formatters.milliampHours(it)).append("\n")
            }
            if (app.backgroundSuspect) append("\nSinal: atividade em segundo plano.\n")
            if (app.ignoresOptimizations && !app.isSystemApp) {
                append("\nSinal: isento da otimização de bateria.\n")
            }
            append("\n").append(getString(R.string.battery_restrict_hint))
        }

        AlertDialog.Builder(this)
            .setTitle(app.label)
            .setMessage(message)
            .setPositiveButton(R.string.battery_app_restrict) { _, _ -> openAppSettings(app) }
            .setNeutralButton(R.string.battery_app_open) { _, _ -> launchApp(app) }
            .setNegativeButton(R.string.battery_app_close, null)
            .show()
    }

    private fun launchApp(app: AppBatteryUsage) {
        val intent = packageManager.getLaunchIntentForPackage(app.packageName)
        if (intent == null) {
            Toast.makeText(this, R.string.battery_no_launch, Toast.LENGTH_SHORT).show()
            return
        }
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            Toast.makeText(this, R.string.battery_open_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun openAppSettings(app: AppBatteryUsage) {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${app.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(this, R.string.battery_open_failed, Toast.LENGTH_SHORT).show()
        }
    }
}
