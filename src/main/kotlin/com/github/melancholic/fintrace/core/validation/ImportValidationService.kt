package com.github.melancholic.fintrace.core.validation

import com.github.melancholic.fintrace.core.api.v1.dto.ImportIdentifiedEntityRequest
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

        validateIDs(payload, problems)
        validateCategories(payload, problems)
        validateAccounts(payload, problems)
        validateOperations(payload, problems)
        validateTransfers(payload, problems)
        validateBalanceAnchors(payload, problems)

        return problems
    }

    private fun validateIDs(
        payload: ImportPayloadRequest,
        problems: MutableList<ImportProblem>
    ) {
        val idMap: Map<UUID, List<EntityType>> = buildIdMap(payload)
        validateIdUniqueness(idMap, payload.categories, EntityType.CATEGORY, problems)
        validateIdUniqueness(idMap, payload.accounts, EntityType.ACCOUNT, problems)
        validateIdUniqueness(idMap, payload.operations, EntityType.OPERATION, problems)
        validateIdUniqueness(idMap, payload.transfers, EntityType.TRANSFER, problems)
        validateIdUniqueness(idMap, payload.balanceAnchors, EntityType.BALANCE_ANCHOR, problems)
    }

    private fun validateIdUniqueness(
        idMap: Map<UUID, List<EntityType>>,
        entities: List<ImportIdentifiedEntityRequest>,
        entityType: EntityType,
        problems: MutableList<ImportProblem>
    ) {
        validateIdUniquenessWithinSection(entities, idMap, entityType, problems)
        validateIdUniquenessAcrossSections(entities, idMap, entityType, problems)
    }

    private fun validateIdUniquenessAcrossSections(
        entities: List<ImportIdentifiedEntityRequest>,
        idMap: Map<UUID, List<EntityType>>,
        entityType: EntityType,
        problems: MutableList<ImportProblem>
    ) {
        val duplicatedIdsAcrossSections = entities.asSequence()
            .map { it.id to idMap.getValue(it.id).filter { et -> et != entityType } }
            .filter { it.second.isNotEmpty() }
            .map { it.first }
            .toSet()

        if (duplicatedIdsAcrossSections.isNotEmpty()) {
            problems.add(ImportProblem.duplicatedIdAcrossSections(entityType, duplicatedIdsAcrossSections))
        }
    }

    private fun validateIdUniquenessWithinSection(
        entities: List<ImportIdentifiedEntityRequest>,
        idMap: Map<UUID, List<EntityType>>,
        entityType: EntityType,
        problems: MutableList<ImportProblem>
    ) {
        val duplicatedIdsWithinSection = entities.asSequence()
            .map { it.id to idMap.getValue(it.id).filter { et -> et == entityType } }
            .filter { it.second.size > 1 }
            .map { it.first }
            .toSet()

        if (duplicatedIdsWithinSection.isNotEmpty()) {
            problems.add(ImportProblem.duplicatedIdWithinSection(entityType, duplicatedIdsWithinSection))
        }
    }

    private fun buildIdMap(payload: ImportPayloadRequest): Map<UUID, List<EntityType>> = sequenceOf(
        payload.categories.asSequence().map { it.id to EntityType.CATEGORY },
        payload.accounts.asSequence().map { it.id to EntityType.ACCOUNT },
        payload.operations.asSequence().map { it.id to EntityType.OPERATION },
        payload.transfers.asSequence().map { it.id to EntityType.TRANSFER },
        payload.balanceAnchors.asSequence().map { it.id to EntityType.BALANCE_ANCHOR },
    )
        .flatMap { it }
        .groupBy ({ it.first }, { it.second })

    private fun validateCategories(
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