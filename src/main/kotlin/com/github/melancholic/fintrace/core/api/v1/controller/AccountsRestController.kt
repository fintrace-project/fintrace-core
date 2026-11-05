package com.github.melancholic.fintrace.core.api.v1.controller

import com.github.melancholic.fintrace.core.api.v1.dto.*
import com.github.melancholic.fintrace.core.api.v1.mapper.AccountMapper
import com.github.melancholic.fintrace.core.api.v1.mapper.FeedItemMapper
import com.github.melancholic.fintrace.core.config.ACCOUNTS_AREA_API_PATH
import com.github.melancholic.fintrace.core.domain.command.CreateAccountCommand
import com.github.melancholic.fintrace.core.domain.command.ReviseAccountCommand
import com.github.melancholic.fintrace.core.domain.command.SetAccountArchivedCommand
import com.github.melancholic.fintrace.core.domain.entity.FeedItemType
import com.github.melancholic.fintrace.core.domain.entity.FeedRequest
import com.github.melancholic.fintrace.core.domain.projection.AccountProjection
import com.github.melancholic.fintrace.core.facade.CommandFacade
import com.github.melancholic.fintrace.core.facade.FeedFacade
import com.github.melancholic.fintrace.core.facade.ProjectionFacade
import com.github.melancholic.fintrace.core.service.FeedServiceImpl
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.servlet.mvc.method.annotation.MvcUriComponentsBuilder
import java.time.LocalDateTime
import java.util.*

@RestController
@RequestMapping(ACCOUNTS_AREA_API_PATH)
@Tag(name = "Accounts")
class AccountsRestController(
    private val commandFacade: CommandFacade,
    private val projectionFacade: ProjectionFacade,
    private val feedFacade: FeedFacade,
    private val mapper: AccountMapper,
    private val feedItemMapper: FeedItemMapper
) {

    @Operation(summary = "Create an account")
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "Created; `Location` points at it"),
        ApiResponse(responseCode = "400", description = "Malformed body, or unknown currency"),
        ApiResponse(responseCode = "404", description = "No such workspace for this caller"),
        ApiResponse(responseCode = "409", description = "Workspace is archived"),
    )
    @PostMapping
    fun createNewAccount(
        @PathVariable("workspaceId") workspaceId: UUID,
        @Valid @RequestBody request: CreateAccountRequest
    ): ResponseEntity<AccountResponse> {
        val projection: AccountProjection = commandFacade.processCommand(
            CreateAccountCommand(
                workspaceId = workspaceId,
                name = request.name,
                currency = request.currency,
                icon = request.icon,
                initialBalance = request.initialBalance,
            )
        )

        val location = MvcUriComponentsBuilder
            .fromController(AccountsRestController::class.java)
            .path("/{accountId}")
            .buildAndExpand(workspaceId, projection.id)
            .toUri()

        return ResponseEntity
            .created(location)
            .body(mapper.toResponse(projection))
    }

    @Operation(summary = "Fetch an account")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Found"),
        ApiResponse(responseCode = "404", description = "No such account in this workspace"),
    )
    @GetMapping("/{accountId}")
    fun getAccount(
        @PathVariable("workspaceId") workspaceId: UUID,
        @PathVariable("accountId") accountId: UUID
    ): ResponseEntity<AccountResponse> {
        val projection: AccountProjection = projectionFacade.getAccount(workspaceId, accountId)
        return ResponseEntity
            .ok(mapper.toResponse(projection))
    }

    @Operation(summary = "List accounts")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Every account, never paged. Archived excluded unless `includeArchived`"),
        ApiResponse(responseCode = "404", description = "No such workspace for this caller"),
    )
    @GetMapping
    fun getAccount(
        @PathVariable("workspaceId") workspaceId: UUID,
        @RequestParam(value = "includeArchived", required = false, defaultValue = "false") includeArchived: Boolean
    ): ResponseEntity<ListResponse<AccountResponse>> {
        val projections: List<AccountProjection> = projectionFacade.getAllAccounts(workspaceId, includeArchived)
        return ResponseEntity
            .ok(ListResponse(mapper.toResponseList(projections)))
    }

    @Operation(summary = "Replace an account's name and icon")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Revised; currency is immutable and untouched"),
        ApiResponse(responseCode = "400", description = "Malformed body"),
        ApiResponse(responseCode = "404", description = "No such account in this workspace"),
    )
    @PutMapping("/{accountId}")
    fun reviseAccount(
        @PathVariable("workspaceId") workspaceId: UUID,
        @PathVariable("accountId") accountId: UUID,
        @Valid @RequestBody request: UpdateAccountRequest
    ): ResponseEntity<AccountResponse> {
        val projection: AccountProjection = commandFacade.processCommand(
            ReviseAccountCommand(
                workspaceId = workspaceId,
                accountId = accountId,
                name = request.name,
                icon = request.icon
            )
        )

        return ResponseEntity.ok(mapper.toResponse(projection))
    }

    @Operation(summary = "Archive an account")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Archived, or already archived"),
        ApiResponse(responseCode = "404", description = "No such account in this workspace"),
    )
    @DeleteMapping("/{accountId}")
    fun archiveAccount(
        @PathVariable("workspaceId") workspaceId: UUID,
        @PathVariable("accountId") accountId: UUID,
    ): ResponseEntity<AccountResponse> {
        val projection: AccountProjection = commandFacade.processCommand(
            SetAccountArchivedCommand(
                workspaceId = workspaceId,
                accountId = accountId,
                archived = true
            )
        )
        return ResponseEntity.ok(mapper.toResponse(projection))
    }

    @Operation(summary = "Restore an archived account")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Active, or already active"),
        ApiResponse(responseCode = "404", description = "No such account in this workspace"),
    )
    @PostMapping("/{accountId}/restore")
    fun restoreAccount(
        @PathVariable("workspaceId") workspaceId: UUID,
        @PathVariable("accountId") accountId: UUID
    ): ResponseEntity<AccountResponse> {
        val projection: AccountProjection = commandFacade.processCommand(
            SetAccountArchivedCommand(
                workspaceId = workspaceId,
                accountId = accountId,
                archived = false
            )
        )
        return ResponseEntity.ok(mapper.toResponse(projection))
    }

    @Operation(summary = "Feed of an account's operations, transfers and balance anchors")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Newest first"),
        ApiResponse(responseCode = "400", description = "Malformed cursor, more than one cursor, `size` out of range, or `from` not before `to`"),
        ApiResponse(responseCode = "404", description = "No such account in this workspace"),
    )
    @GetMapping("/{accountId}/feed")
    fun getAccountFeed(
        @PathVariable("workspaceId") workspaceId: UUID,
        @PathVariable("accountId") accountId: UUID,
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
        @RequestParam("kinds", required = false) kinds: Set<FeedItemType> = FeedItemType.ALL_KINDS,
    ): ResponseEntity<PagedListResponse<AccountFeedItemDTO>> {
        val (items, older, newer) = feedFacade.getFeedItems(
            workspaceId = workspaceId,
            accountId = accountId,
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
            .ok(feedItemMapper.toAccountFeed(items, older, newer))
    }
}