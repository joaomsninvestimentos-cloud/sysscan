package com.sysscan.repair.diag

import android.content.Context
import com.sysscan.repair.diag.battery.BatteryLiveReading
import com.sysscan.repair.diag.battery.BatteryUsageAnalyzer
import com.sysscan.repair.diag.battery.BatteryUsageReport
import com.sysscan.repair.diag.battery.BatteryUsageStore
import com.sysscan.repair.model.ScanCategory
import com.sysscan.repair.model.ScanCheck
import com.sysscan.repair.model.ScanCheckBuilder
import com.sysscan.repair.util.Formatters
import java.util.Locale

/**
 * Módulo de varredura que responde "qual aplicativo está consumindo mais a bateria?".
 *
 * Cruza tempo de tela, sessões, tráfego de rede e isenção do Doze para montar um ranking
 * e também mede a corrente real do aparelho (mA) e a autonomia estimada.
 */
class BatteryUsageDiagnostic(private val context: Context) {

    fun check(windowHours: Int = 24): List<ScanCheck> {
        val results = mutableListOf<ScanCheck>()

        val report: BatteryUsageReport = try {
            BatteryUsageAnalyzer(context).analyze(windowHours)
        } catch (e: Exception) {
            return listOf(
                ScanCheckBuilder.info(
                    "usage_battery", ScanCategory.APPS, "Consumo de bateria por app",
                    "Não foi possível medir o consumo por aplicativo: ${e.javaClass.simpleName}."
                )
            )
        }

        // guarda o relatório para a tela de bateria e para o assistente
        runCatching { BatteryUsageStore.save(context, report) }

        results.add(accessCheck(report))
        results.add(drainCheck(report))
        results.add(topAppCheck(report))
        results.add(videoCallCheck(report))
        results.add(backgroundCheck(report))
        results.add(dozeCheck(report))

        return results
    }

    private fun accessCheck(report: BatteryUsageReport): ScanCheck =
        if (report.usageAccessGranted) {
            ScanCheckBuilder.ok(
                "usage_access", ScanCategory.APPS, "Acesso ao uso de aplicativos",
                "Permissão de uso concedida. O SysScan consegue identificar o consumo por app."
            )
        } else {
            ScanCheckBuilder.info(
                "usage_access", ScanCategory.APPS, "Acesso ao uso de aplicativos",
                "Conceda o acesso ao uso em Configurações > Acesso especial para o SysScan " +
                    "descobrir qual app está consumindo a bateria.", "usage_grant"
            )
        }

    private fun drainCheck(report: BatteryUsageReport): ScanCheck {
        val live = report.live
        val title = "Consumo de bateria agora"
        return when {
            live.charging -> ScanCheckBuilder.ok(
                "battery_drain_rate", ScanCategory.BATTERY, title,
                "Aparelho carregando (${live.levelLabel()}). O consumo em mA só é medido " +
                    "quando o aparelho está fora do carregador."
            )
            live.hasDrain && live.autonomyHours != null -> {
                val detail = "Consumo instantâneo de ${live.drainLabel()} " +
                    "(${estimatePercentPerHour(live)}). " +
                    "Autonomia estimada: ${live.autonomyLabel()} com " +
                    "${live.levelLabel()} de carga."
                when {
                    live.drainMa != null && live.drainMa >= 1800 ->
                        ScanCheckBuilder.warning(
                            "battery_drain_rate", ScanCategory.BATTERY,
                            "Consumo de energia muito alto agora", detail, "battery_settings"
                        )
                    live.drainMa != null && live.drainMa >= 1000 ->
                        ScanCheckBuilder.info(
                            "battery_drain_rate", ScanCategory.BATTERY, title, detail
                        )
                    else -> ScanCheckBuilder.ok(
                        "battery_drain_rate", ScanCategory.BATTERY, title, detail
                    )
                }
            }
            live.hasDrain -> ScanCheckBuilder.ok(
                "battery_drain_rate", ScanCategory.BATTERY, title,
                "Consumo instantâneo de ${live.drainLabel()} " +
                    "(${estimatePercentPerHour(live)})."
            )
            else -> ScanCheckBuilder.info(
                "battery_drain_rate", ScanCategory.BATTERY, title,
                "Este aparelho não expõe a corrente instantânea da bateria " +
                    "(CURRENT_NOW), então o consumo em mA não pode ser medido pelo app."
            )
        }
    }

    private fun topAppCheck(report: BatteryUsageReport): ScanCheck {
        val id = "usage_battery"
        val title = "Consumo de bateria por app"
        if (!report.usageAccessGranted) {
            return ScanCheckBuilder.info(
                id, ScanCategory.APPS, title,
                "Sem a permissão de uso o Android não permite saber qual aplicativo consome " +
                    "a bateria.", "usage_grant"
            )
        }
        val top = report.topApp ?: return ScanCheckBuilder.ok(
            id, ScanCategory.APPS, title,
            "Nenhum app com consumo relevante ${report.windowLabel()}."
        )

        val detail = buildString {
            append("App que mais consome: ").append(top.label)
            append(" — peso ").append(format(top.weight)).append("/100 (")
            append(top.weightLabel()).append("), ")
            append(format(top.sharePercent)).append("% do consumo estimado, ")
            append(top.usageLabel()).append(" de tela")
            if (top.sessions > 0) append(", ${top.sessions} aberturas")
            if (top.totalBytes > 0) append(", ${top.dataLabel()} de dados")
            top.estimatedMah?.let { append(", ≈ ${Formatters.milliampHours(it)}") }
            append(".")
            if (report.apps.size > 1) append(" Ranking: ${report.rankLine()}.")
        }

        val high = top.weight >= 35.0 || top.sharePercent >= 30.0
        return if (high) {
            ScanCheckBuilder.warning(
                id, ScanCategory.APPS, "App que mais consome bateria: ${top.label}",
                detail, "battery_apps_open"
            )
        } else {
            ScanCheckBuilder.ok(
                id, ScanCategory.APPS, "App que mais consome bateria: ${top.label}", detail
            )
        }
    }

