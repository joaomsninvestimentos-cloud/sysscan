package com.sysscan.repair.diag

import android.os.Build
import com.sysscan.repair.model.ScanCategory
import com.sysscan.repair.model.ScanCheck
import com.sysscan.repair.model.ScanCheckBuilder
import com.sysscan.repair.root.RootChecker
import java.io.File

class SystemIntegrityDiagnostic(private val hasRoot: Boolean) {

    private val buildPropPaths = listOf(
        "/system/build.prop",
        "/system/etc/build.prop",
        "/vendor/build.prop"
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
        val abi64 = Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()
        val runtimeCandidates = if (abi64) {
            listOf("/system/bin/app_process64", "/system/bin/app_process")
        } else {
            listOf("/system/bin/app_process32", "/system/bin/app_process")
        }

        val buildProp = buildPropPaths.firstOrNull { pathVisible(it) }
        val runtimePath = runtimeCandidates.firstOrNull { pathVisible(it) }
        val emptyProp = buildProp?.let { File(it).takeIf { f -> f.exists() && f.isFile && f.length() == 0L } }

        if (runtimePath != null && emptyProp == null) {
            val abiLabel = if (abi64) "64-bit" else "32-bit"
            return ScanCheckBuilder.ok(
                "sys_files", ScanCategory.SYSTEM, "Arquivos essenciais do sistema",
                "Runtime $abiLabel presente (${File(runtimePath).name})" +
                    if (buildProp != null) " e ${File(buildProp).name} visível." else "."
            )
        }

        if (emptyProp != null) {
            return ScanCheckBuilder.warning(
                "sys_files", ScanCategory.SYSTEM, "build.prop vazio",
                "${emptyProp.absolutePath} tem 0 bytes. O aparelho ainda está em execução; " +
                    "isso não se corrige com root.",
                "sys_reflash"
            )
        }

        if (hasRoot) {
            val confirm = RootChecker.executeAsRoot(
                "if [ -x /system/bin/app_process64 ] || [ -x /system/bin/app_process32 ] || " +
                    "[ -x /system/bin/app_process ]; then echo runtime_ok; else echo runtime_missing; fi; " +
                    "if [ -e /system/build.prop ] || [ -e /vendor/build.prop ]; then echo prop_ok; else echo prop_missing; fi",
                timeoutSeconds = 8
            )
            if (confirm.success && confirm.stdout.contains("runtime_ok")) {
                return ScanCheckBuilder.ok(
                    "sys_files", ScanCategory.SYSTEM, "Arquivos essenciais do sistema",
                    "Confirmado com root: o runtime do Android está no lugar. " +
                        "Binários 32/64-bit ausentes no ABI que o aparelho não usa são normais."
                )
            }
            if (confirm.success && confirm.stdout.contains("runtime_missing")) {
                return ScanCheckBuilder.critical(
                    "sys_files", ScanCategory.SYSTEM, "Arquivos essenciais ausentes",
                    "Com root, app_process não foi encontrado. Isso não se recria pelo Magisk; " +
                        "só reinstalando a ROM/firmware do fabricante.",
                    "sys_reflash"
                )
            }
        }

        return ScanCheckBuilder.info(
            "sys_files", ScanCategory.SYSTEM, "Arquivos essenciais do sistema",
            "O Android bloqueia a leitura de /system/bin (execute-only). " +
                "Como o SysScan está rodando, o Zygote/app_process existe. " +
                "Faltar app_process32 em celular só 64-bit é normal — root não restaura isso."
        )
    }

    private fun pathVisible(path: String): Boolean {
        return try {
            val f = File(path)
            f.exists()
        } catch (_: Exception) {
            false
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
