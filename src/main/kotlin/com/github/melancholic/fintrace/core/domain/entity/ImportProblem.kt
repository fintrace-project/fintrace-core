package com.github.melancholic.fintrace.core.domain.entity

import com.github.melancholic.fintrace.core.config.UUID_VERSION
import com.github.melancholic.fintrace.core.domain.event.EntityType
import java.util.*

data class ImportProblem(
    val code: ImportProblemCode,
    val message: String,
    val aggregateType: EntityType,
    val affectedIDs: Set<UUID>
) {
    companion object {
        fun wrongUUIDVersion(entityType: EntityType, affectedIDs: Set<UUID>) = ImportProblem(
            code = ImportProblemCode.WRONG_UUID_VERSION,
            message = "Wrong UUID version: should be version $UUID_VERSION",
            aggregateType = entityType,
            affectedIDs = affectedIDs
        )

        fun duplicatedIdWithinSection(entityType: EntityType, affectedIDs: Set<UUID>) = ImportProblem(
            code = ImportProblemCode.DUPLICATED_ENTITY_ID_WITHIN_SECTION,
            message = "$entityType contains several entities with the same ID (all IDs must be unique).",
            aggregateType = entityType,
            affectedIDs = affectedIDs
        )

        fun duplicatedIdAcrossSections(entityType: EntityType, affectedIDs: Set<UUID>) = ImportProblem(
            code = ImportProblemCode.DUPLICATED_ENTITY_ID_ACROSS_SECTIONS,
            message = "$entityType contains ID(s) overlapping with other entity type (all IDs must be unique).",
            aggregateType = entityType,
            affectedIDs = affectedIDs
        )

        fun unresolvedReferences(entityType: EntityType, field: String, affectedIDs: Set<UUID>) = ImportProblem(
            code = ImportProblemCode.UNRESOLVED_REFERENCE,
            message = "$entityType '$field' values resolve to nothing: each must name an entity of the referenced kind in the payload or the workspace.",
            aggregateType = entityType,
            affectedIDs = affectedIDs
        )

        fun cyclicReferences(entityType: EntityType, affectedIDs: Set<UUID>) = ImportProblem(
            code = ImportProblemCode.CYCLIC_REFERENCES,
            message = "$entityType contains cyclic references '${affectedIDs.joinToString("->")}'.",
            aggregateType = entityType,
            affectedIDs = affectedIDs
        )
    }
}

enum class ImportProblemCode {
    WRONG_UUID_VERSION,
    DUPLICATED_ENTITY_ID_WITHIN_SECTION,
    DUPLICATED_ENTITY_ID_ACROSS_SECTIONS,
    UNRESOLVED_REFERENCE,
    CYCLIC_REFERENCES
}