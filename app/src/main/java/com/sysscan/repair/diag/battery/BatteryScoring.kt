package com.sysscan.repair.diag.battery

/**
 * Cálculo puro do "peso de consumo" de um app.
 *
 * O Android não entrega, para apps comuns, o número de mAh por aplicativo (isso exige a
 * permissão privilegiada BATTERY_STATS / dumpsys batterystats). O que é acessível com a
 * permissão de uso ([android.app.usage.UsageStatsManager] / PACKAGE_USAGE_STATS) e com o
 * [android.app.usage.NetworkStatsManager] é:
 *
 *  - tempo em primeiro plano (a tela ligada com o app na frente = maior gasto);
 *  - quantidade de sessões (abrir e fechar apps custa CPU/GPU/rádio);
 *  - tráfego de dados por rede móvel (o rádio é um dos maiores consumidores do aparelho);
 *  - tráfego por Wi-Fi (mais econômico que o móvel);
 *  - atividade em segundo plano (tráfego alto com quase nenhum tempo de tela — considerando
 *    dados móveis e Wi-Fi, porque em alguns aparelhos o Android 10+ bloqueia a consulta
 *    móvel por UID sem a permissão de telefonia);
 *  - se o app é de sistema e se foi usado recentemente.
 *
 * A partir desses sinais calculamos um peso de 0 a 100 e, dividindo pelo peso total do
 * aparelho, a fatia (%) de cada app no consumo. Quando existe medição real do aparelho
 * (mAh/h observados), a fatia é convertida em mAh estimados.
 */
object BatteryScoring {

    const val MAX_WEIGHT = 100.0

    private const val HOUR_MS = 3_600_000.0
    private const val MB = 1024.0 * 1024.0

    /** Fatores de conversão dos sinais em pontos de peso. */
    private const val FOREGROUND_POINTS_PER_HOUR = 7.5
    private const val FOREGROUND_POINTS_MAX = 45.0
    private const val MOBILE_POINTS_PER_MB = 1.2
    private const val MOBILE_POINTS_MAX = 25.0
    private const val WIFI_POINTS_PER_MB = 0.1
    private const val WIFI_POINTS_MAX = 8.0
    private const val SESSION_POINTS = 0.6
    private const val SESSION_POINTS_MAX = 8.0
    private const val BACKGROUND_MOBILE_POINTS_PER_MB = 0.8
    private const val BACKGROUND_WIFI_POINTS_PER_MB = 0.25
    private const val BACKGROUND_POINTS_MAX = 10.0

    /** Tempo de tela abaixo disso é tratado como "quase não usado na frente". */
    private const val BACKGROUND_FOREGROUND_LIMIT_MS = 15 * 60 * 1000L

    /** Abaixo disso o tráfego (móvel + Wi-Fi) não justifica o rótulo de segundo plano. */
    private const val BACKGROUND_TRAFFIC_MIN_BYTES = 2L * 1024 * 1024

    /** Acima disso o app não é considerado "em uso recente". */
    private const val STALE_HOURS = 12.0

    data class Signals(
        val foregroundMs: Long,
        val mobileBytes: Long,
        val wifiBytes: Long,
        val sessions: Int,
        val isSystemApp: Boolean,
        val lastUsedAt: Long,
        val now: Long
    )

    fun weight(signals: Signals): Double {
        val foregroundHours = signals.foregroundMs / HOUR_MS
        val mobileMb = signals.mobileBytes / MB
        val wifiMb = signals.wifiBytes / MB

        val foregroundPoints =
            (foregroundHours * FOREGROUND_POINTS_PER_HOUR).coerceAtMost(FOREGROUND_POINTS_MAX)
        val mobilePoints = (mobileMb * MOBILE_POINTS_PER_MB).coerceAtMost(MOBILE_POINTS_MAX)
        val wifiPoints = (wifiMb * WIFI_POINTS_PER_MB).coerceAtMost(WIFI_POINTS_MAX)
        val sessionPoints = (signals.sessions * SESSION_POINTS).coerceAtMost(SESSION_POINTS_MAX)

        val backgroundPoints =
            if (signals.foregroundMs < BACKGROUND_FOREGROUND_LIMIT_MS &&
                signals.mobileBytes + signals.wifiBytes > BACKGROUND_TRAFFIC_MIN_BYTES
            ) {
                val points = mobileMb * BACKGROUND_MOBILE_POINTS_PER_MB +
                    wifiMb * BACKGROUND_WIFI_POINTS_PER_MB
                points.coerceAtMost(BACKGROUND_POINTS_MAX)
            } else {
                0.0
            }

        val systemFactor = if (signals.isSystemApp) 0.6 else 1.0

        val idleHours = if (signals.lastUsedAt > 0) {
            (signals.now - signals.lastUsedAt).coerceAtLeast(0L) / HOUR_MS
        } else {
            0.0
        }
        val recencyFactor = if (idleHours > STALE_HOURS) 0.6 else 1.0

        val raw = (foregroundPoints + mobilePoints + wifiPoints + sessionPoints + backgroundPoints) *
            systemFactor * recencyFactor
        return raw.coerceIn(0.0, MAX_WEIGHT)
    }

    /** Marca apps que provavelmente gastam bateria fora da tela. */
    fun isBackgroundSuspect(signals: Signals): Boolean =
        signals.mobileBytes + signals.wifiBytes >= BACKGROUND_TRAFFIC_MIN_BYTES &&
            signals.foregroundMs < BACKGROUND_FOREGROUND_LIMIT_MS

    /**
     * Converte pesos em fatias percentuais do consumo. Devolve lista na mesma ordem,
     * com 0 quando o total é zero.
     */
    fun shares(weights: List<Double>): List<Double> {
        val total = weights.sum()
        if (total <= 0.0) return weights.map { 0.0 }
        return weights.map { (it / total) * 100.0 }
    }

    /** Rótulo qualitativo do peso. */
    fun levelLabel(weight: Double): String = when {
        weight >= 60.0 -> "alta"
        weight >= 35.0 -> "moderada"
        weight >= 15.0 -> "leve"
        else -> "mínima"
    }

    /** Cor sugerida (recurso) para o peso — usada pela interface. */
    fun levelColorRes(weight: Double): Int = when {
        weight >= 60.0 -> com.sysscan.repair.R.color.crit_red
        weight >= 35.0 -> com.sysscan.repair.R.color.warn_amber
        weight >= 15.0 -> com.sysscan.repair.R.color.info_blue
        else -> com.sysscan.repair.R.color.ok_green
    }
}
