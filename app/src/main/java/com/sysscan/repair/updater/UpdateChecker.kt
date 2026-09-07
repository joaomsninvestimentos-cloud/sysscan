package com.sysscan.repair.updater

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class UpdateInfo(
    val latestVersion: String,
    val downloadUrl: String,
    val notes: String
)

sealed class UpdateCheckResult {
    data class Available(val info: UpdateInfo) : UpdateCheckResult()
    data class UpToDate(val installed: String, val latest: String) : UpdateCheckResult()
    data class Failed(val reason: String) : UpdateCheckResult()
}

object UpdateChecker {

    const val GITHUB_REPO = "joaomsninvestimentos-cloud/sysscan"

    private const val API_URL = "https://api.github.com/repos/$GITHUB_REPO/releases/latest"
    private const val USER_AGENT = "SysScan-Android"

    fun check(context: Context): UpdateCheckResult {
        val current = currentVersion(context)
            ?: return UpdateCheckResult.Failed("Versão instalada desconhecida")
        val body = fetch(API_URL)
            ?: return UpdateCheckResult.Failed("Sem resposta do GitHub")
        return try {
            val json = JSONObject(body)
            val tag = json.optString("tag_name").removePrefix("v")
            if (tag.isBlank()) {
                return UpdateCheckResult.Failed("Release sem tag")
            }
            val notes = json.optString("body", "")
            val downloadUrl = json.optJSONArray("assets")?.let { assets ->
                (0 until assets.length()).firstNotNullOfOrNull { i ->
                    val asset = assets.getJSONObject(i)
                    if (asset.optString("name").endsWith(".apk")) {
                        asset.optString("browser_download_url")
                    } else null
                }
            }
            if (downloadUrl.isNullOrBlank()) {
                return UpdateCheckResult.Failed("Release sem APK anexado")
            }
            if (compareVersions(tag, current) <= 0) {
                UpdateCheckResult.UpToDate(current, tag)
            } else {
                UpdateCheckResult.Available(UpdateInfo(tag, downloadUrl, notes))
            }
        } catch (e: Exception) {
            UpdateCheckResult.Failed(e.message ?: "Falha ao ler a Release")
        }
    }

    fun downloadApk(context: Context, url: String, onResult: (Result<File>) -> Unit) {
        Thread {
            try {
                val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                    ?: context.cacheDir
                val file = File(dir, "sysscan-update.apk")
                if (file.exists()) file.delete()
                val conn = openFollowing(url)
                if (conn.responseCode !in 200..299) {
                    onResult(Result.failure(Exception("Download HTTP ${conn.responseCode}")))
                    return@Thread
                }
                conn.inputStream.use { input ->
                    file.outputStream().use { output -> input.copyTo(output) }
                }
                if (file.length() == 0L) {
                    onResult(Result.failure(Exception("Arquivo de atualização vazio")))
                } else {
                    onResult(Result.success(file))
                }
            } catch (e: Exception) {
                onResult(Result.failure(e))
            }
        }.start()
    }

    fun canInstallPackages(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    fun requestInstallPermission(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            )
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }

    fun installApk(context: Context, file: File): Boolean = try {
        val uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", file
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        true
    } catch (e: Exception) {
        false
    }

    fun currentVersion(context: Context): String? = try {
        context.packageManager.getPackageInfo(context.packageName, 0)
            .versionName
            ?.substringBefore("-debug")
            ?.substringBefore("-")
    } catch (e: Exception) {
        null
    }

    private fun fetch(url: String): String? = try {
        val conn = openFollowing(url)
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        if (conn.responseCode == 200) {
            conn.inputStream.bufferedReader().readText()
        } else {
            null
        }
    } catch (e: Exception) {
        null
    }

    private fun openFollowing(startUrl: String): HttpURLConnection {
        var current = startUrl
        repeat(6) {
            val conn = URL(current).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Accept", "*/*")
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            val code = conn.responseCode
            if (code in 300..399) {
                val next = conn.getHeaderField("Location") ?: return conn
                current = if (next.startsWith("http")) next else URL(URL(current), next).toString()
                conn.disconnect()
            } else {
                return conn
            }
        }
        throw IllegalStateException("Muitos redirecionamentos")
    }

    fun compareVersions(a: String, b: String): Int {
        val pa = a.split(".").map { it.toIntOrNull() ?: 0 }
        val pb = b.split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val va = pa.getOrElse(i) { 0 }
            val vb = pb.getOrElse(i) { 0 }
            if (va != vb) return va.compareTo(vb)
        }
        return 0
    }
}
