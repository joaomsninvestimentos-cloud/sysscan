package com.sysscan.repair.model

object ScanCheckBuilder {

    fun ok(id: String, category: ScanCategory, title: String, detail: String): ScanCheck =
        ScanCheck(id, category, ScanSeverity.OK, title, detail)

    fun info(id: String, category: ScanCategory, title: String, detail: String): ScanCheck =
        ScanCheck(id, category, ScanSeverity.INFO, title, detail)

    fun info(
        id: String,
        category: ScanCategory,
        title: String,
        detail: String,
        fixId: String
    ): ScanCheck = ScanCheck(id, category, ScanSeverity.INFO, title, detail, fixId)

    fun warning(
        id: String,
        category: ScanCategory,
        title: String,
        detail: String,
        fixId: String? = null
    ): ScanCheck = ScanCheck(id, category, ScanSeverity.WARNING, title, detail, fixId)

    fun critical(
        id: String,
        category: ScanCategory,
        title: String,
        detail: String,
        fixId: String? = null
    ): ScanCheck = ScanCheck(id, category, ScanSeverity.CRITICAL, title, detail, fixId)
}
