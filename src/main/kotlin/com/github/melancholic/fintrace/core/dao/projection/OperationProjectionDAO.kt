package com.github.melancholic.fintrace.core.dao.projection

import com.github.melancholic.fintrace.core.domain.projection.OperationProjection
import com.github.melancholic.fintrace.core.exception.ApplicationException
import com.github.melancholic.fintrace.core.exception.NotFoundEntityException
import com.github.melancholic.fintrace.core.service.projection.ProjectionTarget
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.util.*


interface OperationProjectionDAO : ProjectionDAO<OperationProjection> {
    override fun projectionTarget() = ProjectionTarget.OPERATION
    override fun supportedClass() = OperationProjection::class.java

    override fun create(projection: OperationProjection): UUID
    override fun update(projection: OperationProjection): OperationProjection
    override fun getById(workspaceId: UUID, id: UUID): OperationProjection
    fun getByIdAsOptional(workspaceId: UUID, operationId: UUID): Optional<OperationProjection>
    fun remove(workspaceId: UUID, id: UUID)
    fun getTransferPartiesById(workspaceId: UUID, transferId: UUID): Pair<UUID, UUID>
    fun existsTransferById(workspaceId: UUID, transferId: UUID): Boolean
}

@Repository
class OperationProjectionDAOImpl(
    private val jdbc: JdbcClient
) : OperationProjectionDAO {

    override fun create(projection: OperationProjection): UUID = jdbc.sql(INSERT)
        .param("id", projection.id)
        .param("workspaceId", projection.workspaceId)
        .param("amount", projection.amount)
        .param("kind", projection.kind.name)
        .param("accountId", projection.accountId)
        .param("categoryId", projection.categoryId)
        .param("transferId", projection.transferId)
        .param("counterpartId", projection.counterpartId)
        .param("comment", projection.comment)
        .param("externalRef", projection.externalRef)
        .param("occurredAt", projection.occurredAt)
        .param("recordedAt", projection.recordedAt)
        .query(UUID::class.java)
        .single()

    override fun update(projection: OperationProjection): OperationProjection = jdbc.sql(UPDATE)
        .param("id", projection.id)
        .param("workspaceId", projection.workspaceId)
        .param("amount", projection.amount)
        .param("kind", projection.kind.name)
        .param("accountId", projection.accountId)
        .param("categoryId", projection.categoryId)
        .param("transferId", projection.transferId)
        .param("counterpartId", projection.counterpartId)
        .param("comment", projection.comment)
        .param("externalRef", projection.externalRef)
        .param("occurredAt", projection.occurredAt)
        .param("recordedAt", projection.recordedAt)
        .query(OperationProjection::class.java)
        .optional()
        .orElseThrow { ApplicationException("Couldn't update operation '${projection.id}': no such row in workspace '${projection.workspaceId}'") }

    override fun getById(workspaceId: UUID, id: UUID): OperationProjection = getByIdAsOptional(workspaceId, id)
        .orElseThrow { NotFoundEntityException("Operation not found into workspace (workspaceId='$workspaceId', operationId='$id')") }


    override fun getByIdAsOptional(workspaceId: UUID, operationId: UUID): Optional<OperationProjection> =
        jdbc.sql(SELECT)
            .param("id", operationId)
            .param("workspaceId", workspaceId)
            .query(OperationProjection::class.java)
            .optional()

    override fun removeAll(workspaceId: UUID) {
        jdbc.sql(DELETE_BY_WORKSPACE).param("workspaceId", workspaceId).update()
    }

    override fun remove(workspaceId: UUID, id: UUID) {
        remove(workspaceId, setOf(id))
    }

    override fun getTransferPartiesById(
        workspaceId: UUID,
        transferId: UUID
    ): Pair<UUID, UUID> {
        val ids = jdbc.sql(SELECT_COUNTERPARTS_ID_BY_TRANSFER_ID)
            .param("workspaceId", workspaceId)
            .param("transferId", transferId)
            .query(UUID::class.java)
            .list()
        if (ids.isEmpty()) {
            throw NotFoundEntityException("Transfer not found into workspace (workspaceId='$workspaceId', transferId='$transferId')")
        }
        if (ids.size != 2) {
            throw ApplicationException("Unexpected number of occurred on transfer operations (id=$ids)")
        }
        return ids[0]!! to ids[1]!!
    }

    override fun existsTransferById(workspaceId: UUID, transferId: UUID) = jdbc.sql(CHECK_EXISTS_TRANSFER)
        .param("workspaceId", workspaceId)
        .param("transferId", transferId)
        .query(Boolean::class.java)
        .single()

    override fun remove(workspaceId: UUID, ids: Set<UUID>) {
        jdbc.sql(DELETE_BY_IDS_AND_WORKSPACE).param("workspaceId", workspaceId).param("ids", ids).update()
    }

    private companion object {
        const val TABLE_NAME = "t_operations"
        const val ALL_COLUMNS = "id, workspace_id, amount, kind, account_id, category_id, transfer_id, counterpart_id, comment, external_ref, occurred_at, recorded_at"

        const val INSERT = """
            INSERT INTO $TABLE_NAME ($ALL_COLUMNS)
            VALUES (
                :id,
                :workspaceId, 
                :amount,
                :kind,
                :accountId,
                :categoryId,
                :transferId,
                :counterpartId,
                :comment,
                :externalRef,
                :occurredAt,
                :recordedAt
            ) RETURNING id
        """

        const val UPDATE = """
            UPDATE $TABLE_NAME
            SET 
                amount = :amount,
                kind = :kind,
                account_id = :accountId,
                category_id = :categoryId,
                transfer_id = :transferId,
                counterpart_id = :counterpartId,
                comment = :comment,
                external_ref = :externalRef,
                occurred_at = :occurredAt,
                recorded_at = :recordedAt
            WHERE id = :id AND workspace_id = :workspaceId
            RETURNING $ALL_COLUMNS
        """

        const val SELECT = """
            SELECT $ALL_COLUMNS
            from $TABLE_NAME
            WHERE workspace_id = :workspaceId AND id = :id
        """

        const val DELETE_BY_WORKSPACE = """
            DELETE from $TABLE_NAME WHERE workspace_id = :workspaceId
        """

        const val DELETE_BY_IDS_AND_WORKSPACE = """
            DELETE from $TABLE_NAME WHERE workspace_id = :workspaceId AND id IN (:ids)
        """

        const val SELECT_COUNTERPARTS_ID_BY_TRANSFER_ID = """
            SELECT id FROM $TABLE_NAME
            WHERE workspace_id = :workspaceId AND transfer_id = :transferId
            ORDER BY amount ASC
        """

        const val CHECK_EXISTS_TRANSFER = """
            SELECT EXISTS ($SELECT_COUNTERPARTS_ID_BY_TRANSFER_ID)
        """
    }
}