package com.sysscan.repair.diag

import android.app.ActivityManager
import android.content.Context
import com.sysscan.repair.model.ScanCategory
import com.sysscan.repair.model.ScanCheck
import com.sysscan.repair.model.ScanCheckBuilder
import com.sysscan.repair.util.Formatters

class MemoryDiagnostic(private val context: Context) {

    private val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

    fun check(): List<ScanCheck> {
        val results = mutableListOf<ScanCheck>()
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)

        val total = memoryInfo.totalMem
        val avail = memoryInfo.availMem
        val availRatio = if (total > 0) avail.toDouble() / total.toDouble() else 0.0

        results.add(
            when {
                memoryInfo.lowMemory -> ScanCheckBuilder.critical(
                    "memory_low", ScanCategory.MEMORY, "Memória do sistema em escassez",
                    "O sistema está operando com pouca memória, o que causa travamentos e fechamento de apps. " +
                        "Disponível: ${Formatters.bytes(avail)} de ${Formatters.bytes(total)}.",
                    "memory_optimize"
                )
                availRatio < 0.15 -> ScanCheckBuilder.warning(
                    "memory_low", ScanCategory.MEMORY, "Pouca memória disponível",
                    "Apenas ${Formatters.bytes(avail)} disponível (${(availRatio * 100).toInt()}% " +
                        "de ${Formatters.bytes(total)}). Feche apps em segundo plano.", "memory_optimize"
                )
                else -> ScanCheckBuilder.ok(
                    "memory_low", ScanCategory.MEMORY, "Memória do sistema",
                    "${Formatters.bytes(avail)} disponível de ${Formatters.bytes(total)}."
                )
            }
        )

        val pss = runningAppPss()
        results.add(
            if (pss > total * 0.85) {
                ScanCheckBuilder.warning(
                    "memory_pressure", ScanCategory.MEMORY, "Pressão de memória alta",
                    "Apps em execução consomem grande parte da memória. Otimizar pode melhorar a estabilidade.",
                    "memory_optimize"
                )
            } else {
                ScanCheckBuilder.ok(
                    "memory_pressure", ScanCategory.MEMORY, "Pressão de memória",
                    "Uso de memória pelos processos dentro do esperado."
                )
            }
        )

        results.add(checkHeavyProcesses())

        return results
    }

    private fun checkHeavyProcesses(): ScanCheck {
        val procs = activityManager.runningAppProcesses ?: emptyList()
        if (procs.isEmpty()) {
            return ScanCheckBuilder.ok(
                "memory_heavy", ScanCategory.MEMORY, "Processos em execução",
                "Nenhum processo ativo detectado."
            )
        }
        val pids = procs.map { it.pid }.toIntArray()
        val infos = activityManager.getProcessMemoryInfo(pids) ?: return ScanCheckBuilder.ok(
            "memory_heavy", ScanCategory.MEMORY, "Processos em execução",
            "${procs.size} processos ativos."
        )
        val heavyThreshold = 300L * 1024 * 1024
        val heavy = procs.mapIndexedNotNull { index, p ->
            val pss = infos[index].totalPss.toLong() * 1024
            if (pss > heavyThreshold) p.processName to pss else null
        }.sortedByDescending { it.second }

        return when {
            heavy.isEmpty() -> ScanCheckBuilder.ok(
                "memory_heavy", ScanCategory.MEMORY, "Processos em execução",
                "${procs.size} processos ativos, nenhum consumindo memória excessiva."
            )
            else -> ScanCheckBuilder.warning(
                "memory_heavy", ScanCategory.MEMORY, "Processos consumindo muita memória",
                "${heavy.size} processo(s) acima de 300 MB. Maior consumidor: " +
                    "${heavy.first().first} (${Formatters.bytes(heavy.first().second)}).",
                "memory_optimize"
            )
        }
    }

    private fun runningAppPss(): Long {
        val pids = (activityManager.runningAppProcesses ?: emptyList()).map { it.pid }.toIntArray()
        if (pids.isEmpty()) return 0L
        val infos = activityManager.getProcessMemoryInfo(pids) ?: return 0L
        return infos.sumOf { it.totalPss.toLong() * 1024 }
    }
}
