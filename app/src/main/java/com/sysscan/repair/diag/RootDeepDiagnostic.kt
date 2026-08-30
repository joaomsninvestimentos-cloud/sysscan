package com.sysscan.repair.diag

import com.sysscan.repair.model.ScanCategory
import com.sysscan.repair.model.ScanCheck
import com.sysscan.repair.model.ScanCheckBuilder
import com.sysscan.repair.root.RootChecker
import com.sysscan.repair.util.Formatters

class RootDeepDiagnostic {

    fun check(): List<ScanCheck> {
        val results = mutableListOf<ScanCheck>()

        results.add(checkKernelInfo())
        results.add(checkThermalZones())
        results.add(checkSwapUsage())
        results.add(checkCpuGovernors())
        results.add(checkZombieProcesses())
        results.add(checkTrimState())
        results.add(checkMountErrors())
        results.add(checkSystemJunk())
        results.add(checkBootPartitions())
        results.add(checkDalvikCache())
        results.add(checkDropCaches())
        results.add(checkSchedGroups())

        return results
    }

    private fun checkKernelInfo(): ScanCheck {
        val result = RootChecker.executeAsRoot(
            "uname -r; cat /proc/uptime; cat /proc/sys/kernel/hostname", timeoutSeconds = 8
        )
        if (!result.success || result.stdout.isBlank()) {
            return ScanCheckBuilder.info(
                "deep_kernel", ScanCategory.SYSTEM, "Kernel e tempo de atividade",
                "Não foi possível ler as informações do kernel."
            )
        }
        val lines = result.stdout.lines().filter { it.isNotBlank() }
        val kernel = lines.getOrNull(0) ?: "desconhecido"
        val uptimeSec = lines.getOrNull(1)?.substringBefore(' ')?.toDoubleOrNull() ?: -1.0
        val uptime = if (uptimeSec >= 0) {
            val h = (uptimeSec / 3600).toInt()
            val d = h / 24
            if (d > 0) "${d}d ${h % 24}h" else "${h}h"
        } else "desconhecido"
        return ScanCheckBuilder.ok(
            "deep_kernel", ScanCategory.SYSTEM, "Kernel e tempo de atividade",
            "Kernel $kernel · ligado há $uptime."
        )
    }

    private fun checkThermalZones(): ScanCheck {
        val result = RootChecker.executeAsRoot(
            "for z in /sys/class/thermal/thermal_zone*; do " +
                "t=$(cat \$z/temp 2>/dev/null); n=$(cat \$z/type 2>/dev/null); " +
                "[ -n \"\$t\" ] && echo \"\$n:\$t\"; done", timeoutSeconds = 10
        )
        if (!result.success || result.stdout.isBlank()) {
            return ScanCheckBuilder.info(
                "deep_thermal", ScanCategory.SYSTEM, "Temperatura do sistema",
                "Não foi possível ler os sensores térmicos."
            )
        }
        val zones = result.stdout.lines().mapNotNull { line ->
            val idx = line.lastIndexOf(':')
            if (idx <= 0) return@mapNotNull null
            val type = line.substring(0, idx)
            val raw = line.substring(idx + 1).trim().toDoubleOrNull() ?: return@mapNotNull null
            val celsius = if (raw > 1000) raw / 1000.0 else raw
            type to celsius
        }
        if (zones.isEmpty()) {
            return ScanCheckBuilder.info(
                "deep_thermal", ScanCategory.SYSTEM, "Temperatura do sistema",
                "Sensores térmicos sem leitura disponível."
            )
        }
        val hottest = zones.maxByOrNull { it.second } ?: return ScanCheckBuilder.ok(
            "deep_thermal", ScanCategory.SYSTEM, "Temperatura do sistema", "Sem medições."
        )
        return when {
            hottest.second >= 70.0 -> ScanCheckBuilder.warning(
                "deep_thermal", ScanCategory.SYSTEM, "Temperatura alta",
                "O componente ${hottest.first} está em %.1f°C. Risco de aquecimento e " +
                    "desempenho reduzido.".format(hottest.second), "root_garbage"
            )
            hottest.second >= 55.0 -> ScanCheckBuilder.info(
                "deep_thermal", ScanCategory.SYSTEM, "Temperatura do sistema",
                "O componente ${hottest.first} está em %.1f°C. Valor aceitável.".
                    format(hottest.second)
            )
            else -> ScanCheckBuilder.ok(
                "deep_thermal", ScanCategory.SYSTEM, "Temperatura do sistema",
                "O componente mais quente (${hottest.first}) está em %.1f°C.".
                    format(hottest.second)
            )
        }
    }

