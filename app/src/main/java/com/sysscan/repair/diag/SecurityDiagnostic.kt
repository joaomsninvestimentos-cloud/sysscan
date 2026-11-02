package com.sysscan.repair.diag

import android.content.Context
import com.sysscan.repair.model.ScanCategory
import com.sysscan.repair.model.ScanCheck
import com.sysscan.repair.model.ScanCheckBuilder
import com.sysscan.repair.security.RiskLevel
import com.sysscan.repair.security.SecurityHashStore
import com.sysscan.repair.security.SecurityResultsStore
import com.sysscan.repair.security.SecurityScanReport
import com.sysscan.repair.security.SecurityScanner

/**
 * Módulo da varredura geral que responde "existe algo escondido ou malicioso no aparelho?".
 *
 * Ele roda a análise de segurança e transforma o resultado em itens do relatório:
 *  - apps sem ícone (que o usuário nem sabia que existiam);
 *  - apps com indícios sérios (spyware, versões modificadas de apps famosos etc.);
 *  - apps com poderes especiais perigosos (acessibilidade, notificações, administrador);
 *  - resumo de limpeza.
 */
class SecurityDiagnostic(private val context: Context) {

    fun check(): List<ScanCheck> {
        val report = try {
            SecurityScanner(context).scan(hashApks = false)
        } catch (e: Exception) {
            return listOf(
                ScanCheckBuilder.info(
                    "security_scan", ScanCategory.SECURITY, "Varredura de segurança",
                    "Não foi possível concluir a análise de segurança: " +
                        "${e.javaClass.simpleName}."
                )
            )
        }

        val hashes = SecurityHashStore.hashes(context).mapValues { it.value.sha256 }
        runCatching { SecurityResultsStore.save(context, report, hashes) }

        val results = mutableListOf<ScanCheck>()
        results.add(criticalAppsCheck(report))
        results.add(hiddenAppsCheck(report))
        results.add(specialPowersCheck(report))
        results.add(cleanCheck(report))
        return results
    }

    private fun criticalAppsCheck(report: SecurityScanReport): ScanCheck {
        val critical = report.criticalApps
        val high = report.highApps
        val id = "security_malware"
        return when {
            critical.isNotEmpty() -> ScanCheckBuilder.critical(
                id, ScanCategory.SECURITY,
                "${critical.size} app(s) com risco crítico",
                "Indícios fortes de app espião/malware: " +
                    critical.take(3).joinToString("; ") { "${it.label} (${it.signalsText()})" } +
                    ". Abra a varredura de segurança para ver a ficha e desinstalar.",
                "security_open"
            )
            high.isNotEmpty() -> ScanCheckBuilder.warning(
                id, ScanCategory.SECURITY,
                "${high.size} app(s) com risco alto",
                high.take(3).joinToString("; ") { "${it.label} (${it.signalsText()})" } +
                    ". Confira se você instalou esses apps.",
                "security_open"
            )
            else -> ScanCheckBuilder.ok(
                id, ScanCategory.SECURITY, "Apps maliciosos",
                "Nenhum app com indícios fortes de malware entre os " +
                    "${report.totalApps} analisados."
            )
        }
    }

    private fun hiddenAppsCheck(report: SecurityScanReport): ScanCheck {
        val hidden = report.hiddenApps
        val id = "security_hidden_apps"
        if (hidden.isEmpty()) {
            return ScanCheckBuilder.ok(
                id, ScanCategory.SECURITY, "Apps ocultos",
                "Todos os apps instalados têm ícone na tela — nenhum app escondido."
            )
        }
        val risky = hidden.filter { it.isSuspicious }
        val names = hidden.take(4).joinToString(", ") { it.label }
        return when {
            risky.isNotEmpty() -> ScanCheckBuilder.warning(
                id, ScanCategory.SECURITY,
                "${hidden.size} app(s) sem ícone na tela",
                "$names — e ${risky.size} deles têm outros indícios (permissões, " +
                    "acessibilidade ou origem suspeita). Veja a ficha de cada um.",
                "security_open"
            )
            else -> ScanCheckBuilder.info(
                id, ScanCategory.SECURITY,
                "${hidden.size} app(s) sem ícone na tela",
                "$names. Apps sem ícone podem ser legítimos (teclados, complementos), mas " +
                    "vale confirmar se você reconhece todos.",
                "security_open"
            )
        }
    }

    private fun specialPowersCheck(
        report: SecurityScanReport
    ): ScanCheck {
        val special = report.accessibilityApps
        val id = "security_special_powers"
        if (special.isEmpty()) {
            return ScanCheckBuilder.ok(
                id, ScanCategory.SECURITY, "Poderes especiais",
                "Nenhum app ativo com acessibilidade, leitura de notificações ou " +
                    "administrador do aparelho."
            )
        }
        val list = special.take(4).joinToString(", ") { it.label }
        return if (special.size > 4) {
            ScanCheckBuilder.info(
                id, ScanCategory.SECURITY,
                "${special.size} app(s) com poderes especiais",
                "$list e mais ${special.size - 4}. Acessibilidade/notificações veem a tela e as " +
                    "mensagens — mantenha só o que você confia.",
                "security_a11y"
            )
        } else {
            ScanCheckBuilder.info(
                id, ScanCategory.SECURITY,
                "${special.size} app(s) com poderes especiais",
                "$list. Revise em Configurações > Acessibilidade e > Notificações se todos " +
                    "são confiáveis.", "security_a11y"
            )
        }
    }

    private fun cleanCheck(report: SecurityScanReport): ScanCheck {
        val worst = report.worstLevel
        val id = "security_summary"
        val notes = buildString {
            append("${report.totalApps} apps verificados (ícone, permissões, acessibilidade, ")
            append("origem da instalação e APK).")
            if (!report.vtConfigured) {
                append(" Dica: configure a chave do VirusTotal para conferir os APKs ")
                append("suspeitos em mais de 60 antivírus.")
            }
        }
        return if (worst == RiskLevel.CLEAN || worst == RiskLevel.LOW) {
            ScanCheckBuilder.ok(id, ScanCategory.SECURITY, "Resultado da análise", notes)
        } else {
            ScanCheckBuilder.info(id, ScanCategory.SECURITY, "Resultado da análise", notes)
        }
    }
}
