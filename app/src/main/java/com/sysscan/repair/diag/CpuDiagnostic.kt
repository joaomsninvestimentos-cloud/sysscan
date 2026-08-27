package com.sysscan.repair.diag

import com.sysscan.repair.model.ScanCategory
import com.sysscan.repair.model.ScanCheck
import com.sysscan.repair.model.ScanCheckBuilder
import java.io.File
import java.util.concurrent.TimeUnit

class CpuDiagnostic {

    fun check(): List<ScanCheck> {
        val results = mutableListOf<ScanCheck>()

        val first = readCpuTimes()
        Thread.sleep(TimeUnit.MILLISECONDS.toMillis(500))
        val second = readCpuTimes()

        val usage = computeUsage(first, second)
        val cores = Runtime.getRuntime().availableProcessors()

        results.add(
            when {
                usage > 85.0 -> ScanCheckBuilder.warning(
                    "cpu_usage", ScanCategory.CPU, "Uso de CPU muito alto",
                    "CPU em ${"%.0f".format(usage)}% por todos os núcleos. Pode indicar processo " +
                        "pesado ou trava em segundo plano.", "cpu_optimize"
                )
                usage > 60.0 -> ScanCheckBuilder.info(
                    "cpu_usage", ScanCategory.CPU, "Uso de CPU elevado",
                    "CPU em ${"%.0f".format(usage)}%. Carga normal, mas elevada."
                )
                else -> ScanCheckBuilder.ok(
                    "cpu_usage", ScanCategory.CPU, "Uso de CPU",
                    "CPU em ${"%.0f".format(usage)}% (média entre amostras)."
                )
            }
        )

        results.add(
            if (cores > 0) {
                ScanCheckBuilder.ok(
                    "cpu_cores", ScanCategory.CPU, "Núcleos disponíveis",
                    "$cores núcleos lógicos disponíveis para o sistema."
                )
            } else {
                ScanCheckBuilder.info(
                    "cpu_cores", ScanCategory.CPU, "Núcleos disponíveis",
                    "Não foi possível identificar a quantidade de núcleos."
                )
            }
        )

        return results
    }

    private data class CpuTimes(val idle: Long, val total: Long)

    private fun readCpuTimes(): CpuTimes? {
        return try {
            val line = File("/proc/stat").readLines().firstOrNull { it.startsWith("cpu ") }
                ?: return null
            val parts = line.split("\\s+".toRegex()).drop(1).mapNotNull { it.toLongOrNull() }
            if (parts.isEmpty()) return null
            val idle = parts.getOrElse(3) { 0L } + parts.getOrElse(4) { 0L }
            CpuTimes(idle, parts.sum())
        } catch (e: Exception) {
            null
        }
    }

    private fun computeUsage(first: CpuTimes?, second: CpuTimes?): Double {
        if (first == null || second == null) return -1.0
        val totalDelta = second.total - first.total
        val idleDelta = second.idle - first.idle
        if (totalDelta <= 0) return 0.0
        return (totalDelta - idleDelta) * 100.0 / totalDelta
    }
}