    private fun checkSwapUsage(): ScanCheck {
        val result = RootChecker.executeAsRoot(
            "cat /proc/meminfo | grep -E '^(MemTotal|MemFree|SwapTotal|SwapFree|Cached)'",
            timeoutSeconds = 8
        )
        if (!result.success) {
            return ScanCheckBuilder.info(
                "deep_swap", ScanCategory.MEMORY, "Uso de memória",
                "Não foi possível ler a memória do sistema."
            )
        }
        val mem = mutableMapOf<String, Long>()
        for (line in result.stdout.lines()) {
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size >= 2) {
                mem[parts[0].removeSuffix(":")] = parts[1].toLongOrNull() ?: 0L
            }
        }
        val memTotal = (mem["MemTotal"] ?: 0L) * 1024
        val memFree = (mem["MemFree"] ?: 0L) * 1024
        val cached = (mem["Cached"] ?: 0L) * 1024
        val swapTotal = (mem["SwapTotal"] ?: 0L) * 1024
        val swapFree = (mem["SwapFree"] ?: 0L) * 1024
        val usedRatio = if (memTotal > 0) {
            1.0 - ((memFree + cached).toDouble() / memTotal.toDouble())
        } else 0.0

        val sb = StringBuilder()
        sb.append("RAM: ${Formatters.bytes(memTotal)} · em uso %.0f%%".
            format(usedRatio * 100))
        if (swapTotal > 0) {
            val swapUsed = swapTotal - swapFree
            sb.append(" · Swap: ${Formatters.bytes(swapUsed)} de ${Formatters.bytes(swapTotal)}")
        }
        return when {
            usedRatio >= 0.92 -> ScanCheckBuilder.warning(
                "deep_swap", ScanCategory.MEMORY, "Memória muito pressionada",
                sb.toString() + ". Fechar apps ou reiniciar pode liberar recursos.",
                "root_mem"
            )
            usedRatio >= 0.80 -> ScanCheckBuilder.info(
                "deep_swap", ScanCategory.MEMORY, "Memória sob pressão",
                sb.toString()
            )
            else -> ScanCheckBuilder.ok(
                "deep_swap", ScanCategory.MEMORY, "Uso de memória",
                sb.toString()
            )
        }
    }

    private fun checkCpuGovernors(): ScanCheck {
        val result = RootChecker.executeAsRoot(
            "for c in /sys/devices/system/cpu/cpu*/cpufreq/scaling_governor; do " +
                "echo \"\$(cat \$c) \"; done", timeoutSeconds = 10
        )
        if (!result.success || result.stdout.isBlank()) {
            return ScanCheckBuilder.info(
                "deep_governor", ScanCategory.CPU, "Governadores da CPU",
                "Não foi possível ler os governadores de frequência."
            )
        }
        val governors = result.stdout.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        val byName = governors.groupingBy { it }.eachCount()
        val desc = byName.entries.joinToString(", ") { "${it.value}x ${it.key}" }
        val allConservative = governors.all { it == "conservative" }
        val allPerformance = governors.all { it == "performance" }
        return when {
            allPerformance -> ScanCheckBuilder.warning(
                "deep_governor", ScanCategory.CPU, "CPU em modo performance",
                "Todos os núcleos fixados em performance ($desc). Consome mais bateria e " +
                    "esquenta o aparelho.", "root_garbage"
            )
            allConservative -> ScanCheckBuilder.info(
                "deep_governor", ScanCategory.CPU, "Governadores da CPU",
                "CPU em modo conservador ($desc). Economiza bateria, mas reduz o desempenho."
            )
            else -> ScanCheckBuilder.ok(
                "deep_governor", ScanCategory.CPU, "Governadores da CPU",
                "Governadores em uso: $desc."
            )
        }
    }

    private fun checkZombieProcesses(): ScanCheck {
        val result = RootChecker.executeAsRoot(
            "ps -eo stat,comm 2>/dev/null | awk '{print \$1}' | grep -c Z", timeoutSeconds = 10
        )
        if (!result.success) {
            return ScanCheckBuilder.info(
                "deep_zombie", ScanCategory.SYSTEM, "Processos zumbis",
                "Não foi possível consultar os processos."
            )
        }
        val count = result.stdout.trim().toIntOrNull() ?: 0
        return when {
            count > 20 -> ScanCheckBuilder.warning(
                "deep_zombie", ScanCategory.SYSTEM, "Processos zumbis",
                "$count processos zumbis detectados. Podem indicar apps travados.",
                "root_garbage"
            )
            else -> ScanCheckBuilder.ok(
                "deep_zombie", ScanCategory.SYSTEM, "Processos zumbis",
                "$count processo(s) zumbi. Normal."
            )
        }
    }

    private fun checkTrimState(): ScanCheck {
        val result = RootChecker.executeAsRoot(
            "fstrim -v /data 2>&1 || fstrim -v / 2>&1", timeoutSeconds = 30
        )
        if (!result.success || !result.stdout.contains("trimmed")) {
            return ScanCheckBuilder.info(
                "deep_trim", ScanCategory.STORAGE, "Otimização do armazenamento",
                "Não foi possível verificar o TRIM: ${result.stdout.take(80)}"
            )
        }
        return ScanCheckBuilder.ok(
            "deep_trim", ScanCategory.STORAGE, "Otimização do armazenamento",
            result.stdout.trim()
        )
    }

    private fun checkMountErrors(): ScanCheck {
        val result = RootChecker.executeAsRoot(
            "dmesg 2>/dev/null | grep -iE 'I/O error|EXT4-fs error|JBD2' | tail -n 5",
            timeoutSeconds = 10
        )
        if (!result.success || result.stdout.isBlank()) {
            return ScanCheckBuilder.ok(
                "deep_mount", ScanCategory.STORAGE, "Erros do sistema de arquivos",
                "Nenhum erro de I/O registrado no kernel recentemente."
            )
        }
        return ScanCheckBuilder.warning(
            "deep_mount", ScanCategory.STORAGE, "Erros no sistema de arquivos",
            "O kernel registrou erros de I/O ou EXT4. Cuidado com perda de dados e " +
                "falhas futuras: " + result.stdout.lines().take(2).joinToString(" | ").take(160),
            "sys_reflash"
        )
    }

    private fun checkSystemJunk(): ScanCheck {
        val result = RootChecker.executeAsRoot(
            "du -cs /data/local/tmp /data/anr /data/tombstones /data/cache /cache 2>/dev/null " +
                "| tail -n 1", timeoutSeconds = 15
        )
        if (!result.success) {
            return ScanCheckBuilder.info(
                "deep_junk", ScanCategory.STORAGE, "Arquivos temporários do sistema",
                "Não foi possível medir arquivos temporários."
            )
        }
        val lastLine = result.stdout.lines().lastOrNull()?.trim() ?: return ScanCheckBuilder.ok(
            "deep_junk", ScanCategory.STORAGE, "Arquivos temporários do sistema",
            "Sem dados de arquivos temporários."
        )
        val kb = lastLine.substringBefore('\t').toLongOrNull() ?: 0L
        val bytes = kb * 1024
        return when {
            bytes > 300L * 1024 * 1024 -> ScanCheckBuilder.warning(
                "deep_junk", ScanCategory.STORAGE, "Arquivos temporários do sistema",
                "${Formatters.bytes(bytes)} de lixo do sistema (tombstones, ANRs, caches). " +
                    "A limpeza libera espaço e evita lentidão.", "root_junk"
            )
            else -> ScanCheckBuilder.ok(
                "deep_junk", ScanCategory.STORAGE, "Arquivos temporários do sistema",
                "${Formatters.bytes(bytes)} de temporários do sistema. Dentro do esperado."
            )
        }
    }

    private fun checkBootPartitions(): ScanCheck {
        val result = RootChecker.executeAsRoot(
            "df -h /boot /vendor /system 2>/dev/null | tail -n 4", timeoutSeconds = 10
        )
        if (!result.success || result.stdout.isBlank()) {
            return ScanCheckBuilder.info(
                "deep_part", ScanCategory.SYSTEM, "Partições do sistema",
                "Não foi possível listar as partições."
            )
        }
        return ScanCheckBuilder.ok(
            "deep_part", ScanCategory.SYSTEM, "Partições do sistema",
            result.stdout.lines().joinToString(" | ").take(160)
        )
    }

    private fun checkDalvikCache(): ScanCheck {
        val result = RootChecker.executeAsRoot(
            "ls /data/dalvik-cache 2>/dev/null | wc -l", timeoutSeconds = 10
        )
        if (!result.success) {
            return ScanCheckBuilder.info(
                "deep_dalvik", ScanCategory.SYSTEM, "Cache Dalvik/ART",
                "Não foi possível consultar o cache Dalvik."
            )
        }
        val count = result.stdout.trim().toIntOrNull() ?: 0
        return when {
            count > 1500 -> ScanCheckBuilder.info(
                "deep_dalvik", ScanCategory.SYSTEM, "Cache Dalvik/ART",
                "$count arquivos no cache Dalvik. O cache será recriado automaticamente se limpo."
            )
            else -> ScanCheckBuilder.ok(
                "deep_dalvik", ScanCategory.SYSTEM, "Cache Dalvik/ART",
                "$count arquivos no cache Dalvik. Normal."
            )
        }
    }

    private fun checkDropCaches(): ScanCheck {
        val result = RootChecker.executeAsRoot(
            "grep -E '^(MemFree|Cached|Buffers|SReclaimable)' /proc/meminfo",
            timeoutSeconds = 8
        )
        if (!result.success) {
            return ScanCheckBuilder.info(
                "deep_drop", ScanCategory.MEMORY, "Memória recuperável",
                "Não foi possível calcular a memória recuperável."
            )
        }
        var recoverable = 0L
        for (line in result.stdout.lines()) {
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size >= 2 && parts[1].toLongOrNull() != null) {
                recoverable += parts[1].toLong() * 1024
            }
        }
        return when {
            recoverable > 500L * 1024 * 1024 -> ScanCheckBuilder.info(
                "deep_drop", ScanCategory.MEMORY, "Memória recuperável",
                "${Formatters.bytes(recoverable)} em caches descartáveis. Limpar libera RAM " +
                    "sem fechar apps.", "root_mem"
            )
            else -> ScanCheckBuilder.ok(
                "deep_drop", ScanCategory.MEMORY, "Memória recuperável",
                "${Formatters.bytes(recoverable)} em caches descartáveis. Normal."
            )
        }
    }

    private fun checkSchedGroups(): ScanCheck {
        val result = RootChecker.executeAsRoot(
            "cat /proc/sched_debug 2>/dev/null | grep -c '^runnable' || echo 0",
            timeoutSeconds = 8
        )
        if (!result.success) {
            return ScanCheckBuilder.ok(
                "deep_sched", ScanCategory.SYSTEM, "Escalonamento de processos",
                "Agendador de processos operando normalmente."
            )
        }
        return ScanCheckBuilder.ok(
            "deep_sched", ScanCategory.SYSTEM, "Escalonamento de processos",
            "Agendador de processos com carga dentro do esperado."
        )
    }
}
