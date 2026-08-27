package com.sysscan.repair.updater

import android.content.Context
import android.content.Intent
import android.os.Environment
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

object UpdateChecker {

    const val GITHUB_REPO = "joaomsninvestimentos-cloud/sysscan"

    private const val API_URL = "https://api.github.com/repos/$GITHUB_REPO/releases/latest"

    fun check(context: Context): UpdateInfo? {
        val current = currentVersion(context) ?: return null
        val body = fetch(API_URL) ?: return null
        return try {
            val json = JSONObject(body)
            val tag = json.optString("tag_name").removePrefix("v")
            if (tag.isBlank()) return null
            val notes = json.optString("body", "")
            val downloadUrl = json.optJSONArray("assets")?.let { assets ->
                (0 until assets.length()).firstNotNullOfOrNull { i ->
                    val asset = assets.getJSONObject(i)
                    if (asset.optString("name").endsWith(".apk")) {
                        asset.optString("browser_download_url")
                    } else null
                }
            } ?: return null
            if (compareVersions(tag, current) <= 0) return null
            UpdateInfo(tag, downloadUrl, notes)
        } catch (e: Exception) {
            null
        }
    }

    fun downloadApk(context: Context, url: String, onResult: (Result<File>) -> Unit) {
        Thread {
            try {
                val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                    ?: context.cacheDir
                val file = File(dir, "sysscan-update.apk")
                if (file.exists()) file.delete()
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = true
                conn.connectTimeout = 15_000
                conn.readTimeout = 60_000
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
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    } catch (e: Exception) {
        null
    }

    private fun fetch(url: String): String? = try {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("User-Agent", "SysScan")
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        if (conn.responseCode == 200) {
            conn.inputStream.bufferedReader().readText()
        } else {
            null
        }
    } catch (e: Exception) {
        null
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
