package com.sysscan.repair.util

import java.util.Locale

object Formatters {

    fun bytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
        val gb = mb / 1024.0
        return String.format(Locale.US, "%.2f GB", gb)
    }

    fun percent(value: Int): String = "$value%"

    fun celsius(milliCelsius: Int): String =
        String.format(Locale.US, "%.1f°C", milliCelsius / 10.0)

    fun elapsedMillis(start: Long, end: Long): String {
        val ms = end - start
        return String.format(Locale.US, "%.1f s", ms / 1000.0)
    }
}
