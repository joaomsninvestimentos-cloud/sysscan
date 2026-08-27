package com.sysscan.repair.root

import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

enum class RootMethod {
    NONE, MAGISK, KERNELSU, SUPERSU, OTHER
}

data class RootStatus(
    val hasRoot: Boolean,
    val method: RootMethod,
    val suBinaryPath: String,
    val magiskVersion: String,
    val kernelSuVersion: String,
    val hasBusyBox: Boolean,
    val modulesCount: Int,
    val hiddenRootSuspect: Boolean
) {
    fun describe(): String {
        if (!hasRoot && !hiddenRootSuspect) return "Root não detectado"
        val sb = StringBuilder()
        when {
            hasRoot && method == RootMethod.MAGISK -> sb.append("Root via Magisk")
            hasRoot && method == RootMethod.KERNELSU -> sb.append("Root via KernelSU")
            hasRoot && method == RootMethod.SUPERSU -> sb.append("Root via SuperSU")
            hasRoot && method == RootMethod.OTHER -> sb.append("Root detectado")
            hiddenRootSuspect -> sb.append("Root possivelmente oculto")
            else -> sb.append("Sem acesso root")
        }
        if (magiskVersion.isNotBlank()) sb.append(" ${magiskVersion}")
        if (kernelSuVersion.isNotBlank()) sb.append(" ${kernelSuVersion}")
        if (hasBusyBox) sb.append(" · BusyBox")
        if (modulesCount > 0) sb.append(" · $modulesCount módulo(s)")
        return sb.toString()
    }
}

object RootChecker {

    private val SU_PATHS = listOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/vendor/bin/su",
        "/system/sbin/su",
        "/su/bin/su"
    )

    private val MAGISK_HINTS = listOf(
        "/data/adb/magisk",
        "/sbin/.magisk",
        "/sbin/.magisk/mirror/system",
        "/data/adb/.magisk"
    )

    private val KERNELSU_HINTS = listOf(
        "/data/adb/ksu",
        "/data/adb/ksud",
        "/proc/version"
    )

    private val pool = Executors.newCachedThreadPool { r ->
        Thread(r).apply { isDaemon = true }
    }

    fun status(): RootStatus {
        val method = detectMethod()
        val hasRoot = method != RootMethod.NONE && hasRootAccess()

        val magiskVersion = if (method == RootMethod.MAGISK || hasRoot) {
            executeAsRoot("magisk -v", timeoutSeconds = 5).stdout.trim()
        } else ""

        val kernelSuVersion = if (method == RootMethod.KERNELSU) {
            val v = executeAsRoot("ksud -V 2>/dev/null; magisk --denylist >/dev/null 2>&1; echo", 5).stdout
            v.trim()
        } else ""

        val hasBusyBox = isBusyBoxPresent()
        val modulesCount = countModules()
        val hiddenRootSuspect = !hasRoot && isSuBinaryPresent() && !isMagiskInstalled()

        return RootStatus(
            hasRoot = hasRoot,
            method = if (hasRoot) method else RootMethod.NONE,
            suBinaryPath = findSuPath() ?: "",
            magiskVersion = magiskVersion,
            kernelSuVersion = kernelSuVersion,
            hasBusyBox = hasBusyBox,
            modulesCount = modulesCount,
            hiddenRootSuspect = hiddenRootSuspect
        )
    }

    fun detectMethod(): RootMethod {
        if (isMagiskInstalled() || MAGISK_HINTS.any { File(it).exists() }) return RootMethod.MAGISK
        if (isKernelSuPresent() || File("/data/adb/ksu").exists() || File("/data/adb/ksud").exists()) {
            return RootMethod.KERNELSU
        }
        if (File("/system/xbin/su").exists() || File("/system/bin/su").exists()) {
            return RootMethod.SUPERSU
        }
        if (isSuBinaryPresent()) return RootMethod.OTHER
        return RootMethod.NONE
    }

    private fun isMagiskInstalled(): Boolean {
        val result = executeAsRoot("magisk -v", timeoutSeconds = 5)
        return result.exitCode == 0 && result.stdout.isNotBlank()
    }

    private fun isKernelSuPresent(): Boolean {
        val result = executeAsRoot("ksud --version 2>/dev/null || ksud -V 2>/dev/null; echo \$?", 5)
        return result.stdout.contains("KernelSU") || File("/data/adb/ksud").exists()
    }

    private fun isBusyBoxPresent(): Boolean {
        val result = executeAsRoot("busybox --help 2>/dev/null | head -n 1; echo \$?", 5)
        return result.stdout.contains("BusyBox")
    }

    private fun countModules(): Int {
        val result = executeAsRoot("ls /data/adb/modules 2>/dev/null | grep -v '^\\.' | wc -l", 10)
        return result.stdout.trim().toIntOrNull() ?: 0
    }

    fun isSuBinaryPresent(): Boolean =
        SU_PATHS.any { File(it).exists() } || isSuInPath()

    private fun isSuInPath(): Boolean {
        val env = System.getenv("PATH") ?: return false
        return env.split(":").any { dir ->
            val f = File(dir, "su")
            f.exists() && f.canExecute()
        }
    }

    private fun findSuPath(): String? {
        SU_PATHS.forEach { if (File(it).exists()) return it }
        val env = System.getenv("PATH") ?: return null
        env.split(":").forEach { dir ->
            val f = File(dir, "su")
            if (f.exists()) return f.absolutePath
        }
        return null
    }

    fun hasRootAccess(): Boolean {
        if (!isSuBinaryPresent()) return false
        val result = executeAsRoot("id")
        return result.exitCode == 0 && result.stdout.contains("uid=0")
    }

    fun executeAsRoot(command: String, timeoutSeconds: Long = 30): ShellResult = try {
        val process = ProcessBuilder("su", "-c", command)
            .redirectErrorStream(true)
            .start()
        val reader = pool.submit<List<String>> { process.inputStream.bufferedReader().readLines() }
        val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            ShellResult(false, -1, "Comando excedeu o tempo limite ($timeoutSeconds s)")
        } else {
            val lines = try {
                reader.get(2, TimeUnit.SECONDS)
            } catch (_: Exception) {
                emptyList()
            }
            val output = lines.joinToString("\n").trim()
            ShellResult(process.exitValue() == 0, process.exitValue(), output)
        }
    } catch (e: Exception) {
        ShellResult(false, -1, e.message ?: "Erro ao executar como root")
    }
}

data class ShellResult(
    val success: Boolean,
    val exitCode: Int,
    val stdout: String
)
