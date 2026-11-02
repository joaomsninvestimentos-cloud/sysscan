package com.sysscan.repair.diag.battery

import android.app.AppOpsManager
import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.PowerManager
import android.os.Process
import java.util.concurrent.TimeUnit

/**
 * Descobre **quais aplicativos consomem mais bateria** sem root e sem a permissão
 * privilegiada BATTERY_STATS.
 *
 * Fontes de dados:
 *  - [UsageStatsManager]: tempo em primeiro plano e último uso por pacote (PACKAGE_USAGE_STATS);
 *  - [UsageEvents]: número de sessões (quantas vezes o app foi aberto);
 *  - [NetworkStatsManager]: bytes trafegados por UID, separando rede móvel e Wi-Fi;
 *  - [PowerManager.isIgnoringBatteryOptimizations]: apps fora do Doze (gastam mais parados);
 *  - [BatteryLiveMeter]: corrente real do aparelho (mA) e autonomia estimada.
 *
 * O cruzamento desses sinais gera o peso de consumo em [BatteryScoring]. Tudo roda em
 * background thread — chame fora da main thread.
 */
class BatteryUsageAnalyzer(private val context: Context) {

    /**
     * @param windowHours janela de análise em horas (1, 6, 24...).
     * @param live leitura de energia já feita (para não medir duas vezes).
     * @param networkProbeLimit quantos apps recebem consulta individual de tráfego
     *        (cada consulta é uma chamada ao sistema; limitar mantém a varredura rápida).
     */
    fun analyze(
        windowHours: Int = 24,
        live: BatteryLiveReading? = null,
        networkProbeLimit: Int = 25
    ): BatteryUsageReport {
        val now = System.currentTimeMillis()
        val windowMs = TimeUnit.HOURS.toMillis(windowHours.toLong().coerceAtLeast(1L))
        val start = now - windowMs

        val reading = live ?: BatteryLiveMeter.read(context)
        BatteryDrainTracker.record(context, reading)
        val observedMahPerHour = BatteryDrainTracker.observedMahPerHour(context)

        val granted = hasUsageAccess(context)
        if (!granted) {
            return BatteryUsageReport(
                generatedAt = now,
                windowHours = windowHours,
                usageAccessGranted = false,
                apps = emptyList(),
                live = reading,
                observedMahPerHour = observedMahPerHour,
                windowMah = null,
                notes = listOf(
                    "Sem o acesso ao uso de aplicativos o Android não informa qual app está " +
                        "consumindo a bateria. Conceda em Configurações > Acesso especial > " +
                        "Acesso ao uso."
                )
            )
        }

        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return emptyReport(now, windowHours, reading, observedMahPerHour)

        val usage = queryForeground(usm, start, now)
        if (usage.isEmpty()) {
            return BatteryUsageReport(
                generatedAt = now,
                windowHours = windowHours,
                usageAccessGranted = true,
                apps = emptyList(),
                live = reading,
                observedMahPerHour = observedMahPerHour,
                windowMah = null,
                notes = listOf("Nenhum uso de aplicativos registrado na janela analisada.")
            )
        }

        val sessions = countSessions(usm, start, now)

        val candidates = usage.entries
            .filter { it.value.foregroundMs > 0L || it.value.lastUsed > 0L }
            .sortedByDescending { it.value.foregroundMs }
            .map { it.key }

        val probed = candidates.take(networkProbeLimit).toHashSet()

        val rows = mutableListOf<AppBatteryUsage>()
        for (packageName in candidates) {
            val info = applicationInfo(packageName) ?: continue
            val data = usage[packageName] ?: continue
            val sessionCount = sessions[packageName] ?: 0

            val (mobileBytes, wifiBytes) = if (probed.contains(packageName)) {
                networkBytes(info.uid, start, now)
            } else {
                0L to 0L
            }

            val isSystem = isSystemApp(info)
            val signals = BatteryScoring.Signals(
                foregroundMs = data.foregroundMs,
                mobileBytes = mobileBytes,
                wifiBytes = wifiBytes,
                sessions = sessionCount,
                isSystemApp = isSystem,
                lastUsedAt = data.lastUsed,
                now = now
            )
            val weight = BatteryScoring.weight(signals)
            if (weight <= 0.0 && mobileBytes == 0L && data.foregroundMs == 0L) continue

            rows.add(
                AppBatteryUsage(
                    packageName = packageName,
                    label = label(info, packageName),
                    isSystemApp = isSystem,
                    foregroundMs = data.foregroundMs,
                    sessions = sessionCount,
                    lastUsedAt = data.lastUsed,
                    mobileBytes = mobileBytes,
                    wifiBytes = wifiBytes,
                    weight = weight,
                    sharePercent = 0.0,
                    estimatedMah = null,
                    backgroundSuspect = BatteryScoring.isBackgroundSuspect(signals),
                    ignoresOptimizations = ignoresOptimizations(packageName)
                )
            )
        }

        if (rows.isEmpty()) {
            return BatteryUsageReport(
                generatedAt = now,
                windowHours = windowHours,
                usageAccessGranted = true,
                apps = emptyList(),
                live = reading,
                observedMahPerHour = observedMahPerHour,
                windowMah = null,
                notes = listOf("Nenhum consumo relevante registrado na janela analisada.")
            )
        }

        val shares = BatteryScoring.shares(rows.map { it.weight })
        val windowMah = observedMahPerHour?.times(windowHours.toDouble())
        val withShares = rows.mapIndexed { index, row ->
            val share = shares[index]
            row.copy(
                sharePercent = share,
                estimatedMah = windowMah?.let { it * share / 100.0 }
            )
        }.sortedWith(
            compareByDescending<AppBatteryUsage> { it.sharePercent }
                .thenByDescending { it.totalBytes }
                .thenByDescending { it.foregroundMs }
        )

        return BatteryUsageReport(
            generatedAt = now,
            windowHours = windowHours,
            usageAccessGranted = true,
            apps = withShares,
            live = reading,
            observedMahPerHour = observedMahPerHour,
            windowMah = windowMah,
            notes = buildNotes(reading, observedMahPerHour, probed.size, candidates.size)
        )
    }

