package com.sysscan.repair.diag

import android.os.Build
import com.sysscan.repair.model.ScanCategory
import com.sysscan.repair.model.ScanCheck
import com.sysscan.repair.model.ScanCheckBuilder
import java.io.File

class SystemIntegrityDiagnostic {

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
                    "modificação não oficial do sistema."
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
                    "comportamento irregular de apps."
            )
            "Disabled" -> ScanCheckBuilder.critical(
                "sys_selinux", ScanCategory.SYSTEM, "SELinux desativado",
                "SELinux está desativado. O sistema está com proteção reduzida."
            )
            else -> ScanCheckBuilder.info(
                "sys_selinux", ScanCategory.SYSTEM, "SELinux",
                "Não foi possível determinar o estado do SELinux."
            )
        }
    }

    private fun selinuxMode(): String {
        return try {
            val sys = File("/sys/fs/selinux/enforce")
            if (sys.exists()) {
                when (sys.readText().trim()) {
                    "1" -> "Enforcing"
                    "0" -> "Permissive"
                    else -> "Desconhecido"
                }
            } else "Desconhecido"
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
                "${emptyProp.absolutePath} tem 0 bytes. Reinstale a ROM original pelo modo de " +
                    "recuperação se o aparelho apresentar instabilidade.",
                "sys_reflash"
            )
        }

        return ScanCheckBuilder.info(
            "sys_files", ScanCategory.SYSTEM, "Arquivos essenciais do sistema",
            "O Android bloqueia a leitura de /system/bin (execute-only). " +
                "Como o SysScan está rodando, o Zygote/app_process existe. " +
                "Faltar app_process32 em celular só 64-bit é normal."
        )
    }

    private fun pathVisible(path: String): Boolean {
        return try {
            val file = File(path)
            file.exists()
        } catch (_: Exception) {
            false
        }
    }
}
