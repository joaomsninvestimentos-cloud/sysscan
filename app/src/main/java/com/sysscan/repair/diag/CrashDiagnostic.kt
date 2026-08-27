package com.sysscan.repair.diag

import android.content.Context
import android.os.DropBoxManager
import com.sysscan.repair.model.ScanCategory
import com.sysscan.repair.model.ScanCheck
import com.sysscan.repair.model.ScanCheckBuilder
import java.util.concurrent.TimeUnit

class CrashDiagnostic(private val context: Context) {

    private val crashTags = arrayOf(
        "data_app_crash",
        "system_app_crash",
        "system_app_anr",
        "data_app_anr",
        "system_server_crash"
    )

    fun check(): List<ScanCheck> {
        val results = mutableListOf<ScanCheck>()
        val dropBox = context.getSystemService(Context.DROPBOX_SERVICE) as? DropBoxManager
        if (dropBox == null) {
            results.add(ScanCheckBuilder.info(
                "crash_available", ScanCategory.APPS, "Registro de falhas",
                "Serviço de registro de falhas indisponível."
            ))
            return results
        }

        val now = System.currentTimeMillis()
        val windowMs = TimeUnit.HOURS.toMillis(24)
        val events = mutableListOf<Pair<String, Long>>()

        for (tag in crashTags) {
            var entry = dropBox.getNextEntry(tag, now - windowMs)
            while (entry != null) {
                val e = entry
                events.add(tag to e.timeMillis)
                entry = dropBox.getNextEntry(tag, e.timeMillis + 1)
            }
        }

        val total = events.size
        val appCrashes = events.count { it.first.contains("crash") && !it.first.contains("server") }
        val anrs = events.count { it.first.contains("anr") }

        results.add(
            when {
                total == 0 -> ScanCheckBuilder.ok(
                    "crash_count", ScanCategory.APPS, "Estabilidade dos apps",
                    "Nenhuma falha ou travamento registrado nas últimas 24 horas."
                )
                total <= 2 -> ScanCheckBuilder.info(
                    "crash_count", ScanCategory.APPS, "Estabilidade dos apps",
                    "$total ocorrência(s) de falha/travamento nas últimas 24h ($appCrashes crash(es), $anrs ANR(s))."
                )
                else -> ScanCheckBuilder.warning(
                    "crash_count", ScanCategory.APPS, "Falhas recorrentes",
                    "$total ocorrência(s) de falha/travamento nas últimas 24h ($appCrashes crash(es), $anrs ANR(s)). " +
                        "Falhas repetidas indicam instabilidade.", "apps_stability_fix"
                )
            }
        )

        return results
    }
}
