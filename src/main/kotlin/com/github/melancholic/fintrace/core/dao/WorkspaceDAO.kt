package com.github.melancholic.fintrace.core.dao

import com.github.melancholic.fintrace.core.api.v1.dto.CreateWorkspaceRequest
import com.github.melancholic.fintrace.core.api.v1.dto.EditWorkspaceRequest
import com.github.melancholic.fintrace.core.domain.entity.Workspace
import com.github.melancholic.fintrace.core.domain.entity.WorkspacePurgeData
import com.github.melancholic.fintrace.core.domain.entity.WorkspaceStatus
import com.github.melancholic.fintrace.core.util.SqlHelper.orderBy
import com.github.melancholic.fintrace.core.util.TimestampProvider
import com.github.melancholic.fintrace.core.util.UUIDGenerator
import org.springframework.data.domain.Pageable
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.LocalDateTime
import java.util.*

interface WorkspaceDAO {

    fun create(userId: UUID, request: CreateWorkspaceRequest): UUID

    fun get(userId: UUID, workspaceId: UUID): Optional<Workspace>

    fun search(userId: UUID, page: Pageable): List<Workspace>

    fun update(
        userId: UUID,
        workspaceId: UUID,
        request: EditWorkspaceRequest,
        version: Long
    ): Int

    fun changeStatus(
        userId: UUID,
        workspaceId: UUID,
        sourceStatuses: Set<WorkspaceStatus>,
        newStatus: WorkspaceStatus
    ): Boolean

    fun changeStatus(
        userId: UUID,
        workspaceId: UUID,
        sourceStatuses: Set<WorkspaceStatus>,
        newStatus: WorkspaceStatus,
        version: Long?
    ): Boolean

    fun expireImportLease(
        workspaceId: UUID,
        before: LocalDateTime,
        newStatus: WorkspaceStatus
    ): Boolean

    fun isEmpty(workspaceId: UUID): Boolean

    fun findDeletedBefore(cutoff: LocalDateTime, limit: Int = 100): List<WorkspacePurgeData>

    fun purgeWorkspace(workspacePurgeData: WorkspacePurgeData): Boolean
}

