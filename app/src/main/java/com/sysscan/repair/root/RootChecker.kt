package com.sysscan.repair.root

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
    val hiddenRootSuspect: Boolean,
    val managerInstalled: Boolean = false,
    val pendingGrant: Boolean = false
) {
    fun describe(): String {
        if (hasRoot) {
            val sb = StringBuilder()
            when (method) {
                RootMethod.MAGISK -> sb.append("Root via Magisk")
                RootMethod.KERNELSU -> sb.append("Root via KernelSU")
                RootMethod.SUPERSU -> sb.append("Root via SuperSU")
                RootMethod.OTHER -> sb.append("Root detectado")
                RootMethod.NONE -> sb.append("Root detectado")
            }
            if (magiskVersion.isNotBlank()) sb.append(" $magiskVersion")
            if (kernelSuVersion.isNotBlank()) sb.append(" $kernelSuVersion")
            if (hasBusyBox) sb.append(" · BusyBox")
            if (modulesCount > 0) sb.append(" · $modulesCount módulo(s)")
            return sb.toString()
        }
        if (pendingGrant && method == RootMethod.MAGISK) {
            return "Magisk encontrado — toque para conceder root"
        }
        if (pendingGrant && method == RootMethod.KERNELSU) {
            return "KernelSU encontrado — toque para conceder root"
        }
        if (pendingGrant) {
            return "Gerenciador de root encontrado — toque para conceder"
        }
        if (hiddenRootSuspect) return "Root possivelmente oculto — toque para tentar"
        return "Root não detectado"
    }
}

object RootChecker {

    private val SU_PATHS = listOf(
        "/debug_ramdisk/su",
        "/debug_ramdisk/.magisk",
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/vendor/bin/su",
        "/system/sbin/su",
        "/su/bin/su",
        "/data/local/su",
        "/data/local/bin/su",
        "/data/local/xbin/su",
        "/cache/su"
    )

    private val MAGISK_HINTS = listOf(
        "/data/adb/magisk",
        "/data/adb/magisk.db",
        "/sbin/.magisk",
        "/debug_ramdisk/.magisk",
        "/debug_ramdisk/bin/su",
        "/data/adb/.magisk",
        "/cache/.disable_magisk",
        "/dev/.magisk",
        "/system/bin/magisk",
        "/system/xbin/magisk"
    )

    private val KERNELSU_HINTS = listOf(
        "/data/adb/ksu",
        "/data/adb/ksud",
        "/data/adb/ksu.apk"
    )

    private val MAGISK_PACKAGES = listOf(
        "com.topjohnwu.magisk",
        "io.github.vvb2060.magisk",
        "io.github.huskydg.magisk"
    )

    private val KERNELSU_PACKAGES = listOf(
        "me.weishu.kernelsu",
        "me.weishu.kernelsu.next",
        "com.rifsxd.ksunext",
        "com.rifsxd.ksuwebui"
    )

    private val SUPERSU_PACKAGES = listOf(
        "eu.chainfire.supersu",
        "com.koushikdutta.superuser",
        "com.noshufou.android.su",
        "com.thirdparty.superuser"
    )

    private val SU_INVOCATIONS = listOf(
        listOf("su", "-c"),
        listOf("su", "0", "-c"),
        listOf("magisk", "su", "-c"),
        listOf("/system/bin/su", "-c"),
        listOf("/system/xbin/su", "-c"),
        listOf("/sbin/su", "-c"),
        listOf("/debug_ramdisk/su", "-c"),
        listOf("/debug_ramdisk/bin/su", "-c")
    )

    private val pool = Executors.newCachedThreadPool { r ->
        Thread(r).apply { isDaemon = true }
    }

    @Volatile
    private var cachedAccess: Boolean? = null

    @Volatile
    private var cachedSuPrefix: List<String>? = null

