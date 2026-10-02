package com.github.melancholic.fintrace.core.validation

import com.github.melancholic.fintrace.core.api.v1.dto.ImportPayloadRequest
import com.github.melancholic.fintrace.core.config.UUID_VERSION
import com.github.melancholic.fintrace.core.domain.entity.ImportProblem
import com.github.melancholic.fintrace.core.domain.event.EntityType
import org.springframework.stereotype.Service
import java.util.*

interface ImportValidationService {
    fun validate(workspaceId: UUID, payload: ImportPayloadRequest): List<ImportProblem>
}

@Service
class ImportValidationServiceImpl : ImportValidationService {
    override fun validate(workspaceId: UUID, payload: ImportPayloadRequest): List<ImportProblem> {
        val problems: MutableList<ImportProblem> = mutableListOf()

        validateCategories(workspaceId, payload, problems)
        validateAccounts(workspaceId, payload, problems)
        validateOperations(workspaceId, payload, problems)
        validateTransfers(workspaceId, payload, problems)
        validateBalanceAnchors(workspaceId, payload, problems)

        return problems
    }

    private fun validateCategories(
        workspaceId: UUID,
        request: ImportPayloadRequest,
        problems: MutableList<ImportProblem>
    ) {
        val wrongUUIDs: MutableSet<UUID> = mutableSetOf()
        request.categories.forEach { entity ->

            if (uuidWithWrongVersion(entity.id)) {
                wrongUUIDs.add(entity.id)
            }
        }

        if (wrongUUIDs.isNotEmpty()) {
            problems.add(ImportProblem.wrongUUIDVersion(EntityType.CATEGORY, wrongUUIDs))
        }
    }

    private fun validateAccounts(
        workspaceId: UUID,
        request: ImportPayloadRequest,
        problems: MutableList<ImportProblem>
    ) {
        val wrongUUIDs: MutableSet<UUID> = mutableSetOf()
        request.accounts.forEach { entity ->

            if (uuidWithWrongVersion(entity.id)) {
                wrongUUIDs.add(entity.id)
            }
        }

        if (wrongUUIDs.isNotEmpty()) {
            problems.add(ImportProblem.wrongUUIDVersion(EntityType.ACCOUNT, wrongUUIDs))
        }
    }

    private fun validateOperations(
        workspaceId: UUID,
        request: ImportPayloadRequest,
        problems: MutableList<ImportProblem>
    ) {
        val wrongUUIDs: MutableSet<UUID> = mutableSetOf()
        request.operations.forEach { entity ->

            if (uuidWithWrongVersion(entity.id)) {
                wrongUUIDs.add(entity.id)
            }
        }

        if (wrongUUIDs.isNotEmpty()) {
            problems.add(ImportProblem.wrongUUIDVersion(EntityType.OPERATION, wrongUUIDs))
        }
    }

    private fun validateTransfers(
        workspaceId: UUID,
        request: ImportPayloadRequest,
        problems: MutableList<ImportProblem>
    ) {
        val wrongUUIDs: MutableSet<UUID> = mutableSetOf()
        request.transfers.forEach { entity ->

            if (uuidWithWrongVersion(entity.id)) {
                wrongUUIDs.add(entity.id)
            }
        }

        if (wrongUUIDs.isNotEmpty()) {
            problems.add(ImportProblem.wrongUUIDVersion(EntityType.TRANSFER, wrongUUIDs))
        }
    }

    private fun validateBalanceAnchors(
        workspaceId: UUID,
        request: ImportPayloadRequest,
        problems: MutableList<ImportProblem>
    ) {
        val wrongUUIDs: MutableSet<UUID> = mutableSetOf()
        request.balanceAnchors.forEach { entity ->

            if (uuidWithWrongVersion(entity.id)) {
                wrongUUIDs.add(entity.id)
            }
        }

        if (wrongUUIDs.isNotEmpty()) {
            problems.add(ImportProblem.wrongUUIDVersion(EntityType.BALANCE_ANCHOR, wrongUUIDs))
        }
    }

    private fun uuidWithWrongVersion(id: UUID): Boolean = id.version() != UUID_VERSION
}