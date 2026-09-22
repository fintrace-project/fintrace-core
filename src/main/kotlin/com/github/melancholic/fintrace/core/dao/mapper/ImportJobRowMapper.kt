package com.github.melancholic.fintrace.core.dao.mapper

import com.github.melancholic.fintrace.core.domain.entity.ImportDiagnostic
import com.github.melancholic.fintrace.core.domain.entity.ImportJob
import com.github.melancholic.fintrace.core.domain.entity.ImportJobStatus
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Component
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.json.JsonMapper
import java.sql.ResultSet
import java.time.LocalDateTime
import java.util.*

@Component
class ImportJobRowMapper(
    private val mapper: JsonMapper
) : RowMapper<ImportJob> {
    override fun mapRow(
        rs: ResultSet,
        rowNum: Int
    ): ImportJob = ImportJob(
        id = rs.getObject("id", UUID::class.java),
        workspaceId = rs.getObject("workspace_id", UUID::class.java),
        status = ImportJobStatus.valueOf(rs.getString("status")),
        startedBy = rs.getObject("started_by", UUID::class.java),
        startedAt = rs.getObject("started_at", LocalDateTime::class.java),
        finishedAt = rs.getObject("finished_at", LocalDateTime::class.java),
        importerName = rs.getString("importer_name"),
        importerVersion = rs.getString("importer_version"),
        message = rs.getString("message"),
        accounts = rs.getInt("accounts"),
        categories = rs.getInt("categories"),
        operations = rs.getInt("operations"),
        transfers = rs.getInt("transfers"),
        anchors = rs.getInt("anchors"),
        diagnostics = rs.getString("diagnostics")
            ?.let { mapper.readValue(it, object : TypeReference<List<ImportDiagnostic>>() {}) }
            ?: emptyList()
    )
}
