package com.github.melancholic.fintrace.core.facade

import com.github.melancholic.fintrace.core.domain.entity.CategoryKind
import com.github.melancholic.fintrace.core.domain.statistic.*
import com.github.melancholic.fintrace.core.exception.CoreErrorCode
import com.github.melancholic.fintrace.core.exception.OperationNotSupported
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

    fun getCategoryTotals(
        workspaceId: UUID,
        kind: CategoryKind,
        mode: CategoryTotalsMode,
        startDate: LocalDate?,
        endDate: LocalDate?,
        accountIds: Set<UUID>,
        includeArchived: Boolean
    ): CategoryTotals
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
        return statisticService.getCashflow(workspace.id, granularity, startDate, endDate, accountIds, includeArchived)
    }

    override fun getCategoryTotals(
        workspaceId: UUID,
        kind: CategoryKind,
        mode: CategoryTotalsMode,
        startDate: LocalDate?,
        endDate: LocalDate?,
        accountIds: Set<UUID>,
        includeArchived: Boolean
    ): CategoryTotals {
        val workspace = workspaceService.requireReadable(identityProvider.currentUserId(), workspaceId)
        if (accountIds.isNotEmpty()) {
            accountValidationService.checkExists(workspace.id, accountIds, true, "accountIds")
        }

        return when (mode) {
            CategoryTotalsMode.EXPANDED -> statisticService.getCategoryTotalsExpanded(workspace.id, kind, startDate, endDate, accountIds, includeArchived)
            else -> throw OperationNotSupported(CoreErrorCode.CATEGORY_TOTALS_UNSUPPORTED_MODE, "Mode `$mode` is not supported")
        }
    }

}