package com.sysscan.repair.repair

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.provider.Settings

data class FixDefinition(
    val id: String,
    val label: String,
    val openSettingsIntent: String? = null
)

data class FixResult(
    val success: Boolean,
    val message: String
)

object FixRegistry {

    val definitions: Map<String, FixDefinition> = mapOf(
        "battery_charge" to FixDefinition(
            "battery_charge", "Abrir configurações de bateria", openSettingsIntent =
            Settings.ACTION_BATTERY_SAVER_SETTINGS
        ),
        "memory_optimize" to FixDefinition(
            "memory_optimize", "Otimizar memória agora"
        ),
        "cpu_optimize" to FixDefinition(
            "cpu_optimize", "Otimizar processos em segundo plano"
        ),
        "storage_clean" to FixDefinition(
            "storage_clean", "Abrir configurações de armazenamento", openSettingsIntent =
            Settings.ACTION_INTERNAL_STORAGE_SETTINGS
        ),
        "network_dns_fix" to FixDefinition(
            "network_dns_fix", "Abrir configurações de rede", openSettingsIntent =
            Settings.ACTION_WIFI_SETTINGS
        ),
        "sensor_verify" to FixDefinition(
            "sensor_verify", "Verificar sensores", openSettingsIntent =
            Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS
        ),
        "apps_clear" to FixDefinition(
            "apps_clear", "Gerenciar aplicativos", openSettingsIntent =
            Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS
        ),
        "apps_cleanup" to FixDefinition(
            "apps_cleanup", "Revisar aplicativos instalados", openSettingsIntent =
            Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS
        ),
        "apps_permission_fix" to FixDefinition(
            "apps_permission_fix", "Revisar permissões dos apps", openSettingsIntent =
            Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS
        ),
        "apps_stability_fix" to FixDefinition(
            "apps_stability_fix", "Abrir configurações de aplicativos", openSettingsIntent =
            Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS
        ),
        "usage_grant" to FixDefinition(
            "usage_grant", "Conceder acesso ao uso de aplicativos", openSettingsIntent =
            Settings.ACTION_USAGE_ACCESS_SETTINGS
        ),
        "usage_review" to FixDefinition(
            "usage_review", "Revisar apps com uso intenso", openSettingsIntent =
            Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS
        ),
        "pkg_fix" to FixDefinition(
            "pkg_fix", "Reinstalar os aplicativos corrompidos",
            openSettingsIntent = Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS
        ),
        "sys_reflash" to FixDefinition(
            "sys_reflash", "Instalar ROM original (via recovery)"
        )
    )

    fun resolve(fixId: String): FixDefinition? = definitions[fixId]
}

class RepairEngine(private val context: Context) {

    fun execute(fixId: String): FixResult {
        val def = FixRegistry.resolve(fixId) ?: return FixResult(
            false, "Ação de reparo desconhecida."
        )

        return when {
            def.openSettingsIntent != null -> openSettings(def)
            fixId == "memory_optimize" || fixId == "cpu_optimize" -> killBackgroundProcesses()
            fixId == "sys_reflash" -> FixResult(
                false,
                "Reinstalar a ROM é uma ação manual pelo modo de recuperação (recovery). " +
                    "Só é necessária se o aparelho não ligar ou apresentar falhas graves."
            )
            else -> FixResult(false, "Ação de reparo não executável automaticamente.")
        }
    }

    private fun openSettings(def: FixDefinition): FixResult {
        val action = def.openSettingsIntent ?: return FixResult(false, "Destino não definido.")
        return try {
            val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            FixResult(true, "Configurações abertas.")
        } catch (e: Exception) {
            FixResult(false, "Não foi possível abrir as configurações: ${e.message}")
        }
    }

    private fun killBackgroundProcesses(): FixResult {
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val processes = am.runningAppProcesses ?: emptyList()
            var killed = 0
            for (proc in processes) {
                try {
                    if (proc.importance > ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) {
                        am.killBackgroundProcesses(proc.processName)
                        killed++
                    }
                } catch (_: Exception) {
                    // processo sem permissão para encerrar
                }
            }
            FixResult(true, "$killed processo(s) em segundo plano encerrado(s).")
        } catch (e: Exception) {
            FixResult(false, "Não foi possível otimizar: ${e.message}")
        }
    }
}
