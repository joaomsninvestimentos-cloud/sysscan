package com.sysscan.repair.diag

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Process
import android.provider.Settings
import com.sysscan.repair.model.ScanCategory
import com.sysscan.repair.model.ScanCheck
import com.sysscan.repair.model.ScanCheckBuilder
import java.util.concurrent.TimeUnit

class UsageDiagnostic(private val context: Context) {

    fun check(): List<ScanCheck> {
        val results = mutableListOf<ScanCheck>()

        val hasPermission = hasUsageAccess(context)
        results.add(
            if (hasPermission) {
                ScanCheckBuilder.ok(
                    "usage_access", ScanCategory.APPS, "Acesso ao uso de aplicativos",
                    "Permissão de uso concedida. Dados de bateria por app disponíveis."
                )
            } else {
                ScanCheckBuilder.info(
                    "usage_access", ScanCategory.APPS, "Acesso ao uso de aplicativos",
                    "Conceda o acesso ao uso em Configurações > Acesso especial para medir o " +
                        "consumo de bateria por aplicativo.", "usage_grant"
                )
            }
        )

        if (!hasPermission) {
            results.add(ScanCheckBuilder.info(
                "usage_battery", ScanCategory.APPS, "Consumo de bateria por app",
                "Sem permissão de uso, não é possível medir o consumo por aplicativo."
            ))
            return results
        }

        results.add(checkBatteryByApp())
        return results
    }

    private fun checkBatteryByApp(): ScanCheck {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val end = System.currentTimeMillis()
        val start = end - TimeUnit.HOURS.toMillis(24)
        val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, end)

        val byPackage = mutableMapOf<String, Long>()
        for (stat in stats) {
            if (stat.totalTimeInForeground <= 0) continue
            val key = stat.packageName
            byPackage[key] = (byPackage[key] ?: 0L) + stat.totalTimeInForeground
        }

        val sorted = byPackage.entries.sortedByDescending { it.value }
        if (sorted.isEmpty()) {
            return ScanCheckBuilder.ok(
                "usage_battery", ScanCategory.APPS, "Consumo de bateria por app",
                "Nenhum uso de apps registrado nas últimas 24h."
            )
        }

        val heavyThreshold = TimeUnit.HOURS.toMillis(6)
        val heavy = sorted.filter { it.value > heavyThreshold }
        val topLabel = packageLabel(sorted.first().key)
        val topHours = sorted.first().value.toDouble() / TimeUnit.HOURS.toMillis(1)

        return when {
            heavy.isNotEmpty() -> ScanCheckBuilder.warning(
                "usage_battery", ScanCategory.APPS, "Uso intenso de bateria",
                "${heavy.size} app(s) em uso por mais de 6h no dia. O mais usado: $topLabel " +
                    "(${"%.1f".format(topHours)}h).", "usage_review"
            )
            else -> ScanCheckBuilder.ok(
                "usage_battery", ScanCategory.APPS, "Consumo de bateria por app",
                "App mais usado nas últimas 24h: $topLabel (${"%.1f".format(topHours)}h)."
            )
        }
    }

    private fun packageLabel(packageName: String): String = try {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    } catch (e: Exception) {
        packageName
    }

    companion object {
        fun hasUsageAccess(context: Context): Boolean {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
            return mode == AppOpsManager.MODE_ALLOWED
        }

        fun grantIntent(context: Context): Intent =
            Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
