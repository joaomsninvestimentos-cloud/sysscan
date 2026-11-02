package com.sysscan.repair.diag.battery

import com.sysscan.repair.util.Formatters

/**
 * Leitura instantânea do consumo de energia do aparelho inteiro.
 * Vem do [android.os.BatteryManager] (propriedade CURRENT_NOW / CHARGE_COUNTER).
 */
data class BatteryLiveReading(
    /** Corrente de descarga em mA (null quando o aparelho não expõe a propriedade). */
    val drainMa: Double? = null,
    /** Nível da bateria em % (0 a 100). */
    val levelPercent: Int = -1,
    /** Se está conectado ao carregador. */
    val charging: Boolean = false,
    /** Se algum carregador está plugado (AC/USB/Wireless). */
    val plugged: Boolean = false,
    /** Temperatura em °C. */
    val temperatureC: Double? = null,
    /** Carga restante em µAh, quando o aparelho informa. */
    val chargeCounterUah: Long? = null,
    /** Autonomia estimada em horas com a corrente atual. */
    val autonomyHours: Double? = null,
    val readAt: Long = System.currentTimeMillis(),
    /** Quantas amostras foram usadas na média (1 = leitura instantânea). */
    val samples: Int = 1
) {
    val hasDrain: Boolean get() = drainMa != null && drainMa > 0

    fun drainLabel(): String = drainMa?.let { String.format(java.util.Locale.US, "%.0f mA", it) } ?: "—"

    fun autonomyLabel(): String = autonomyHours?.let { Formatters.duration((it * 3_600_000).toLong()) } ?: "—"

    fun temperatureLabel(): String = temperatureC?.let { Formatters.celsius((it * 10).toInt()) } ?: "—"

    fun levelLabel(): String = if (levelPercent >= 0) "$levelPercent%" else "—"
}

/** Um aplicativo no ranking de consumo. */
data class AppBatteryUsage(
    val packageName: String,
    val label: String,
    /** App de sistema (peso reduzido no cálculo: são muitos e costumam ser serviços do SO). */
    val isSystemApp: Boolean,
    /** Tempo em primeiro plano na janela analisada. */
    val foregroundMs: Long,
    /** Quantas vezes o app foi aberto na janela. */
    val sessions: Int,
    /** Último uso (epoch ms, 0 quando desconhecido). */
    val lastUsedAt: Long,
    /** Tráfego por rede móvel na janela. */
    val mobileBytes: Long,
    /** Tráfego por Wi-Fi na janela. */
    val wifiBytes: Long,
    /** Peso de consumo de 0 a 100 (quanto maior, mais energia o app pede). */
    val weight: Double,
    /** Fatia do consumo estimado do aparelho (0 a 100). */
    val sharePercent: Double,
    /** Estimativa de mAh gastos na janela (null quando ainda não há medição do aparelho). */
    val estimatedMah: Double?,
    /** Sinal de atividade em segundo plano: tráfego móvel alto e quase nenhum tempo de tela. */
    val backgroundSuspect: Boolean,
    /** App isento da otimização de bateria do Android (Doze). */
    val ignoresOptimizations: Boolean
) {
    val totalBytes: Long get() = mobileBytes + wifiBytes

    fun usageLabel(): String = Formatters.duration(foregroundMs)

    fun dataLabel(): String = Formatters.bytes(totalBytes)

    fun weightLabel(): String = BatteryScoring.levelLabel(weight)

    fun summaryLine(): String = buildString {
        append(usageLabel()).append(" em uso")
        if (totalBytes > 0) append(" · ").append(dataLabel())
        append(" · peso ").append(String.format(java.util.Locale.US, "%.0f", weight)).append("/100")
    }
}

/** Resultado completo de uma análise de consumo. */
data class BatteryUsageReport(
    val generatedAt: Long,
    val windowHours: Int,
    val usageAccessGranted: Boolean,
    val apps: List<AppBatteryUsage>,
    val live: BatteryLiveReading,
    /** Consumo médio do aparelho observado entre medições (mAh/h). */
    val observedMahPerHour: Double? = null,
    /** Energia total estimada consumida na janela (mAh). */
    val windowMah: Double? = null,
    /** Observações para o usuário (ex.: falta de permissão, propriedade não suportada). */
    val notes: List<String> = emptyList()
) {
    val topApps: List<AppBatteryUsage> get() = apps.take(5)

    val topApp: AppBatteryUsage? get() = apps.firstOrNull()

    val hasData: Boolean get() = usageAccessGranted && apps.isNotEmpty()

    /** Apps que parecem gastar bateria em segundo plano. */
    val backgroundApps: List<AppBatteryUsage>
        get() = apps.filter { it.backgroundSuspect }.sortedByDescending { it.totalBytes }

    /** Apps isentos da otimização de bateria (candidatos a restringir). */
    val optimizedExemptApps: List<AppBatteryUsage>
        get() = apps.filter { it.ignoresOptimizations && !it.isSystemApp }

    /** Frase pronta para uso direto na interface, já com a preposição. */
    fun windowLabel(): String = when (windowHours) {
        1 -> "na última 1 hora"
        6 -> "nas últimas 6 horas"
        else -> "nas últimas $windowHours horas"
    }

    fun rankLine(limit: Int = 3): String = apps.take(limit).mapIndexed { index, app ->
        "${index + 1}. ${app.label} (${String.format(java.util.Locale.US, "%.0f", app.sharePercent)}%)"
    }.joinToString(" · ")
}
