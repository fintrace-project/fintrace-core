package com.github.melancholic.fintrace.core.service

import com.github.melancholic.fintrace.core.dao.BalanceDAO
import com.github.melancholic.fintrace.core.domain.entity.AccountBalance
import com.github.melancholic.fintrace.core.domain.entity.CurrencyAmounts
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.util.*

sealed interface StatisticService {

    fun getBalancesByAccounts(workspaceId: UUID, asOf: LocalDate, includeArchived: Boolean): List<AccountBalance>
    fun getBalancesByCurrencies(workspaceId: UUID, asOf: LocalDate, includeArchived: Boolean): CurrencyAmounts

}

@Service
@Transactional(propagation = Propagation.MANDATORY, readOnly = true)
class StatisticServiceImpl(
    private val balanceDAO: BalanceDAO
) : StatisticService {

    override fun getBalancesByAccounts(
        workspaceId: UUID,
        asOf: LocalDate,
        includeArchived: Boolean
    ): List<AccountBalance> =
        balanceDAO.getBalancesByAccounts(workspaceId, asOf, includeArchived)

    override fun getBalancesByCurrencies(
        workspaceId: UUID,
        asOf: LocalDate,
        includeArchived: Boolean
    ): CurrencyAmounts {
        return CurrencyAmounts(
            byCurrency = balanceDAO.getBalancesByCurrencies(workspaceId, asOf, includeArchived)
        )
    }
}