    fun status(context: Context? = null, force: Boolean = false): RootStatus {
        if (force) {
            cachedAccess = null
            cachedSuPrefix = null
        }

        val manager = detectManager(context)
        val access = hasRootAccess(waitForGrant = force)
        val suPath = findSuPath() ?: cachedSuPrefix?.firstOrNull().orEmpty()

        val method = when {
            access && (manager == RootMethod.MAGISK || looksLikeMagisk()) -> RootMethod.MAGISK
            access && (manager == RootMethod.KERNELSU || looksLikeKernelSu()) -> RootMethod.KERNELSU
            access && manager == RootMethod.SUPERSU -> RootMethod.SUPERSU
            access -> RootMethod.OTHER
            manager != RootMethod.NONE -> manager
            looksLikeMagisk() -> RootMethod.MAGISK
            looksLikeKernelSu() -> RootMethod.KERNELSU
            else -> RootMethod.NONE
        }

        val magiskVersion = if (access && (method == RootMethod.MAGISK || looksLikeMagisk())) {
            executeAsRoot("magisk -v", timeoutSeconds = 8).stdout.trim()
        } else ""

        val kernelSuVersion = if (access && method == RootMethod.KERNELSU) {
            executeAsRoot(
                "ksud -V 2>/dev/null || ksud --version 2>/dev/null",
                timeoutSeconds = 8
            ).stdout.trim().lineSequence().firstOrNull().orEmpty()
        } else ""

        val hasBusyBox = access && isBusyBoxPresent()
        val modulesCount = if (access) countModules() else 0
        val managerInstalled = manager != RootMethod.NONE
        val hiddenRootSuspect = !access && (isSuBinaryPresent() || looksLikeMagisk() || looksLikeKernelSu())
        val pendingGrant = !access && managerInstalled

        return RootStatus(
            hasRoot = access,
            method = method,
            suBinaryPath = suPath,
            magiskVersion = magiskVersion,
            kernelSuVersion = kernelSuVersion,
            hasBusyBox = hasBusyBox,
            modulesCount = modulesCount,
            hiddenRootSuspect = hiddenRootSuspect,
            managerInstalled = managerInstalled,
            pendingGrant = pendingGrant
        )
    }

    fun detectMethod(): RootMethod {
        if (looksLikeMagisk()) return RootMethod.MAGISK
        if (looksLikeKernelSu()) return RootMethod.KERNELSU
        if (File("/system/xbin/su").exists() || File("/system/bin/su").exists()) {
            return RootMethod.SUPERSU
        }
        if (isSuBinaryPresent()) return RootMethod.OTHER
        return RootMethod.NONE
    }