    private fun videoCallCheck(report: BatteryUsageReport): ScanCheck {
        val id = "usage_video_calls"
        val title = "Apps de chamada de vídeo"
        if (!report.usageAccessGranted) {
            return ScanCheckBuilder.info(
                id, ScanCategory.BATTERY, title,
                "Conceda o acesso ao uso para medir o consumo de chamadas de vídeo."
            )
        }
        val callApps = try {
            com.sysscan.repair.battery.CallModeHelper.callApps(context, report)
        } catch (_: Exception) {
            emptyList()
        }
        val active = callApps.filter { it.usage != null }
        if (active.isEmpty()) {
            return ScanCheckBuilder.ok(
                id, ScanCategory.BATTERY, title,
                "Nenhum app de chamada de vídeo com uso registrado ${report.windowLabel()}."
            )
        }
        val share = active.sumOf { it.share }
        val list = active.take(3).joinToString("; ") {
            "${it.label} (${format(it.share)}%, ${it.usage?.usageLabel() ?: "—"} de tela)"
        }
        val detail = "$list. Chamadas de vídeo somam ${format(share)}% do consumo estimado " +
            "— câmera, tela acesa, codificação e rádio ao mesmo tempo."
        return when {
            share >= 25.0 -> ScanCheckBuilder.warning(
                id, ScanCategory.BATTERY,
                "Chamadas de vídeo consumindo ${format(share)}% da bateria", detail,
                "call_mode_open"
            )
            else -> ScanCheckBuilder.info(id, ScanCategory.BATTERY, title, detail)
        }
    }

    private fun backgroundCheck(report: BatteryUsageReport): ScanCheck {
        val id = "usage_background"
        val title = "Consumo em segundo plano"
        if (!report.usageAccessGranted) {
            return ScanCheckBuilder.info(
                id, ScanCategory.APPS, title,
                "Necessário conceder o acesso ao uso para medir a atividade em segundo plano."
            )
        }
        val suspects = report.backgroundApps
        if (suspects.isEmpty()) {
            return ScanCheckBuilder.ok(
                id, ScanCategory.APPS, title,
                "Nenhum app com tráfego de dados relevante fora da tela " +
                    "${report.windowLabel()}."
            )
        }
        val list = suspects.take(3).joinToString("; ") {
            "${it.label} (${Formatters.bytes(it.totalBytes)} de dados, " +
                "${it.usageLabel()} de tela)"
        }
        return ScanCheckBuilder.warning(
            id, ScanCategory.APPS,
            "${suspects.size} app(s) com atividade em segundo plano",
            "$list. Apps que trafegam dados sem tela aberta costumam puxar rádio e CPU — " +
                "restringir a bateria deles reduz o consumo.",
            "battery_apps_open"
        )
    }

    private fun dozeCheck(report: BatteryUsageReport): ScanCheck {
        val id = "battery_doze"
        val title = "Otimização de bateria (Doze)"
        if (!report.usageAccessGranted) {
            return ScanCheckBuilder.info(
                id, ScanCategory.BATTERY, title,
                "Conceda o acesso ao uso para revisar quais apps estão isentos da otimização."
            )
        }
        val exempt = report.optimizedExemptApps
        return when {
            exempt.isEmpty() -> ScanCheckBuilder.ok(
                id, ScanCategory.BATTERY, title,
                "Nenhum app instalado está isento da otimização de bateria."
            )
            exempt.size >= 6 -> ScanCheckBuilder.warning(
                id, ScanCategory.BATTERY,
                "${exempt.size} apps isentos da otimização de bateria",
                "App(s) fora do Doze podem gastar bateria mesmo parados: " +
                    exempt.take(5).joinToString(", ") { it.label } + ".",
                "battery_optimize_settings"
            )
            else -> ScanCheckBuilder.info(
                id, ScanCategory.BATTERY,
                "${exempt.size} app(s) isento(s) da otimização de bateria",
                exempt.joinToString(", ") { it.label } +
                    " não é pausado pelo Doze — se não precisar de segundo plano, restrinja.",
                "battery_optimize_settings"
            )
        }
    }

    private fun estimatePercentPerHour(reading: BatteryLiveReading): String {
        val drainMa = reading.drainMa
        if (drainMa == null || drainMa <= 0.0) return "consumo em %/h indisponível"

        // Capacidade estimada: carga restante (µAh) dividida pelo nível atual.
        val estimatedCapacityMah = reading.chargeCounterUah
            ?.takeIf { it > 0 && reading.levelPercent in 5..100 }
            ?.let { it / 1000.0 * 100.0 / reading.levelPercent }
        val capacityMah = estimatedCapacityMah ?: 4000.0
        val percentPerHour = drainMa / capacityMah * 100.0
        val suffix = if (estimatedCapacityMah != null) "" else " (base 4.000 mAh)"
        return "≈ ${String.format(Locale.US, "%.1f", percentPerHour)}%/h$suffix"
    }

    private fun format(value: Double): String = String.format(Locale.US, "%.0f", value)
}
