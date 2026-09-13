package com.github.melancholic.fintrace.core.api.v1.controller

import com.github.melancholic.fintrace.core.api.v1.dto.AccountBalanceResponse
import com.github.melancholic.fintrace.core.api.v1.mapper.AccountBalanceMapper
import com.github.melancholic.fintrace.core.config.STATISTICS_AREA_API_PATH
import com.github.melancholic.fintrace.core.domain.entity.AccountBalance
import com.github.melancholic.fintrace.core.facade.StatisticFacade
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.time.LocalDate
import java.util.*

@RestController
@RequestMapping(STATISTICS_AREA_API_PATH)
@Tag(name = "Statistics")
class StatisticsRestController(
    private val statisticFacade: StatisticFacade,
    private val mapper: AccountBalanceMapper
) {

    @Operation(summary = "List account balances")
    @ApiResponses(
        ApiResponse(
            responseCode = "200",
            description = "One row per account, in its own currency, as of the end of `asOf`"
        ),
        ApiResponse(responseCode = "400", description = "Malformed `asOf` or `includeArchived`"),
        ApiResponse(responseCode = "404", description = "No such workspace for this caller"),
        ApiResponse(responseCode = "403", description = "Not authenticated"),
    )
    @GetMapping("/balances/accounts")
    fun getAllBalancesOf(
        @PathVariable("workspaceId") workspaceId: UUID,
        @RequestParam("asOf", required = false) asOf: LocalDate?,
        @RequestParam(value = "includeArchived", required = false, defaultValue = "true") includeArchived: Boolean
    ): ResponseEntity<List<AccountBalanceResponse>> {
        val balancesList: List<AccountBalance> =
            statisticFacade.getAllBalancesOf(workspaceId, asOf ?: LocalDate.now(), includeArchived)
        return ResponseEntity
            .ok(mapper.toResponseList(balancesList))
    }

}