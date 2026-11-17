package com.github.melancholic.fintrace.core.facade

import com.github.melancholic.fintrace.core.domain.statistic.AccountBalance
import com.github.melancholic.fintrace.core.domain.statistic.BalancesSeries
import com.github.melancholic.fintrace.core.domain.statistic.Cashflow
import com.github.melancholic.fintrace.core.domain.statistic.CurrencyAmounts
import com.github.melancholic.fintrace.core.domain.statistic.Granularity
import com.github.melancholic.fintrace.core.security.IdentityProvider
import com.github.melancholic.fintrace.core.service.StatisticService
import com.github.melancholic.fintrace.core.service.WorkspaceService
import com.github.melancholic.fintrace.core.validation.AccountValidationService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.util.*

sealed interface StatisticFacade : RestFacade {
    fun getBalancesByAccounts(workspaceId: UUID, asOf: LocalDate, includeArchived: Boolean): List<AccountBalance>
    fun getBalancesByCurrencies(workspaceId: UUID, asOf: LocalDate, includeArchived: Boolean): CurrencyAmounts
    fun getBalancesSeries(
        workspaceId: UUID,
        granularity: Granularity,
        startDate: LocalDate?,
        endDate: LocalDate?,
        accountIds: Set<UUID>,
        includeArchived: Boolean
    ): BalancesSeries

    fun getCashflow(
        workspaceId: UUID,
        granularity: Granularity,
        startDate: LocalDate?,
        endDate: LocalDate?,
        accountIds: Set<UUID>,
        includeArchived: Boolean
    ): Cashflow
}

@Service
@Transactional(readOnly = true)
class StatisticFacadeImpl(
    private val workspaceService: WorkspaceService,
    private val identityProvider: IdentityProvider,
    private val statisticService: StatisticService,
    private val accountValidationService: AccountValidationService
) : StatisticFacade {

    override fun getBalancesByAccounts(
        workspaceId: UUID,
        asOf: LocalDate,
        includeArchived: Boolean
    ): List<AccountBalance> {
        val workspace = workspaceService.requireReadable(identityProvider.currentUserId(), workspaceId)
        return statisticService.getBalancesByAccounts(workspace.id, asOf, includeArchived)
    }

    override fun getBalancesByCurrencies(
        workspaceId: UUID,
        asOf: LocalDate,
        includeArchived: Boolean
    ): CurrencyAmounts {
        val workspace = workspaceService.requireReadable(identityProvider.currentUserId(), workspaceId)
        return statisticService.getBalancesByCurrencies(workspace.id, asOf, includeArchived)
    }

    override fun getBalancesSeries(
        workspaceId: UUID,
        granularity: Granularity,
        startDate: LocalDate?,
        endDate: LocalDate?,
        accountIds: Set<UUID>,
        includeArchived: Boolean
    ): BalancesSeries {
        val workspace = workspaceService.requireReadable(identityProvider.currentUserId(), workspaceId)
        if (accountIds.isNotEmpty()) {
            accountValidationService.checkExists(workspace.id, accountIds, true, "accountIds")
        }
        return statisticService.getBalancesSeries(workspace.id, granularity, startDate, endDate, accountIds, includeArchived)
    }

    override fun getCashflow(
        workspaceId: UUID,
        granularity: Granularity,
        startDate: LocalDate?,
        endDate: LocalDate?,
        accountIds: Set<UUID>,
        includeArchived: Boolean
    ): Cashflow {
        val workspace = workspaceService.requireReadable(identityProvider.currentUserId(), workspaceId)
        if (accountIds.isNotEmpty()) {
            accountValidationService.checkExists(workspace.id, accountIds, true, "accountIds")
        }
        return statisticService.getCashflow(workspace.id, granularity, startDate, endDate, accountIds, includeArchived)    }

}