package com.sysscan.repair.repair

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.sysscan.repair.root.RootChecker

data class FixDefinition(
    val id: String,
    val label: String,
    val requiresRoot: Boolean = false,
    val command: String? = null,
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
        "storage_clean_root" to FixDefinition(
            "storage_clean_root", "Limpar caches dos apps (root)",
            requiresRoot = true, command = "pm trim-caches 104857600"
        ),
        "network_open_settings" to FixDefinition(
            "network_open_settings", "Abrir configurações de rede", openSettingsIntent =
            Settings.ACTION_WIFI_SETTINGS
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
        "sys_remount_ro" to FixDefinition(
            "sys_remount_ro", "Restaurar proteção da partição /system (root)",
            requiresRoot = true, command = "mount -o remount,ro /system"
        ),
        "sys_selinux_fix" to FixDefinition(
            "sys_selinux_fix", "Restaurar SELinux para Enforcing (root)",
            requiresRoot = true, command = "setenforce 1"
        ),
        "sys_reflash" to FixDefinition(
            "sys_reflash", "Instalar ROM original (via recovery)"
        ),
        "root_trim" to FixDefinition(
            "root_trim", "Otimizar armazenamento (fstrim, root)",
            requiresRoot = true, command = "sync; fstrim -v /data 2>&1 || fstrim -v / 2>&1"
        ),
        "root_cache" to FixDefinition(
            "root_cache", "Limpar caches do sistema (root)",
            requiresRoot = true, command = "sync; rm -rf /data/cache/* /cache/* 2>/dev/null; echo done"
        ),
        "root_mem" to FixDefinition(
            "root_mem", "Liberar memória e caches do kernel (root)",
            requiresRoot = true, command = "sync; echo 3 > /proc/sys/vm/drop_caches; echo done"
        ),
        "root_junk" to FixDefinition(
            "root_junk", "Remover arquivos temporários e lixo do sistema (root)",
            requiresRoot = true, command = "sync; rm -rf /data/local/tmp/* /data/anr/* /data/tombstones/* 2>/dev/null; echo done"
        ),
        "root_logs" to FixDefinition(
            "root_logs", "Limpar logs do sistema (root)",
            requiresRoot = true, command = "sync; logcat -c 2>/dev/null; echo done"
        ),
        "root_garbage" to FixDefinition(
            "root_garbage", "Rodar coleta de lixo e reiniciar Zygote (root)",
            requiresRoot = true, command = "sync; stop; start; echo done"
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
            def.requiresRoot -> executeRootCommand(def)
            def.command != null -> executeRootCommand(def)
            def.openSettingsIntent != null -> openSettings(def)
            fixId == "memory_optimize" || fixId == "cpu_optimize" -> killBackgroundProcesses()
            fixId == "sys_reflash" -> FixResult(
                false,
                "A corrupção do sistema não pode ser reparada por app. Instale a ROM original " +
                    "pelo modo de recuperação (recovery) do fabricante."
            )
            else -> FixResult(false, "Ação de reparo não executável automaticamente.")
        }
    }

    private fun executeRootCommand(def: FixDefinition): FixResult {
        val cmd = def.command ?: return FixResult(false, "Comando não definido.")
        val result = RootChecker.executeAsRoot(cmd, timeoutSeconds = 60)
        return if (result.exitCode == 0) {
            FixResult(true, "Reparo concluído: ${def.label}.")
        } else {
            FixResult(
                false,
                "Falha ao executar o reparo (código ${result.exitCode}): ${result.stdout.take(160)}"
            )
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
