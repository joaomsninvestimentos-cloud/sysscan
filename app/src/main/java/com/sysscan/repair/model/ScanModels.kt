package com.sysscan.repair.model

enum class ScanSeverity(val score: Int) {
    OK(0),
    INFO(5),
    WARNING(25),
    CRITICAL(50)
}

enum class ScanCategory {
    SYSTEM,
    BATTERY,
    MEMORY,
    STORAGE,
    NETWORK,
    CPU,
    SENSORS,
    APPS,
    SECURITY
}

data class ScanCheck(
    val id: String,
    val category: ScanCategory,
    val severity: ScanSeverity,
    val title: String,
    val detail: String,
    val fixId: String? = null
)

data class ScanReport(
    val category: ScanCategory,
    val total: Int = 0,
    val warnings: Int = 0,
    val critical: Int = 0,
    val fixed: Int = 0
)

data class ScanSummary(
    val checks: List<ScanCheck>,
    val startedAt: Long,
    val finishedAt: Long
) {
    val score: Int
        get() {
            val base = 100
            var penalty = 0
            for (c in checks) penalty += c.severity.score
            val maxPenalty = checks.size * 50
            if (maxPenalty == 0) return base
            val ratio = penalty.toDouble() / maxPenalty.toDouble()
            return (base * (1.0 - ratio)).toInt().coerceIn(0, 100)
        }

    val criticalCount: Int get() = checks.count { it.severity == ScanSeverity.CRITICAL }
    val warningCount: Int get() = checks.count { it.severity == ScanSeverity.WARNING }
    val infoCount: Int get() = checks.count { it.severity == ScanSeverity.INFO }
    val okCount: Int get() = checks.count { it.severity == ScanSeverity.OK }

    val fixableChecks: List<ScanCheck>
        get() = checks.filter { it.fixId != null && it.severity != ScanSeverity.OK }
}
