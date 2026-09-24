package com.github.melancholic.fintrace.core.dao

import com.github.melancholic.fintrace.core.dao.mapper.ImportJobRowMapper
import com.github.melancholic.fintrace.core.domain.entity.*
import com.github.melancholic.fintrace.core.exception.NotFoundEntityException
import com.github.melancholic.fintrace.core.util.TimestampProvider
import com.github.melancholic.fintrace.core.util.UUIDGenerator
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import tools.jackson.databind.ObjectMapper
import java.util.*

interface ImportJobDAO {
    fun create(workspaceId: UUID, startedBy: UUID, importer: ImporterDetails): UUID

    fun complete(
        workspaceId: UUID,
        id: UUID,
        status: ImportJobStatus,
        counts: ImportJobStatistics?,
        diagnostics: List<ImportDiagnostic>,
        message: String? = null
    ): ImportJob

    fun updateStatus(
        workspaceId: UUID,
        oldStatus: ImportJobStatus,
        newStatus: ImportJobStatus
    ): Optional<ImportJob>

    fun getById(workspaceId: UUID, id: UUID): ImportJob
}

@Repository
class ImportJobDAOImpl(
    private val jdbc: JdbcClient,
    private val uuidGenerator: UUIDGenerator,
    private val timestampProvider: TimestampProvider,
    private val mapper: ObjectMapper,
    private val rowMapper: ImportJobRowMapper
) : ImportJobDAO {

    override fun create(workspaceId: UUID, startedBy: UUID, importer: ImporterDetails) =
        jdbc.sql(CREATE_IMPORT_JOB)
            .param("id", uuidGenerator.nextUUID())
            .param("status", ImportJobStatus.RUNNING.name)
            .param("startedAt", timestampProvider.now())
            .param("workspaceId", workspaceId)
            .param("startedBy", startedBy)
            .param("importerName", importer.name)
            .param("importerVersion", importer.version)
            .query(UUID::class.java)
            .single()

    override fun complete(
        workspaceId: UUID,
        id: UUID,
        status: ImportJobStatus,
        counts: ImportJobStatistics?,
        diagnostics: List<ImportDiagnostic>,
        message: String?
    ): ImportJob = jdbc.sql(UPDATE_IMPORT_JOB)
        .param("id", id)
        .param("workspaceId", workspaceId)
        .param("status", status.name)
        .param("finishedAt", timestampProvider.now())
        .param("message", message)
        .param("accounts", counts?.accounts)
        .param("categories", counts?.categories)
        .param("operations", counts?.operations)
        .param("transfers", counts?.transfers)
        .param("anchors", counts?.anchors)
        .param("diagnostics", mapper.writeValueAsString(diagnostics))
        .query(rowMapper)
        .single()

    override fun updateStatus(
        workspaceId: UUID,
        oldStatus: ImportJobStatus,
        newStatus: ImportJobStatus
    ): Optional<ImportJob> = jdbc.sql(UPDATE_STATUS_IMPORT_JOB)
        .param("workspaceId", workspaceId)
        .param("oldStatus", oldStatus.name)
        .param("newStatus", newStatus.name)
        .param("finishedAt", timestampProvider.now())
        .query(rowMapper)
        .optional()

    override fun getById(
        workspaceId: UUID,
        id: UUID
    ): ImportJob = jdbc.sql(SELECT_IMPORT_JOB)
        .param("id", id)
        .param("workspaceId", workspaceId)
        .query(rowMapper)
        .optional()
        .orElseThrow { NotFoundEntityException("Import job with id=$id not found for workspace with id=$workspaceId") }

    companion object {
        const val TABLE = "t_import_jobs"

        const val ALL_FIELDS = """
            id, workspace_id, status, started_by, started_at, finished_at, importer_name,
            importer_version, message, accounts, categories, operations, 
            transfers, anchors, diagnostics
        """

        const val CREATE_IMPORT_JOB = """
            INSERT INTO $TABLE (id, workspace_id, status, started_at, started_by, importer_name, importer_version)
            VALUES (:id, :workspaceId, :status, :startedAt, :startedBy, :importerName, :importerVersion)
            RETURNING id
        """

        const val UPDATE_IMPORT_JOB = """
            UPDATE $TABLE SET 
                status = :status,
                finished_at = :finishedAt,
                message = :message,
                accounts = :accounts,
                categories = :categories,
                operations = :operations,
                transfers = :transfers,
                anchors = :anchors,
                diagnostics = CAST(:diagnostics AS jsonb)
            WHERE id = :id and workspace_id = :workspaceId
            RETURNING $ALL_FIELDS
        """

        const val UPDATE_STATUS_IMPORT_JOB = """
            UPDATE $TABLE SET 
                status = :newStatus,
                finished_at = :finishedAt
            WHERE workspace_id = :workspaceId and status = :oldStatus
            RETURNING $ALL_FIELDS
        """

        const val SELECT_IMPORT_JOB = """
            SELECT $ALL_FIELDS
            FROM $TABLE 
            WHERE id = :id AND workspace_id = :workspaceId
        """
    }

}