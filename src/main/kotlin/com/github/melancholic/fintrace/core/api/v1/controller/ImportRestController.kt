package com.github.melancholic.fintrace.core.api.v1.controller

import com.github.melancholic.fintrace.core.api.v1.dto.ImportEnvelopRequest
import com.github.melancholic.fintrace.core.api.v1.dto.ImportJobResponse
import com.github.melancholic.fintrace.core.api.v1.mapper.ImportMapper
import com.github.melancholic.fintrace.core.config.IMPORT_AREA_API_PATH
import com.github.melancholic.fintrace.core.facade.ImportFacade
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.servlet.mvc.method.annotation.MvcUriComponentsBuilder
import java.util.*

@RestController
@RequestMapping(IMPORT_AREA_API_PATH)
@Tag(name = "Import API")
class ImportRestController(
    private val importFacade: ImportFacade,
    private val mapper: ImportMapper,
) {

    @Operation(summary = "Import a whole workspace in one request")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Imported; body carries the job with its counts"),
        ApiResponse(responseCode = "400", description = "Malformed body"),
        ApiResponse(responseCode = "404", description = "No such workspace for this caller"),
        ApiResponse(
            responseCode = "409",
            description = "The workspace is not NEW, not empty, or an import is already running",
        ),
        ApiResponse(responseCode = "401", description = "Not authenticated"),
        ApiResponse(responseCode = "403", description = "Missing the required role"),
    )
    @PostMapping
    fun importWorkspaceData(
        @PathVariable("workspaceId") workspaceId: UUID,
        @Valid @RequestBody request: ImportEnvelopRequest
    ): ResponseEntity<ImportJobResponse> {
        val import = importFacade.importWorkspaceData(workspaceId, request)

        val location = MvcUriComponentsBuilder
            .fromController(WorkspacesRestController::class.java)
            .path("/{workspaceId}")
            .buildAndExpand(workspaceId)
            .toUri()

        return ResponseEntity.ok()
            .location(location)
            .body(mapper.toResponse(import))
    }

}