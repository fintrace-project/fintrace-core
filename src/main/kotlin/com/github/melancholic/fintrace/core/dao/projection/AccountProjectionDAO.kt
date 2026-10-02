package com.github.melancholic.fintrace.core.dao.projection

import com.github.melancholic.fintrace.core.domain.projection.AccountProjection
import com.github.melancholic.fintrace.core.exception.ActionConflictException
import com.github.melancholic.fintrace.core.exception.ApplicationException
import com.github.melancholic.fintrace.core.exception.NotFoundEntityException
import com.github.melancholic.fintrace.core.service.projection.ProjectionTarget
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.util.*


interface AccountProjectionDAO : ProjectionDAO<AccountProjection> {
    override fun projectionTarget() = ProjectionTarget.ACCOUNT
    override fun supportedClass() = AccountProjection::class.java

    override fun create(projection: AccountProjection): UUID
    override fun update(projection: AccountProjection): AccountProjection
    override fun getById(workspaceId: UUID, id: UUID): AccountProjection

    fun remove(workspaceId: UUID, accountId: UUID)
    fun exists(workspaceId: UUID, accountId: UUID): Boolean
    fun getAllAccounts(workspaceId: UUID, includeArchived: Boolean): List<AccountProjection>
}


@Repository
class AccountProjectionDAOImpl(
    private val jdbc: JdbcClient
) : AccountProjectionDAO {

    override fun create(projection: AccountProjection): UUID = try {
        jdbc.sql(INSERT)
            .param("id", projection.id)
            .param("workspaceId", projection.workspaceId)
            .param("name", projection.name)
            .param("currency", projection.currency)
            .param("icon", projection.icon)
            .param("archived", projection.archived)
            .param("externalRef", projection.externalRef)
            .param("recordedAt", projection.recordedAt)
            .query(UUID::class.java)
            .single()
    } catch (_: DuplicateKeyException) {
        throw ActionConflictException("Couldn't create account '${projection.id}': that id is already taken")
    }

    override fun update(projection: AccountProjection): AccountProjection = jdbc.sql(UPDATE)
        .param("id", projection.id)
        .param("workspaceId", projection.workspaceId)
        .param("name", projection.name)
        .param("currency", projection.currency)
        .param("icon", projection.icon)
        .param("archived", projection.archived)
        .param("externalRef", projection.externalRef)
        .param("recordedAt", projection.recordedAt)
        .query(AccountProjection::class.java)
        .optional()
        .orElseThrow { ApplicationException("Couldn't update account '${projection.id}': no such row in workspace '${projection.workspaceId}'") }

    override fun getById(workspaceId: UUID, id: UUID): AccountProjection {
        return jdbc.sql(SELECT_BY_ID).param("id", id).param("workspaceId", workspaceId)
            .query(AccountProjection::class.java).optional()
            .orElseThrow { NotFoundEntityException("Account not found into workspace (workspaceId='$workspaceId', accountId='$id')") }
    }

    override fun remove(workspaceId: UUID, accountId: UUID) {
        remove(workspaceId, setOf(accountId))
    }

    override fun exists(workspaceId: UUID, accountId: UUID): Boolean {
        return jdbc.sql(CHECK_EXISTS).param("id", accountId).param("workspaceId", workspaceId)
            .query(Boolean::class.java).single()
    }

    override fun getAllAccounts(
        workspaceId: UUID,
        includeArchived: Boolean
    ): List<AccountProjection> {

        var sql = SELECT_ALL
        if (!includeArchived) {
            sql += " AND not archived"
        }

        return jdbc.sql(sql)
            .param("workspaceId", workspaceId)
            .query(AccountProjection::class.java)
            .list()
            .filterNotNull()
    }

    override fun removeAll(workspaceId: UUID) {
        jdbc.sql(DELETE_BY_WORKSPACE).param("workspaceId", workspaceId).update()
    }

    override fun remove(workspaceId: UUID, ids: Set<UUID>) {
        jdbc.sql(DELETE_BY_IDS_AND_WORKSPACE).param("workspaceId", workspaceId).param("ids", ids).update()
    }

    companion object {
        const val TABLE_NAME = "t_accounts"
        const val ALL_COLUMNS = "id, workspace_id, name, currency, archived, recorded_at, icon, external_ref"

        const val INSERT = """
            INSERT INTO $TABLE_NAME ($ALL_COLUMNS)
            VALUES (:id, :workspaceId, :name, :currency, :archived, :recordedAt, :icon, :externalRef)
            RETURNING id
        """

        const val UPDATE = """
            UPDATE $TABLE_NAME
            SET name = :name, currency = :currency, archived = :archived, recorded_at = :recordedAt, icon = :icon, external_ref = :externalRef
            WHERE workspace_id = :workspaceId AND id = :id
            RETURNING $ALL_COLUMNS
        """

        const val SELECT_ALL = """
            SELECT $ALL_COLUMNS
            from $TABLE_NAME
            WHERE workspace_id = :workspaceId
        """

        const val SELECT_BY_ID = SELECT_ALL + """
            AND id = :id
        """

        const val CHECK_EXISTS = """
            SELECT EXISTS(SELECT 1 FROM $TABLE_NAME WHERE workspace_id = :workspaceId AND id = :id)
        """

        const val DELETE_BY_WORKSPACE = """
            DELETE from $TABLE_NAME WHERE workspace_id = :workspaceId
        """

        const val DELETE_BY_IDS_AND_WORKSPACE = """
            DELETE from $TABLE_NAME WHERE workspace_id = :workspaceId AND id IN (:ids)
        """
    }
}