    fun openManager(context: Context): Boolean {
        val pkg = findManagerPackage(context) ?: return false
        val intent = context.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun findManagerPackage(context: Context): String? {
        val pm = context.packageManager
        (MAGISK_PACKAGES + KERNELSU_PACKAGES + SUPERSU_PACKAGES).forEach { pkg ->
            if (isPackageInstalled(pm, pkg)) return pkg
        }
        return try {
            pm.getInstalledApplications(0).firstOrNull { app ->
                val pkg = app.packageName.lowercase()
                val label = try {
                    pm.getApplicationLabel(app).toString().lowercase()
                } catch (_: Exception) {
                    ""
                }
                pkg.contains("magisk") || label.contains("magisk") ||
                    pkg.contains("kernelsu") || label.contains("kernelsu") ||
                    pkg.contains("ksunext") || label.contains("superuser")
            }?.packageName
        } catch (_: Exception) {
            null
        }
    }

    private fun detectManager(context: Context?): RootMethod {
        if (context == null) return RootMethod.NONE
        val pm = context.packageManager
        if (MAGISK_PACKAGES.any { isPackageInstalled(pm, it) }) return RootMethod.MAGISK
        if (KERNELSU_PACKAGES.any { isPackageInstalled(pm, it) }) return RootMethod.KERNELSU
        if (SUPERSU_PACKAGES.any { isPackageInstalled(pm, it) }) return RootMethod.SUPERSU
        val found = findManagerPackage(context) ?: return RootMethod.NONE
        val lower = found.lowercase()
        return when {
            lower.contains("magisk") -> RootMethod.MAGISK
            lower.contains("ksu") || lower.contains("kernelsu") -> RootMethod.KERNELSU
            else -> RootMethod.OTHER
        }
    }

    private fun isPackageInstalled(pm: PackageManager, pkg: String): Boolean = try {
        pm.getPackageInfo(pkg, 0)
        true
    } catch (_: Exception) {
        false
    }

    private fun looksLikeMagisk(): Boolean =
        MAGISK_HINTS.any { File(it).exists() } || mountsMention("magisk")

    private fun looksLikeKernelSu(): Boolean =
        KERNELSU_HINTS.any { File(it).exists() } || mountsMention("kernelsu") || mountsMention("ksu")

    private fun mountsMention(token: String): Boolean = try {
        val mounts = File("/proc/self/mounts").takeIf { it.canRead() }?.readText().orEmpty()
        val info = File("/proc/self/mountinfo").takeIf { it.canRead() }?.readText().orEmpty()
        mounts.contains(token, ignoreCase = true) || info.contains(token, ignoreCase = true)
    } catch (_: Exception) {
        false
    }

    private fun isBusyBoxPresent(): Boolean {
        val result = executeAsRoot("busybox | head -n 1", timeoutSeconds = 5)
        return result.stdout.contains("BusyBox", ignoreCase = true)
    }

    private fun countModules(): Int {
        val result = executeAsRoot(
            "ls /data/adb/modules 2>/dev/null | grep -v '^\\.' | wc -l",
            10
        )
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

    fun hasRootAccess(): Boolean = hasRootAccess(waitForGrant = false)

    fun hasRootAccess(waitForGrant: Boolean): Boolean {
        cachedAccess?.let { return it }
        val granted = probeRootAccess(waitForGrant)
        if (granted) cachedAccess = true
        return granted
    }

    private fun probeRootAccess(waitForGrant: Boolean): Boolean {
        cachedSuPrefix?.let { prefix ->
            val cached = executeWithPrefix(prefix, "id", timeoutSeconds = 8)
            if (isUidZero(cached)) return true
        }
        if (probeViaStdin(if (waitForGrant) 30 else 3)) return true
        val grantTimeout = if (waitForGrant) 30L else 3L
        val primary = listOf(listOf("su", "-c"), listOf("su", "0", "-c"))
        for (prefix in primary) {
            val result = executeWithPrefix(prefix, "id", timeoutSeconds = grantTimeout)
            if (isUidZero(result)) {
                cachedSuPrefix = prefix
                return true
            }
            if (result.stdout.contains("tempo limite") && waitForGrant) return false
        }
        for (prefix in SU_INVOCATIONS) {
            if (prefix in primary) continue
            val result = executeWithPrefix(prefix, "id", timeoutSeconds = 2)
            if (isUidZero(result)) {
                cachedSuPrefix = prefix
                return true
            }
        }
        return false
    }

    private fun probeViaStdin(timeoutSeconds: Long): Boolean = try {
        val process = ProcessBuilder("su")
            .redirectErrorStream(true)
            .start()
        try {
            process.outputStream.bufferedWriter().use { writer ->
                writer.write("id\n")
                writer.write("exit\n")
                writer.flush()
            }
        } catch (_: Exception) {
        }
        val reader = pool.submit<List<String>> {
            process.inputStream.bufferedReader().readLines()
        }
        val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            false
        } else {
            val lines = try {
                reader.get(2, TimeUnit.SECONDS)
            } catch (_: Exception) {
                emptyList()
            }
            val output = lines.joinToString("\n")
            if (output.contains("uid=0")) {
                cachedSuPrefix = listOf("su", "-c")
                true
            } else {
                false
            }
        }
    } catch (_: Exception) {
        false
    }

    private fun isUidZero(result: ShellResult): Boolean =
        result.exitCode == 0 && result.stdout.contains("uid=0")

    fun executeAsRoot(command: String, timeoutSeconds: Long = 30): ShellResult {
        val prefixes = cachedSuPrefix?.let { listOf(it) } ?: SU_INVOCATIONS
        var last = ShellResult(false, -1, "su indisponível")
        for (prefix in prefixes) {
            last = executeWithPrefix(prefix, command, timeoutSeconds)
            if (last.exitCode == 0) {
                cachedSuPrefix = prefix
                cachedAccess = true
                return last
            }
        }
        return last
    }

    private fun executeWithPrefix(
        prefix: List<String>,
        command: String,
        timeoutSeconds: Long
    ): ShellResult = try {
        val process = ProcessBuilder(prefix + command)
            .redirectErrorStream(true)
            .start()
        val reader = pool.submit<List<String>> {
            process.inputStream.bufferedReader().readLines()
        }
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