    /** Analisa medindo a corrente real por alguns segundos (mais preciso, porém mais lento). */
    fun analyzeWithMeasurement(windowHours: Int = 24, seconds: Int = 2): BatteryUsageReport {
        val live = BatteryLiveMeter.measure(context, seconds)
        return analyze(windowHours = windowHours, live = live)
    }

    private fun emptyReport(
        now: Long,
        windowHours: Int,
        reading: BatteryLiveReading,
        observedMahPerHour: Double?
    ): BatteryUsageReport = BatteryUsageReport(
        generatedAt = now,
        windowHours = windowHours,
        usageAccessGranted = true,
        apps = emptyList(),
        live = reading,
        observedMahPerHour = observedMahPerHour,
        windowMah = null,
        notes = listOf("Não foi possível consultar as estatísticas de uso neste aparelho.")
    )

    private fun buildNotes(
        reading: BatteryLiveReading,
        observedMahPerHour: Double?,
        probed: Int,
        total: Int
    ): List<String> {
        val notes = mutableListOf<String>()
        if (!reading.hasDrain) {
            notes.add(
                "Este aparelho não informa a corrente instantânea (CURRENT_NOW), então o " +
                    "consumo em mA não aparece — o ranking por app continua válido."
            )
        }
        if (observedMahPerHour == null) {
            notes.add(
                "Abra esta tela de vez em quando: com o tempo o SysScan aprende o consumo real " +
                    "do aparelho (mAh/h) e passa a estimar os mAh de cada app."
            )
        }
        if (probed < total) {
            notes.add("Tráfego de rede consultado para os $probed apps mais usados de $total.")
        }
        return notes
    }

    internal data class ForegroundData(val foregroundMs: Long, val lastUsed: Long)

