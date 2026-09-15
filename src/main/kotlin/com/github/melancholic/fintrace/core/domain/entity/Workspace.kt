package com.github.melancholic.fintrace.core.domain.entity

import java.time.LocalDateTime
import java.util.*

data class Workspace(
    val id: UUID,
    val name: String,
    val status: WorkspaceStatus,
    val ownerId: UUID,
    val defaultCurrency: String,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
    val version: Long,
    val deletedAt: LocalDateTime?
)

data class WorkspacePurgeData(
    val id: UUID,
    val version: Long,
    val deletedAt: LocalDateTime,
    val numOfEvents: Long,
    val numOfOperations: Long,
    val numOfAccounts: Long,
    val numOfCategories: Long,
    val numOfBalanceAnchors: Long
)

enum class WorkspaceStatus {
    NEW, ACTIVE, ARCHIVED, DELETED
}