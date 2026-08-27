package com.sysscan.repair

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.sysscan.repair.databinding.ActivityMainBinding
import com.sysscan.repair.history.HistoryActivity
import com.sysscan.repair.model.ScanCheck
import com.sysscan.repair.model.ScanSummary
import com.sysscan.repair.updater.UpdateChecker
import com.sysscan.repair.updater.UpdateInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: ScanResultsAdapter
    private val viewModel: ScanViewModel by viewModels()
    private var updating = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = ScanResultsAdapter(::onFixClicked)
        binding.resultsList.layoutManager = LinearLayoutManager(this)
        binding.resultsList.adapter = adapter

        binding.btnScan.setOnClickListener { viewModel.startScan() }
        binding.btnUpdate.setOnClickListener { checkForUpdate() }
        binding.btnDarkToggle.setOnClickListener { toggleDarkMode() }
        binding.btnHistory.setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }
        binding.btnFixAll.setOnClickListener { onFixAllClicked() }

        updateDarkToggleIcon()
        observeState()
    }

    private fun isNightMode(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    private fun toggleDarkMode() {
        AppCompatDelegate.setDefaultNightMode(
            if (isNightMode()) AppCompatDelegate.MODE_NIGHT_NO
            else AppCompatDelegate.MODE_NIGHT_YES
        )
        updateDarkToggleIcon()
    }

    private fun updateDarkToggleIcon() {
        binding.btnDarkToggle.setImageResource(
            if (isNightMode()) R.drawable.ic_light_mode else R.drawable.ic_dark_mode
        )
        binding.btnDarkToggle.contentDescription = getString(
            if (isNightMode()) R.string.toggle_light else R.string.toggle_dark
        )
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.rootInfo.collect { info ->
                        binding.rootStatus.text = info
                    }
                }
                launch {
                    viewModel.uiState.collect(::render)
                }
                launch {
                    viewModel.fixingAll.collect(::renderFixAllState)
                }
            }
        }
    }

    private fun renderFixAllState(fixing: Boolean) {
        binding.btnFixAll.isEnabled = !fixing
        if (!fixing) {
            binding.btnFixAll.text = getString(R.string.fix_all)
        }
    }

    private fun render(state: ScanUiState) {
        when (state) {
            is ScanUiState.Idle -> renderIdle()
            is ScanUiState.Scanning -> renderScanning(state)
            is ScanUiState.Done -> renderDone(state.summary, state.fixResults)
            is ScanUiState.Error -> {
                binding.progressCard.visibility = android.view.View.GONE
                Toast.makeText(this, state.message, Toast.LENGTH_LONG).show()
                renderIdle()
            }
        }
    }

    private fun renderIdle() {
        binding.scoreValue.text = "—"
        binding.scoreIndicator.setProgressCompat(0, false)
        binding.scoreIndicator.setIndicatorColor(ContextCompat.getColor(this, R.color.score_circle_ok))
        binding.scoreSummary.text = getString(R.string.scan_subtitle)
        binding.btnScan.text = getString(R.string.scan_start)
        binding.btnScan.isEnabled = true
        binding.progressCard.visibility = android.view.View.GONE
        binding.summaryRow.visibility = android.view.View.GONE
        binding.btnFixAll.visibility = android.view.View.GONE
        adapter.submit(emptyList(), emptyMap())
    }

    private fun renderScanning(state: ScanUiState.Scanning) {
        binding.progressCard.visibility = android.view.View.VISIBLE
        binding.progressLabel.text = state.label
        val total = if (state.total > 0) state.total else 1
        val pct = (state.done * 100) / total
        binding.progressBar.progress = pct
        binding.scoreIndicator.setProgressCompat(pct, true)
        binding.scoreValue.text = if (state.done > 0) "${state.done}/${state.total}" else "…"
        binding.btnScan.isEnabled = false
        binding.summaryRow.visibility = android.view.View.GONE
        binding.btnFixAll.visibility = android.view.View.GONE
    }

    private fun renderDone(summary: ScanSummary, fixResults: Map<String, com.sysscan.repair.repair.FixResult>) {
        binding.progressCard.visibility = android.view.View.GONE
        binding.btnScan.isEnabled = true
        binding.btnScan.text = getString(R.string.scan_again)

        val score = summary.score
        binding.scoreValue.text = score.toString()
        binding.scoreIndicator.setProgressCompat(score, true)
        val color = when {
            score >= 80 -> R.color.score_circle_ok
            score >= 60 -> R.color.score_circle_warn
            else -> R.color.score_circle_crit
        }
        binding.scoreIndicator.setIndicatorColor(ContextCompat.getColor(this, color))

        binding.scoreSummary.text = when {
            summary.criticalCount > 0 -> getString(R.string.score_summary_critical)
            summary.warningCount > 0 -> getString(R.string.score_summary_warn)
            else -> getString(R.string.score_summary_ok)
        }

        binding.okCount.text = "${summary.okCount} OK"
        binding.warnCount.text = "${summary.warningCount} Atenção"
        binding.critCount.text = "${summary.criticalCount} Crítico"
        binding.summaryRow.visibility = android.view.View.VISIBLE

        binding.btnFixAll.visibility =
            if (summary.fixableChecks.isNotEmpty()) android.view.View.VISIBLE
            else android.view.View.GONE

        adapter.submit(summary.checks, fixResults)
    }

    private fun onFixClicked(check: ScanCheck) {
        val fix = com.sysscan.repair.repair.FixRegistry.resolve(check.fixId ?: return)
        val label = fix?.label ?: getString(R.string.fix_running)
        Toast.makeText(this, "$label…", Toast.LENGTH_SHORT).show()
        viewModel.runFix(check) { result ->
            runOnUiThread {
                Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun onFixAllClicked() {
        viewModel.fixAll(
            onProgress = { done, total ->
                runOnUiThread {
                    binding.btnFixAll.isEnabled = false
                    binding.btnFixAll.text = getString(R.string.fix_all_running) +
                        " $done/$total"
                }
            },
            onFinished = {
                runOnUiThread {
                    binding.btnFixAll.isEnabled = true
                    binding.btnFixAll.text = getString(R.string.fix_all)
                    Toast.makeText(this, R.string.fix_all_done, Toast.LENGTH_LONG).show()
                }
            }
        )
    }

    private fun checkForUpdate() {
        if (updating) return
        if (UpdateChecker.GITHUB_REPO.startsWith("SEU-USUARIO")) {
            Toast.makeText(this, R.string.update_not_configured, Toast.LENGTH_LONG).show()
            return
        }
        updating = true
        Toast.makeText(this, R.string.update_checking, Toast.LENGTH_SHORT).show()
        lifecycleScope.launch(Dispatchers.IO) {
            val info = UpdateChecker.check(applicationContext)
            runOnUiThread {
                updating = false
                when {
                    info == null -> Toast.makeText(
                        this@MainActivity, R.string.update_uptodate, Toast.LENGTH_LONG
                    ).show()
                    else -> showUpdateDialog(info)
                }
            }
        }
    }

    private fun showUpdateDialog(info: UpdateInfo) {
        val notes = info.notes.trim().ifBlank { getString(R.string.update_available) }
        AlertDialog.Builder(this)
            .setTitle("${getString(R.string.update_available)} ${info.latestVersion}")
            .setMessage(notes)
            .setPositiveButton(R.string.update_download_install) { _, _ ->
                downloadAndInstall(info)
            }
            .setNegativeButton(R.string.update_later, null)
            .show()
    }

    private fun downloadAndInstall(info: UpdateInfo) {
        Toast.makeText(this, R.string.update_downloading, Toast.LENGTH_SHORT).show()
        lifecycleScope.launch(Dispatchers.IO) {
            UpdateChecker.downloadApk(
                applicationContext, info.downloadUrl
            ) { result ->
                runOnUiThread {
                    result.onSuccess { file -> installDownloaded(file) }
                        .onFailure {
                            Toast.makeText(
                                this@MainActivity,
                                R.string.update_download_failed, Toast.LENGTH_LONG
                            ).show()
                        }
                }
            }
        }
    }

    private fun installDownloaded(file: File) {
        if (!UpdateChecker.installApk(this, file)) {
            Toast.makeText(this, R.string.update_install_failed, Toast.LENGTH_LONG).show()
        }
    }
}
