package com.sysscan.repair.security

import java.io.File
import java.security.MessageDigest

/** Leituras de baixo nível do arquivo APK (hash para o VirusTotal, tamanho e assinatura ZIP). */
object ApkInspector {

    private val ZIP_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04) // "PK\u0003\u0004"

    /** SHA-256 em hexadecimal (identificador usado pelo VirusTotal). */
    fun sha256(file: File): String? {
        if (!file.exists() || !file.isFile) return null
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered(64 * 1024).use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            null
        }
    }

    fun size(file: File): Long = try {
        file.length()
    } catch (_: Exception) {
        0L
    }

    /**
     * Confere se o arquivo realmente começa como um ZIP (todo APK é um ZIP).
     * Um .apk que não é ZIP está corrompido ou foi trocado por outro arquivo.
     */
    fun looksLikeZip(file: File): Boolean = try {
        file.inputStream().use { input ->
            val head = ByteArray(ZIP_MAGIC.size)
            val read = input.read(head)
            read == ZIP_MAGIC.size && head.contentEquals(ZIP_MAGIC)
        }
    } catch (_: Exception) {
        false
    }
}
