package com.sysscan.repair

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
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
import com.sysscan.repair.advisor.ChatActivity
import com.sysscan.repair.history.HistoryActivity
import com.sysscan.repair.model.ScanCheck
import com.sysscan.repair.model.ScanSeverity
import com.sysscan.repair.model.ScanSummary
import com.sysscan.repair.root.RootPrompt
import com.sysscan.repair.updater.UpdateCheckResult
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
    private var severityFilter: ScanSeverity? = null
    private var lastSummary: ScanSummary? = null
    private var pendingUpdate: UpdateInfo? = null
    private var rootDialogVisible = false
    private var askedNotifyPermission = false
    private var autoRootPromptShown = false

    private val notifyPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) RootPrompt.show(this, openManager = false)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = ScanResultsAdapter(::onFixClicked)
        binding.resultsList.layoutManager = LinearLayoutManager(this)
        binding.resultsList.adapter = adapter

        binding.btnScan.setOnClickListener { viewModel.startScan() }
        binding.btnUpdate.setOnClickListener { checkForUpdate(silent = false) }
        binding.btnDarkToggle.setOnClickListener { toggleDarkMode() }
        binding.btnHistory.setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }
        binding.btnChat.setOnClickListener {
            startActivity(Intent(this, ChatActivity::class.java))
        }
        binding.btnFixAll.setOnClickListener { onFixAllClicked() }
        binding.rootRow.setOnClickListener { onRootStatusClicked() }
        binding.okCount.setOnClickListener { toggleFilter(ScanSeverity.OK) }
        binding.warnCount.setOnClickListener { toggleFilter(ScanSeverity.WARNING) }
        binding.critCount.setOnClickListener { toggleFilter(ScanSeverity.CRITICAL) }
        binding.btnFilterClear.setOnClickListener { toggleFilter(null) }
        binding.btnUpdateBanner.setOnClickListener {
            pendingUpdate?.let { showUpdateDialog(it) } ?: checkForUpdate(silent = false)
        }

        updateDarkToggleIcon()
        observeState()
        RootPrompt.ensureChannel(this)
        checkForUpdate(silent = true)
        if (intent.getBooleanExtra(RootPrompt.EXTRA_REQUEST_ROOT, false)) {
            onRootStatusClicked()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(RootPrompt.EXTRA_REQUEST_ROOT, false)) {
            onRootStatusClicked()
        }
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
                        val granted = viewModel.lastRootStatus.value?.hasRoot == true
                        binding.rootStatus.setTextColor(
                            ContextCompat.getColor(
                                this@MainActivity,
                                if (granted) R.color.ok_green else R.color.warn_amber
                            )
                        )
                        if (granted) RootPrompt.cancel(this@MainActivity)
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
        binding.emptyFilterText.visibility = android.view.View.GONE
        severityFilter = null
        lastSummary = null
        adapter.submit(emptyList(), emptyMap())
    }

    private fun renderScanning(state: ScanUiState.Scanning) {
        binding.progressCard.visibility = android.view.View.VISIBLE
        binding.progressLabel.text = state.label
        binding.emptyFilterText.visibility = android.view.View.GONE
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

        val isNewScan = lastSummary !== summary
        lastSummary = summary
        if (isNewScan) {
            severityFilter = null
            adapter.setSeverityFilter(null)
        }

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

        renderFilterPills(summary)
        renderEmptyFilterMessage()
        adapter.submit(summary.checks, fixResults)
        if (!summary.hasRoot && !autoRootPromptShown) {
            autoRootPromptShown = true
            maybePromptRoot()
        }
    }

    private fun maybePromptRoot() {
        val status = viewModel.lastRootStatus.value
        if (status?.hasRoot == true) return
        showRootPrompt(openManager = false)
    }

    private fun onRootStatusClicked() {
        val current = viewModel.lastRootStatus.value
        if (current?.hasRoot == true) {
            RootPrompt.cancel(this)
            Toast.makeText(this, R.string.root_granted, Toast.LENGTH_SHORT).show()
            return
        }
        showRootPrompt(openManager = true)
        viewModel.refreshRoot(force = true)
    }

    private fun showRootPrompt(openManager: Boolean) {
        requestNotifyPermission()
        RootPrompt.show(this, openManager = false)
        if (rootDialogVisible) return
        rootDialogVisible = true
        AlertDialog.Builder(this)
            .setTitle(R.string.root_dialog_title)
            .setMessage(R.string.root_dialog_message)
            .setPositiveButton(R.string.root_dialog_grant) { _, _ ->
                rootDialogVisible = false
                Toast.makeText(this, R.string.root_requesting, Toast.LENGTH_SHORT).show()
                viewModel.refreshRoot(force = true)
            }
            .setNeutralButton(R.string.root_dialog_open_magisk) { _, _ ->
                rootDialogVisible = false
                com.sysscan.repair.root.RootChecker.openManager(this)
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                rootDialogVisible = false
            }
            .setOnDismissListener { rootDialogVisible = false }
            .show()
        if (openManager) {
            binding.rootRow.postDelayed({
                if (viewModel.lastRootStatus.value?.hasRoot != true) {
                    com.sysscan.repair.root.RootChecker.openManager(this)
                }
            }, 1200)
        }
    }

    private fun requestNotifyPermission() {
        if (askedNotifyPermission) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (RootPrompt.canNotify(this)) return
        askedNotifyPermission = true
        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun toggleFilter(severity: ScanSeverity?) {
        val summary = lastSummary ?: return
        val target = if (severityFilter == severity) null else severity
        val count = when (severity) {
            ScanSeverity.OK -> summary.okCount
            ScanSeverity.WARNING -> summary.warningCount
            ScanSeverity.CRITICAL -> summary.criticalCount
            ScanSeverity.INFO -> 0
            null -> 0
        }
        if (severity != null && count == 0) {
            Toast.makeText(this, R.string.filter_empty, Toast.LENGTH_SHORT).show()
            return
        }
        severityFilter = target
        adapter.setSeverityFilter(target)
        renderFilterPills(summary)
        renderEmptyFilterMessage()
        if (target != null && count > 0) {
            val label = when (target) {
                ScanSeverity.OK -> getString(R.string.severity_ok)
                ScanSeverity.WARNING -> getString(R.string.severity_warning)
                ScanSeverity.CRITICAL -> getString(R.string.severity_critical)
                else -> ""
            }
            Toast.makeText(
                this,
                getString(R.string.filter_showing, count, label),
                Toast.LENGTH_SHORT
            ).show()
            scrollToFilteredItem(target)
        }
    }

    private fun scrollToFilteredItem(severity: ScanSeverity) {
        binding.resultsList.post {
            val index = adapter.indexOfFirst(severity)
            if (index >= 0) {
                val manager = binding.resultsList.layoutManager as? LinearLayoutManager
                manager?.scrollToPositionWithOffset(index, 24)
            }
        }
    }

    private fun renderFilterPills(summary: ScanSummary) {
        binding.okCount.backgroundTintList = ContextCompat.getColorStateList(
            this,
            if (severityFilter == ScanSeverity.OK) R.color.ok_green else R.color.ok_green_bg
        )
        binding.warnCount.backgroundTintList = ContextCompat.getColorStateList(
            this,
            if (severityFilter == ScanSeverity.WARNING) R.color.warn_amber else R.color.warn_amber_bg
        )
        binding.critCount.backgroundTintList = ContextCompat.getColorStateList(
            this,
            if (severityFilter == ScanSeverity.CRITICAL) R.color.crit_red else R.color.crit_red_bg
        )
        binding.okCount.setTextColor(
            ContextCompat.getColor(
                this,
                if (severityFilter == ScanSeverity.OK) R.color.white else R.color.ok_green
            )
        )
        binding.warnCount.setTextColor(
            ContextCompat.getColor(
                this,
                if (severityFilter == ScanSeverity.WARNING) R.color.white else R.color.warn_amber
            )
        )
        binding.critCount.setTextColor(
            ContextCompat.getColor(
                this,
                if (severityFilter == ScanSeverity.CRITICAL) R.color.white else R.color.crit_red
            )
        )
        binding.btnFilterClear.visibility =
            if (severityFilter == null) android.view.View.GONE
            else android.view.View.VISIBLE

        binding.okCount.isEnabled = true
        binding.warnCount.isEnabled = true
        binding.critCount.isEnabled = true
        binding.okCount.alpha = if (summary.okCount > 0) 1f else 0.55f
        binding.warnCount.alpha = if (summary.warningCount > 0) 1f else 0.55f
        binding.critCount.alpha = if (summary.criticalCount > 0) 1f else 0.55f
        binding.okCount.contentDescription = getString(
            R.string.filter_desc, summary.okCount, getString(R.string.severity_ok)
        )
        binding.warnCount.contentDescription = getString(
            R.string.filter_desc, summary.warningCount, getString(R.string.severity_warning)
        )
        binding.critCount.contentDescription = getString(
            R.string.filter_desc, summary.criticalCount, getString(R.string.severity_critical)
        )
    }

    private fun renderEmptyFilterMessage() {
        val filter = severityFilter ?: return
        val summary = lastSummary ?: return
        val count = when (filter) {
            ScanSeverity.OK -> summary.okCount
            ScanSeverity.WARNING -> summary.warningCount
            ScanSeverity.CRITICAL -> summary.criticalCount
            ScanSeverity.INFO -> 0
        }
        binding.emptyFilterText.visibility =
            if (count == 0) android.view.View.VISIBLE
            else android.view.View.GONE
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

    private fun checkForUpdate(silent: Boolean) {
        if (updating) return
        updating = true
        if (!silent) {
            Toast.makeText(this, R.string.update_checking, Toast.LENGTH_SHORT).show()
        }
        lifecycleScope.launch(Dispatchers.IO) {
            val result = UpdateChecker.check(applicationContext)
            runOnUiThread {
                updating = false
                when (result) {
                    is UpdateCheckResult.Available -> {
                        pendingUpdate = result.info
                        binding.btnUpdateBanner.visibility = android.view.View.VISIBLE
                        binding.btnUpdateBanner.text = getString(
                            R.string.update_banner, result.info.latestVersion
                        )
                        if (!silent) showUpdateDialog(result.info)
                    }
                    is UpdateCheckResult.UpToDate -> {
                        pendingUpdate = null
                        binding.btnUpdateBanner.visibility = android.view.View.GONE
                        if (!silent) {
                            Toast.makeText(
                                this@MainActivity,
                                getString(
                                    R.string.update_uptodate_detail,
                                    result.installed,
                                    result.latest
                                ),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                    is UpdateCheckResult.Failed -> {
                        if (!silent) {
                            Toast.makeText(
                                this@MainActivity,
                                getString(R.string.update_error) + ": " + result.reason,
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
            }
        }
    }

    private fun showUpdateDialog(info: UpdateInfo) {
        pendingUpdate = info
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
        if (!UpdateChecker.canInstallPackages(this)) {
            Toast.makeText(this, R.string.update_allow_unknown, Toast.LENGTH_LONG).show()
            UpdateChecker.requestInstallPermission(this)
            return
        }
        Toast.makeText(this, R.string.update_downloading, Toast.LENGTH_SHORT).show()
        binding.btnUpdateBanner.isEnabled = false
        binding.btnUpdateBanner.text = getString(R.string.update_downloading)
        lifecycleScope.launch(Dispatchers.IO) {
            UpdateChecker.downloadApk(
                applicationContext, info.downloadUrl
            ) { result ->
                runOnUiThread {
                    binding.btnUpdateBanner.isEnabled = true
                    binding.btnUpdateBanner.text = getString(
                        R.string.update_banner, info.latestVersion
                    )
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