    private fun queryForeground(
        usm: UsageStatsManager,
        start: Long,
        end: Long
    ): Map<String, ForegroundData> {
        val grouped = HashMap<String, ForegroundData>()
        try {
            @Suppress("DEPRECATION")
            val stats = usm.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY, start, end
            ) ?: return grouped
            for (stat in stats) {
                val packageName = stat.packageName
                if (!packageName.isNullOrBlank() && packageName != "android") {
                    val previous = grouped[packageName]
                    val foreground = stat.totalTimeInForeground.coerceAtLeast(0L)
                    grouped[packageName] = ForegroundData(
                        foregroundMs = (previous?.foregroundMs ?: 0L) + foreground,
                        lastUsed = maxOf(previous?.lastUsed ?: 0L, stat.lastTimeUsed)
                    )
                }
            }
        } catch (_: Exception) {
            // sem permissão ou serviço indisponível: devolve o que já foi agrupado
        }
        return grouped
    }

    private fun countSessions(usm: UsageStatsManager, start: Long, end: Long): Map<String, Int> {
        val counts = HashMap<String, Int>()
        try {
            val events = usm.queryEvents(start, end)
            val event = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                val type = event.eventType
                val isResume = type == UsageEvents.Event.MOVE_TO_FOREGROUND ||
                    type == UsageEvents.Event.ACTIVITY_RESUMED
                val packageName = event.packageName
                if (isResume && !packageName.isNullOrBlank() && packageName != "android") {
                    counts[packageName] = (counts[packageName] ?: 0) + 1
                }
            }
        } catch (_: Exception) {
            // eventos indisponíveis: segue sem a contagem de sessões
        }
        return counts
    }

    /** Bytes (móvel, Wi-Fi) trafegados pelo UID na janela. Requer PACKAGE_USAGE_STATS. */
    private fun networkBytes(uid: Int, start: Long, end: Long): Pair<Long, Long> {
        val manager = context.getSystemService(Context.NETWORK_STATS_SERVICE)
            as? NetworkStatsManager ?: return 0L to 0L
        val mobile = safeUidBytes(manager, ConnectivityManager.TYPE_MOBILE, uid, start, end)
        val wifi = safeUidBytes(manager, ConnectivityManager.TYPE_WIFI, uid, start, end)
        return mobile to wifi
    }

    private fun safeUidBytes(
        manager: NetworkStatsManager,
        networkType: Int,
        uid: Int,
        start: Long,
        end: Long
    ): Long = try {
        val stats: NetworkStats = manager.queryDetailsForUid(networkType, null, start, end, uid)
        val bucket = NetworkStats.Bucket()
        var total = 0L
        while (stats.hasNextBucket()) {
            stats.getNextBucket(bucket)
            total += bucket.rxBytes + bucket.txBytes
        }
        stats.close()
        total
    } catch (_: Exception) {
        0L
    }

    private fun applicationInfo(packageName: String): ApplicationInfo? = try {
        context.packageManager.getApplicationInfo(packageName, 0)
    } catch (_: PackageManager.NameNotFoundException) {
        null
    } catch (_: Exception) {
        null
    }

    private fun label(info: ApplicationInfo, fallback: String): String = try {
        context.packageManager.getApplicationLabel(info).toString().ifBlank { fallback }
    } catch (_: Exception) {
        fallback
    }

    private fun isSystemApp(info: ApplicationInfo): Boolean {
        val flags = info.flags
        return flags and ApplicationInfo.FLAG_SYSTEM != 0 ||
            flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0
    }

    private fun ignoresOptimizations(packageName: String): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            ?: return false
        return try {
            powerManager.isIgnoringBatteryOptimizations(packageName)
        } catch (_: Exception) {
            false
        }
    }

    companion object {

        fun hasUsageAccess(context: Context): Boolean {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
                ?: return false
            return try {
                val mode = appOps.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.packageName
                )
                mode == AppOpsManager.MODE_ALLOWED
            } catch (_: Exception) {
                false
            }
        }
    }
}
