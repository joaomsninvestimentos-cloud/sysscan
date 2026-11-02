package com.sysscan.repair.battery

import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.sysscan.repair.R
import com.sysscan.repair.databinding.DialogCallModeBinding
import com.sysscan.repair.diag.battery.BatteryLiveMeter
import com.sysscan.repair.diag.battery.BatteryUsageReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Controla o diálogo "Modo chamada": consumo dos apps de chamada, checklist e atalhos
 * (brilho, rede, restrição de bateria e medição na hora).
 */
class CallModeController(
    private val activity: AppCompatActivity,
    private val reportProvider: () -> BatteryUsageReport?
) {

    private var live = reportProvider()?.live
    private var apps: List<CallModeHelper.CallApp> = emptyList()
    private var measuring = false

    fun show() {
        val binding = DialogCallModeBinding.inflate(activity.layoutInflater)
        apps = CallModeHelper.callApps(activity, reportProvider())
        live = reportProvider()?.live

        val dialog = AlertDialog.Builder(activity)
            .setView(binding.root)
            .create()

        render(binding)

        binding.btnCallClose.setOnClickListener { dialog.dismiss() }
        binding.btnNetwork.setOnClickListener {
            start(CallModeHelper.networkSettingsIntent(), R.string.battery_open_failed)
        }
        binding.btnRestrictCall.setOnClickListener {
            val target = CallModeHelper.activeCallApps(apps).firstOrNull() ?: apps.firstOrNull()
            if (target == null) {
                Toast.makeText(activity, R.string.call_mode_no_app, Toast.LENGTH_SHORT).show()
            } else {
                start(
                    CallModeHelper.appBatterySettingsIntent(target.packageName),
                    R.string.battery_open_failed
                )
            }
        }
        binding.btnBrightness.setOnClickListener { toggleBrightness(binding) }
        binding.btnCallMeasure.setOnClickListener { measure(binding) }

        dialog.show()
    }

    private fun render(binding: DialogCallModeBinding) {
        val reading = live
        binding.callLive.text = if (measuring) {
            activity.getString(R.string.battery_measuring)
        } else {
            when {
                reading == null -> activity.getString(R.string.call_mode_live_unavailable)
                reading.charging -> activity.getString(R.string.call_mode_live_charging)
                !reading.hasDrain -> activity.getString(R.string.call_mode_live_unavailable)
                else -> activity.getString(
                    R.string.call_mode_live, reading.drainLabel(), reading.autonomyLabel()
                )
            }
        }

        binding.callApps.text = buildAppsText()
        binding.callTips.text = CallModeHelper.tips(reading ?: return, apps) +
            "\n\n" + activity.getString(R.string.call_mode_scan_hint)

        binding.btnBrightness.text = activity.getString(
            if (CallModeHelper.hasSavedBrightness(activity)) {
                R.string.call_mode_brightness_restore
            } else {
                R.string.call_mode_brightness
            }
        )
        binding.btnCallMeasure.isEnabled = !measuring
    }

    private fun buildAppsText(): CharSequence {
        val sb = StringBuilder()
        sb.append(activity.getString(R.string.call_mode_apps_title)).append("\n")
        if (apps.isEmpty()) {
            sb.append(activity.getString(R.string.call_mode_apps_empty))
            return sb
        }
        apps.take(6).forEach { app ->
            sb.append(
                activity.getString(R.string.call_mode_app_line, app.label, app.detailLabel)
            ).append("\n")
        }
        val share = CallModeHelper.callSharePercent(apps)
        if (share > 0.5) {
            sb.append("\n")
            sb.append(
                activity.getString(
                    R.string.call_mode_share_summary,
                    String.format(Locale.US, "%.0f", share)
                )
            )
        }
        return sb
    }

    private fun toggleBrightness(binding: DialogCallModeBinding) {
        if (!CallModeHelper.canWriteSettings(activity)) {
            Toast.makeText(
                activity, R.string.call_mode_brightness_no_permission, Toast.LENGTH_LONG
            ).show()
            start(CallModeHelper.writeSettingsIntent(activity), R.string.battery_open_failed)
            return
        }
        val restored = CallModeHelper.hasSavedBrightness(activity) &&
            CallModeHelper.restoreBrightness(activity)
        if (restored) {
            Toast.makeText(
                activity, R.string.call_mode_brightness_restored, Toast.LENGTH_SHORT
            ).show()
        } else {
            val lowered = CallModeHelper.lowerBrightness(activity)
            Toast.makeText(
                activity,
                if (lowered) R.string.call_mode_brightness_done
                else R.string.call_mode_brightness_no_permission,
                Toast.LENGTH_LONG
            ).show()
        }
        render(binding)
    }

    private fun measure(binding: DialogCallModeBinding) {
        if (measuring) return
        measuring = true
        render(binding)
        activity.lifecycleScope.launch {
            val reading = withContext(Dispatchers.IO) {
                runCatching { BatteryLiveMeter.measure(activity, 3) }.getOrNull()
            }
            measuring = false
            if (reading != null) live = reading
            render(binding)
        }
    }

    private fun start(intent: android.content.Intent, errorMessage: Int) {
        try {
            activity.startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(activity, errorMessage, Toast.LENGTH_LONG).show()
        }
    }

}