@Repository
class WorkspaceDAOImpl(
    private val jdbc: JdbcClient,
    private val uuidGenerator: UUIDGenerator,
    private val timestampProvider: TimestampProvider
) : WorkspaceDAO {

    override fun create(
        userId: UUID,
        request: CreateWorkspaceRequest
    ): UUID {
        val workspaceId = jdbc.sql(CREATE_WORKSPACE)
            .param("id", uuidGenerator.nextUUID())
            .param("name", request.workspaceName)
            .param("status", WorkspaceStatus.NEW.name)
            .param("ownerId", userId)
            .param("defaultCurrency", request.defaultCurrency)
            .param("createdAt", timestampProvider.now())
            .query(UUID::class.java)
            .single()

        return workspaceId
    }

    override fun get(
        userId: UUID,
        workspaceId: UUID
    ): Optional<Workspace> {
        return jdbc.sql(GET_BY_ID_AND_OWNER_ID)
            .param("id", workspaceId)
            .param("ownerId", userId)
            .query(Workspace::class.java)
            .optional()
    }

    @Suppress("UNCHECKED_CAST")
    override fun search(
        userId: UUID,
        page: Pageable
    ): List<Workspace> {
        return jdbc.sql(SEARCH_WORKSPACE_PER_OWNER.format(orderBy(SORTABLE_COLS, listOf("created_at DESC"), page.sort)))
            .param("ownerId", userId)
            .param("limit", page.pageSize)
            .param("offset", page.offset)
            .query(Workspace::class.java)
            .list() as List<Workspace>
    }

    override fun update(
        userId: UUID,
        workspaceId: UUID,
        request: EditWorkspaceRequest,
        version: Long
    ): Int {

        val queryBuilder = jdbc.sql(updateSql(request))
            .param("ownerId", userId)
            .param("id", workspaceId)
            .param("updatedAt", timestampProvider.now())
            .param("version", version)

        request.workspaceName?.let { queryBuilder.param("name", it) }
        request.defaultCurrency?.let { queryBuilder.param("defaultCurrency", it) }

        return queryBuilder.update()
    }

    override fun changeStatus(
        userId: UUID,
        workspaceId: UUID,
        sourceStatuses: Set<WorkspaceStatus>,
        newStatus: WorkspaceStatus
    ): Boolean {
        return changeStatus(userId, workspaceId, sourceStatuses, newStatus, null)
    }

    override fun changeStatus(
        userId: UUID,
        workspaceId: UUID,
        sourceStatuses: Set<WorkspaceStatus>,
        newStatus: WorkspaceStatus,
        version: Long?,
    ): Boolean {
        val sql = if (version == null) CHANGE_STATUS else "$CHANGE_STATUS AND version = :version"

        val builder = jdbc.sql(sql)
            .param("ownerId", userId)
            .param("id", workspaceId)
            .param("updatedAt", timestampProvider.now())
            .param("newStatus", newStatus.name)
            .param("sourceStatuses", sourceStatuses.map { it.name })

        if (version != null) {
            builder.param("version", version)
        }

        val updated = builder.update()
        if (updated > 1) {
            throw IllegalStateException("Updated status change failed: were resolved more than 1 records for update (found: $updated)")
        }
        return updated == 1
    }

    override fun expireImportLease(
        workspaceId: UUID,
        before: LocalDateTime,
        newStatus: WorkspaceStatus
    ): Boolean = jdbc.sql(EXPIRE_IMPORT_LEASE)
        .param("id", workspaceId)
        .param("newStatus", newStatus.name)
        .param("updatedAt", timestampProvider.now())
        .param("before", before)
        .update() == 1

    override fun isEmpty(workspaceId: UUID): Boolean = jdbc.sql("SELECT fn_is_workspace_empty(:workspaceId)")
        .param("workspaceId", workspaceId)
        .query(Boolean::class.java)
        .single()

    override fun findDeletedBefore(cutoff: LocalDateTime, limit: Int): List<WorkspacePurgeData> =
        jdbc.sql(FIND_CANDIDATES_FOR_PURGE)
            .param("cutoffDateTime", cutoff)
            .param("limit", limit)
            .query(WorkspacePurgeData::class.java)
            .list() as List<WorkspacePurgeData>

    override fun purgeWorkspace(workspacePurgeData: WorkspacePurgeData): Boolean = jdbc.sql(PURGE_WORKSPACE)
        .param("workspaceId", workspacePurgeData.id)
        .param("version", workspacePurgeData.version)
        .update() == 1

    private fun updateSql(request: EditWorkspaceRequest): String = buildString {
        append("UPDATE t_workspaces SET")
        request.workspaceName?.let { append(" name = :name,") }
        request.defaultCurrency?.let { append(" default_currency = :defaultCurrency,") }
        append(" updated_at = :updatedAt,")
        append(" version = version + 1")
        append(" WHERE id = :id")
        append(" AND owner_id = :ownerId")
        append(" AND status <> 'DELETED'")
        append(" AND version = :version")
    }

    companion object {
        val SORTABLE_COLS = mapOf(
            "name" to "name",
            "status" to "status",
            "createdAt" to "created_at",
        )

        const val TABLE = "t_workspaces"

        const val CREATE_WORKSPACE = """
            INSERT INTO $TABLE (id, name, status, owner_id, default_currency, created_at, updated_at, version)
            VALUES (:id, :name, :status, :ownerId, :defaultCurrency, :createdAt, :createdAt, 0)
            RETURNING id       
        """

        const val GET_BY_ID_AND_OWNER_ID = """
            SELECT id, name, status, owner_id, default_currency, created_at, updated_at, import_started_at, deleted_at, version
            FROM $TABLE
            WHERE id = :id
            AND owner_id = :ownerId
            AND status <> 'DELETED'
        """

        const val SEARCH_WORKSPACE_PER_OWNER = """
            SELECT *
            FROM $TABLE
            WHERE owner_id = :ownerId 
                AND status <> 'DELETED'
            ORDER BY %s
            LIMIT :limit OFFSET :offset
        """

        const val CHANGE_STATUS = """
            UPDATE $TABLE
            SET status = :newStatus,
                updated_at = :updatedAt,
                deleted_at = CASE WHEN :newStatus = 'DELETED' THEN :updatedAt END,
                import_started_at = CASE
                    WHEN :newStatus = 'IMPORTING' THEN :updatedAt
                    WHEN :newStatus = 'NEW'       THEN NULL
                    ELSE import_started_at
                END,
                version = version + 1
            WHERE id = :id
                AND owner_id = :ownerId
                AND status <> 'DELETED'
                AND status in (:sourceStatuses)
        """

        const val EXPIRE_IMPORT_LEASE = """
            UPDATE $TABLE
            SET status = :newStatus,
                updated_at = :updatedAt,
                version = version + 1
            WHERE id = :id
                AND status = 'IMPORTING'
                AND import_started_at < :before
        """

        const val FIND_CANDIDATES_FOR_PURGE = """
            SELECT
                w.id, w.version, w.deleted_at, 
                (SELECT count(t_events.id) FROM t_events WHERE workspace_id = w.id) AS num_of_events,
                (SELECT count(t_operations.id) FROM t_operations WHERE workspace_id = w.id) AS num_of_operations,
                (SELECT count(t_accounts.id) FROM t_accounts WHERE workspace_id = w.id) AS num_of_accounts,
                (SELECT count(t_categories.id) FROM t_categories WHERE workspace_id = w.id) AS num_of_categories,
                (SELECT count(t_balance_anchors.id) FROM t_balance_anchors WHERE workspace_id = w.id) AS num_of_balance_anchors
            FROM $TABLE w
            WHERE status = 'DELETED'
                AND deleted_at < :cutoffDateTime
            ORDER BY w.id
            LIMIT :limit
        """

        const val PURGE_WORKSPACE = """
            DELETE FROM $TABLE
            WHERE id = :workspaceId
                AND version = :version
                AND status = 'DELETED'
        """
    }

}