package com.sysscan.repair.diag

import android.os.Build
import com.sysscan.repair.model.ScanCategory
import com.sysscan.repair.model.ScanCheck
import com.sysscan.repair.model.ScanCheckBuilder
import com.sysscan.repair.root.RootChecker
import java.io.File

class SystemIntegrityDiagnostic(private val hasRoot: Boolean) {

    private val essentialFiles = listOf(
        "/system/build.prop",
        "/system/bin/app_process",
        "/system/bin/app_process32",
        "/system/bin/app_process64",
        "/system/bin/dalvikvm"
    )

    fun check(): List<ScanCheck> {
        val results = mutableListOf<ScanCheck>()

        results.add(checkMountState())
        results.add(checkSelinuxState())
        results.add(checkEssentialFiles())
        if (hasRoot) {
            results.add(checkDmVerityState())
            results.add(checkSecontexts())
        }

        return results
    }

    private fun checkMountState(): ScanCheck {
        val mounts = try {
            File("/proc/mounts").readText()
        } catch (e: Exception) {
            ""
        }
        val systemWritable = mounts.lines().any {
            (it.startsWith("/dev/") || it.startsWith("/dev/block")) &&
                it.contains(" /system ") && !it.contains(" ro ")
        }
        return when {
            systemWritable -> ScanCheckBuilder.warning(
                "sys_mount", ScanCategory.SYSTEM, "Partição /system gravável",
                "A partição do sistema está montada em modo de escrita, indicando possível " +
                    "modificação não oficial do sistema.", "sys_remount_ro"
            )
            else -> ScanCheckBuilder.ok(
                "sys_mount", ScanCategory.SYSTEM, "Estado da partição do sistema",
                "Partição /system protegida (somente leitura), como esperado."
            )
        }
    }

    private fun checkSelinuxState(): ScanCheck {
        val mode = selinuxMode()
        return when (mode) {
            "Enforcing" -> ScanCheckBuilder.ok(
                "sys_selinux", ScanCategory.SYSTEM, "SELinux",
                "SELinux em modo Enforcing. Política de segurança ativa."
            )
            "Permissive" -> ScanCheckBuilder.warning(
                "sys_selinux", ScanCategory.SYSTEM, "SELinux permissivo",
                "SELinux está em modo Permissive, reduzindo a segurança e podendo causar " +
                    "comportamento irregular de apps.", "sys_selinux_fix"
            )
            "Disabled" -> ScanCheckBuilder.critical(
                "sys_selinux", ScanCategory.SYSTEM, "SELinux desativado",
                "SELinux está desativado. O sistema está com proteção reduzida.", "sys_selinux_fix"
            )
            else -> ScanCheckBuilder.info(
                "sys_selinux", ScanCategory.SYSTEM, "SELinux",
                "Não foi possível determinar o estado do SELinux."
            )
        }
    }

    private fun selinuxMode(): String {
        return try {
            val result = RootChecker.executeAsRoot("getenforce", timeoutSeconds = 5)
            when (result.stdout.trim().uppercase()) {
                "ENFORCING" -> "Enforcing"
                "PERMISSIVE" -> "Permissive"
                "DISABLED" -> "Disabled"
                else -> {
                    val sys = File("/sys/fs/selinux/enforce")
                    if (sys.exists()) {
                        when (sys.readText().trim()) {
                            "1" -> "Enforcing"
                            "0" -> "Permissive"
                            else -> "Desconhecido"
                        }
                    } else "Desconhecido"
                }
            }
        } catch (e: Exception) {
            "Desconhecido"
        }
    }

    private fun checkEssentialFiles(): ScanCheck {
        var missing = 0
        var empty = 0
        val details = mutableListOf<String>()

        for (path in essentialFiles) {
            val f = File(path)
            if (!f.exists()) {
                missing++
            } else if (f.length() == 0L) {
                empty++
                details.add(f.name)
            }
        }

        return when {
            missing == essentialFiles.size -> ScanCheckBuilder.critical(
                "sys_files", ScanCategory.SYSTEM, "Arquivos essenciais ausentes",
                "Nenhum arquivo essencial do sistema foi encontrado. O sistema pode estar " +
                    "gravemente corrompido.", "sys_reflash"
            )
            missing > 0 -> ScanCheckBuilder.warning(
                "sys_files", ScanCategory.SYSTEM, "Arquivos essenciais ausentes",
                "$missing arquivo(s) essencial(is) do sistema não encontrados. Pode indicar " +
                    "corrupção ou um sistema personalizado.", "sys_reflash"
            )
            empty > 0 -> ScanCheckBuilder.warning(
                "sys_files", ScanCategory.SYSTEM, "Arquivos essenciais corrompidos",
                "Arquivos com tamanho inválido (0 bytes): ${details.joinToString(", ")}.",
                "sys_reflash"
            )
            else -> ScanCheckBuilder.ok(
                "sys_files", ScanCategory.SYSTEM, "Arquivos essenciais do sistema",
                "Os arquivos críticos do sistema estão presentes e com tamanho válido."
            )
        }
    }

    private fun checkDmVerityState(): ScanCheck {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            ScanCheckBuilder.info(
                "sys_verity", ScanCategory.SYSTEM, "Proteção dm-verity",
                "A integridade das partições é verificada por dm-verity/avb durante o boot."
            )
        } else {
            ScanCheckBuilder.info(
                "sys_verity", ScanCategory.SYSTEM, "Proteção dm-verity",
                "Versão do Android sem verificação completa de integridade."
            )
        }
    }

    private fun checkSecontexts(): ScanCheck {
        val result = RootChecker.executeAsRoot(
            "find /data/system -maxdepth 1 -name '*.xml' -exec ls -Z {} \\; 2>/dev/null | head -n 5",
            timeoutSeconds = 15
        )
        return if (result.success && result.stdout.isNotBlank()) {
            ScanCheckBuilder.ok(
                "sys_secontext", ScanCategory.SYSTEM, "Contextos SELinux dos dados",
                "Contextos de segurança dos arquivos de sistema verificados."
            )
        } else {
            ScanCheckBuilder.info(
                "sys_secontext", ScanCategory.SYSTEM, "Contextos SELinux dos dados",
                "Não foi possível verificar os contextos (${result.stdout.take(80)})."
            )
        }
    }
}
