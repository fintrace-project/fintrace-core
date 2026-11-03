package com.github.melancholic.fintrace.core.api.v1.controller

import com.github.melancholic.fintrace.core.api.v1.dto.*
import com.github.melancholic.fintrace.core.api.v1.mapper.FeedItemMapper
import com.github.melancholic.fintrace.core.api.v1.mapper.WorkspaceMapper
import com.github.melancholic.fintrace.core.config.WORKSPACES_AREA_API_PATH
import com.github.melancholic.fintrace.core.domain.entity.FeedItemType
import com.github.melancholic.fintrace.core.domain.entity.FeedRequest
import com.github.melancholic.fintrace.core.domain.entity.Workspace
import com.github.melancholic.fintrace.core.facade.FeedFacade
import com.github.melancholic.fintrace.core.facade.WorkspaceFacade
import com.github.melancholic.fintrace.core.service.FeedServiceImpl
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.data.web.PageableDefault
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.servlet.mvc.method.annotation.MvcUriComponentsBuilder
import java.time.LocalDateTime
import java.util.*

@RestController
@RequestMapping(WORKSPACES_AREA_API_PATH)
@Tag(name = "Workspaces")
class WorkspacesRestController(
    private val workspaceFacade: WorkspaceFacade,
    private val mapper: WorkspaceMapper,
    private val feedFacade: FeedFacade,
    private val feedItemMapper: FeedItemMapper
) {

    @Operation(summary = "Create a workspace")
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "Created; `Location` points at it"),
        ApiResponse(responseCode = "400", description = "Malformed body, or unknown currency"),
    )
    @PostMapping
    fun createNewWorkspace(
        @Valid @RequestBody request: CreateWorkspaceRequest
    ): ResponseEntity<WorkspaceResponse> {
        val workspace: Workspace = workspaceFacade.createWorkspace(request)

        val location = MvcUriComponentsBuilder
            .fromController(WorkspacesRestController::class.java)
            .path("/{workspaceId}")
            .buildAndExpand(workspace.id)
            .toUri()

        return ResponseEntity
            .created(location)
            .body(mapper.toResponse(workspace))
    }

    @Operation(summary = "Fetch a workspace")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Found"),
        ApiResponse(responseCode = "404", description = "No such workspace for this caller"),
    )
    @GetMapping("/{workspaceId}")
    fun getWorkspace(
        @PathVariable("workspaceId") workspaceId: UUID
    ): ResponseEntity<WorkspaceResponse> {
        val workspace: Workspace = workspaceFacade.getWorkspace(workspaceId)

        return ResponseEntity
            .ok(mapper.toResponse(workspace))
    }

    @Operation(summary = "List the caller's workspaces")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Sortable by name, status, createdAt"),
        ApiResponse(responseCode = "400", description = "Sort requested on an unsupported field"),
    )
    @GetMapping
    fun getWorkspaces(
        @PageableDefault(sort = ["createdAt"], direction = Sort.Direction.DESC) page: Pageable
    ): ResponseEntity<List<WorkspaceResponse>> {
        val workspaces: List<Workspace> = workspaceFacade.getWorkspaces(page)

        return ResponseEntity
            .ok(mapper.toResponseList(workspaces))
    }

    @Operation(summary = "Replace a workspace's name and currency")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Revised; body carries the new version"),
        ApiResponse(responseCode = "400", description = "Malformed body, or unknown currency"),
        ApiResponse(responseCode = "404", description = "No such workspace for this caller"),
        ApiResponse(responseCode = "409", description = "`version` is stale"),
    )
    @PutMapping("/{workspaceId}")
    fun editWorkspace(
        @PathVariable("workspaceId") workspaceId: UUID,
        @Valid @RequestBody request: EditWorkspaceRequest
    ): ResponseEntity<WorkspaceResponse> {
        val updatedWorkspace = workspaceFacade.editWorkspace(workspaceId, request)
        return ResponseEntity.ok(updatedWorkspace)
    }

    @Operation(summary = "Archive a workspace")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Archived, or already archived"),
        ApiResponse(responseCode = "404", description = "No such workspace for this caller"),
        ApiResponse(responseCode = "409", description = "Not ACTIVE"),
    )
    @PostMapping("/{workspaceId}/archive")
    fun archiveWorkspace(
        @PathVariable("workspaceId") workspaceId: UUID,
    ): ResponseEntity<WorkspaceResponse> {
        val updatedWorkspace = workspaceFacade.archiveWorkspace(workspaceId)
        return ResponseEntity.ok(updatedWorkspace)
    }

    @Operation(summary = "Unarchive a workspace")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Active, or already active"),
        ApiResponse(responseCode = "404", description = "No such workspace for this caller"),
    )
    @DeleteMapping("/{workspaceId}/archive")
    fun unarchiveWorkspace(
        @PathVariable("workspaceId") workspaceId: UUID,
    ): ResponseEntity<WorkspaceResponse> {
        val updatedWorkspace = workspaceFacade.unarchiveWorkspace(workspaceId)
        return ResponseEntity.ok(updatedWorkspace)
    }


    @Operation(summary = "Delete a workspace")
    @ApiResponses(
        ApiResponse(responseCode = "204", description = "Deleted; unrecoverable"),
        ApiResponse(responseCode = "400", description = "`version` missing"),
        ApiResponse(responseCode = "404", description = "No such workspace for this caller"),
        ApiResponse(responseCode = "409", description = "`version` is stale"),
    )
    @DeleteMapping("/{workspaceId}")
    fun deleteWorkspace(
        @PathVariable("workspaceId") workspaceId: UUID,
        @RequestParam("version") version: Long,
    ): ResponseEntity<Void> {
        workspaceFacade.deleteWorkspace(workspaceId, version)
        return ResponseEntity.noContent().build()
    }

    @Operation(summary = "Feed of workspace's operations, across all accounts")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Newest first"),
        ApiResponse(responseCode = "400", description = "Malformed cursor, more than one cursor, `size` out of range, `from` not before `to`, or a kind this feed never has"),
        ApiResponse(responseCode = "404", description = "No such workspace for this caller"),
    )
    @GetMapping("/{workspaceId}/feed")
    fun getWorkspaceFeed(
        @PathVariable("workspaceId") workspaceId: UUID,
        @Parameter(schema = Schema(implementation = Int::class, minimum = "1", maximum = "${FeedServiceImpl.MAX_PAGE_SIZE}", defaultValue = "50"))
        @RequestParam("size", defaultValue = "50", required = false) size: Int = 50,
        @Parameter(description = "Page after this cursor; at most one of `older`, `newer`, `at`")
        @RequestParam("older", required = false) older: String? = null,
        @Parameter(description = "Page before this cursor; at most one of `older`, `newer`, `at`")
        @RequestParam("newer", required = false) newer: String? = null,
        @Parameter(description = "Page opening at this cursor; at most one of `older`, `newer`, `at`")
        @RequestParam("at", required = false) at: String? = null,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
        @RequestParam("from", required = false) from: LocalDateTime? = null,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
        @RequestParam("to", required = false) to: LocalDateTime? = null,
        @Parameter(description = "`OPERATION` and `TRANSFER` only")
        @RequestParam("kinds", required = false) kinds: Set<FeedItemType> = FeedItemType.WORKSPACE_FEED_KINDS,
    ): ResponseEntity<PagedListResponse<WorkspaceFeedItemDTO>> {
        val (items, older, newer) = feedFacade.getFeedItems(
            workspaceId = workspaceId,
            request = FeedRequest(
                size = size,
                older = older,
                newer = newer,
                at = at,
                from = from,
                to = to,
                kinds = kinds
            )
        )

        return ResponseEntity
            .ok(feedItemMapper.toWorkspaceFeed(items, older, newer))
    }
}