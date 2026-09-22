package com.github.melancholic.fintrace.core.api.v1.mapper

import com.github.melancholic.fintrace.core.api.v1.dto.ImportDiagnosticRequest
import com.github.melancholic.fintrace.core.api.v1.dto.ImportJobResponse
import com.github.melancholic.fintrace.core.domain.entity.ImportDiagnostic
import com.github.melancholic.fintrace.core.domain.entity.ImportJob
import org.mapstruct.Mapper

@Mapper(componentModel = "spring")
interface ImportMapper {
    fun toDomainList(dtos: List<ImportDiagnosticRequest>): List<ImportDiagnostic>

    fun toResponse(import: ImportJob): ImportJobResponse
}