package com.github.melancholic.fintrace.core.facade

import com.github.melancholic.fintrace.core.domain.entity.CategoryKind
import com.github.melancholic.fintrace.core.domain.statistic.*
import com.github.melancholic.fintrace.core.exception.ValidationError
import com.github.melancholic.fintrace.core.security.IdentityProvider
import com.github.melancholic.fintrace.core.service.StatisticService
import com.github.melancholic.fintrace.core.service.WorkspaceService
import com.github.melancholic.fintrace.core.validation.AccountValidationService
import com.github.melancholic.fintrace.core.validation.CurrencyValidator
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
        currency: String?,
        kind: CategoryKind,
        startDate: LocalDate?,
        endDate: LocalDate?,
        accountIds: Set<UUID>,
        includeArchived: Boolean
    ): CategoryTotals

    fun getTopCategories(
        workspaceId: UUID,
        limit: Int,
        currency: String,
        kind: CategoryKind,
        startDate: LocalDate?,
        endDate: LocalDate?,
        accountIds: Set<UUID>,
        includeArchived: Boolean
    ): TopCategories
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
        return statisticService.getBalancesSeries(
            workspace.id,
            granularity,
            startDate,
            endDate,
            accountIds,
            includeArchived
        )
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
        currency: String?,
        kind: CategoryKind,
        startDate: LocalDate?,
        endDate: LocalDate?,
        accountIds: Set<UUID>,
        includeArchived: Boolean
    ): CategoryTotals {
        val workspace = workspaceService.requireReadable(identityProvider.currentUserId(), workspaceId)
        if (accountIds.isNotEmpty()) {
            accountValidationService.checkExists(workspace.id, accountIds, true, "accountIds")
        }
        if (currency != null) {
            CurrencyValidator.requireKnown(currency, "currency")
        }

        return statisticService.getCategoryTotals(
            workspace.id,
            currency,
            kind,
            startDate,
            endDate,
            accountIds,
            includeArchived
        )
    }

    override fun getTopCategories(
        workspaceId: UUID,
        limit: Int,
        currency: String,
        kind: CategoryKind,
        startDate: LocalDate?,
        endDate: LocalDate?,
        accountIds: Set<UUID>,
        includeArchived: Boolean
    ): TopCategories {
        validateLimit(limit)
        val workspace = workspaceService.requireReadable(identityProvider.currentUserId(), workspaceId)

        if (accountIds.isNotEmpty()) {
            accountValidationService.checkExists(workspace.id, accountIds, true, "accountIds")
        }
        CurrencyValidator.requireKnown(currency, "currency")

        return statisticService.getTopCategories(
            workspace.id,
            limit,
            currency,
            kind,
            startDate,
            endDate,
            accountIds,
            includeArchived
        )
    }

    private fun validateLimit(limit: Int) {
        if (limit !in 1..100) {
            throw ValidationError("Limit must be between 1 and 100", "limit")
        }
    }

}