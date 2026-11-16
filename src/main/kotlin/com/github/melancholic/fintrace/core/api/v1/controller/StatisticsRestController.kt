package com.github.melancholic.fintrace.core.api.v1.controller

import com.github.melancholic.fintrace.core.api.v1.dto.BalancesByAccountsResponse
import com.github.melancholic.fintrace.core.api.v1.dto.BalancesByCurrenciesResponse
import com.github.melancholic.fintrace.core.api.v1.dto.BalancesSeriesResponse
import com.github.melancholic.fintrace.core.api.v1.mapper.AccountBalanceMapper
import com.github.melancholic.fintrace.core.api.v1.mapper.BalanceSeriesMapper
import com.github.melancholic.fintrace.core.api.v1.mapper.CurrencyAmountsMapper
import com.github.melancholic.fintrace.core.config.STATISTICS_AREA_API_PATH
import com.github.melancholic.fintrace.core.domain.statistic.AccountBalance
import com.github.melancholic.fintrace.core.domain.statistic.Granularity
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
    private val mapper: AccountBalanceMapper,
    private val currencyAmountsMapper: CurrencyAmountsMapper,
    private val balanceSeriesMapper: BalanceSeriesMapper
) {

    @Operation(summary = "List account balances")
    @ApiResponses(
        ApiResponse(
            responseCode = "200",
            description = "One row per account, in its own currency, as of the end of `asOf`"
        ),
        ApiResponse(responseCode = "400", description = "Malformed `asOf` or `includeArchived`"),
        ApiResponse(responseCode = "404", description = "No such workspace for this caller"),
    )
    @GetMapping("/balances/accounts")
    fun getBalancesByAccounts(
        @PathVariable("workspaceId") workspaceId: UUID,
        @RequestParam("asOf", required = false) asOfRequested: LocalDate?,
        @RequestParam(value = "includeArchived", required = false, defaultValue = "true") includeArchived: Boolean
    ): ResponseEntity<BalancesByAccountsResponse> {
        val asOf = asOfRequested ?: LocalDate.now()
        val balancesList: List<AccountBalance> =
            statisticFacade.getBalancesByAccounts(workspaceId, asOf, includeArchived)
        return ResponseEntity
            .ok(
                BalancesByAccountsResponse(
                    asOf = asOf,
                    balances = mapper.toResponseList(balancesList)
                )
            )
    }

    @Operation(summary = "Total balances per currency")
    @ApiResponses(
        ApiResponse(
            responseCode = "200",
            description = "One total per currency the accounts hold, ordered by code, as of the end of `asOf`"
        ),
        ApiResponse(responseCode = "400", description = "Malformed `asOf` or `includeArchived`"),
        ApiResponse(responseCode = "404", description = "No such workspace for this caller"),
    )
    @GetMapping("/balances/currencies")
    fun getBalancesByCurrencies(
        @PathVariable("workspaceId") workspaceId: UUID,
        @RequestParam("asOf", required = false) asOfRequested: LocalDate?,
        @RequestParam(value = "includeArchived", required = false, defaultValue = "true") includeArchived: Boolean
    ): ResponseEntity<BalancesByCurrenciesResponse> {
        val asOf = asOfRequested ?: LocalDate.now()
        val result = statisticFacade.getBalancesByCurrencies(workspaceId, asOf, includeArchived)
        return ResponseEntity
            .ok(
                BalancesByCurrenciesResponse(
                    asOf = asOf,
                    totals = currencyAmountsMapper.toResponse(result)
                )
            )
    }

    @Operation(summary = "Balance over time")
    @ApiResponses(
        ApiResponse(
            responseCode = "200",
            description = "One bucket per calendar period, each the balance per currency at its end; bounds widened to whole buckets"
        ),
        ApiResponse(
            responseCode = "400",
            description = "Missing or unknown `granularity`, more than 2,000 buckets, `from` after `to`, or an `accountIds` not in this workspace"
        ),
        ApiResponse(responseCode = "404", description = "No such workspace for this caller"),
    )
    @GetMapping("/balances/series")
    fun getBalancesSeries(
        @PathVariable("workspaceId") workspaceId: UUID,
        @RequestParam("granularity", required = true) granularity: Granularity,
        @RequestParam("from", required = false) startDate: LocalDate?,
        @RequestParam("to", required = false) endDate: LocalDate?,
        @RequestParam("accountIds", required = false) accountIds: Set<UUID> = setOf(),
        @RequestParam("includeArchived", required = false, defaultValue = "true") includeArchived: Boolean
    ): ResponseEntity<BalancesSeriesResponse> {
        val result =
            statisticFacade.getBalancesSeries(workspaceId, granularity, startDate, endDate, accountIds, includeArchived)
        return ResponseEntity.ok(balanceSeriesMapper.toResponse(result))
    }
}