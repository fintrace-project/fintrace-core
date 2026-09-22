package com.github.melancholic.fintrace.core.domain.entity

import java.time.LocalDateTime
import java.util.*

data class ImportJob(
    val id: UUID,
    val workspaceId: UUID,
    val status: ImportJobStatus,
    val startedBy: UUID,
    val startedAt: LocalDateTime,
    val finishedAt: LocalDateTime?,
    val importerName: String,
    val importerVersion: String,
    val message: String?,
    val accounts: Int = 0,
    val categories: Int = 0,
    val operations: Int = 0,
    val transfers: Int = 0,
    val anchors: Int = 0,
    val diagnostics: List<ImportDiagnostic> = listOf()
) {
    fun counts() = ImportJobStatistics(
        accounts = accounts,
        categories = categories,
        operations = operations,
        transfers = transfers,
        anchors = anchors
    )
}

enum class ImportJobStatus {
    RUNNING, SUCCEEDED, FAILED
}

data class ImportDiagnostic(
    val severity: ImportDiagnosticSeverity,
    val code: ImportDiagnosticCode,
    val count: Long,
    val detail: String?
)

enum class ImportDiagnosticSeverity {
    WARNING, ERROR
}

enum class ImportDiagnosticCode {
    UNSUPPORTED_RECORD, UNKNOWN_CURRENCY, UNRESOLVED_REFERENCE, SOURCE_ANOMALY
}

data class ImportJobStatistics(
    val accounts: Int = 0,
    val categories: Int = 0,
    val operations: Int = 0,
    val transfers: Int = 0,
    val anchors: Int = 0
)

data class ImporterDetails(
    val name: String,
    val version: String
)