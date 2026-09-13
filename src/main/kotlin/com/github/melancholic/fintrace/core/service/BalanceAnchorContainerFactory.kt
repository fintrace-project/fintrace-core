package com.github.melancholic.fintrace.core.service

import com.github.melancholic.fintrace.core.dao.BalanceDAO
import com.github.melancholic.fintrace.core.domain.entity.BalanceAnchorContainer
import com.github.melancholic.fintrace.core.domain.projection.BalanceAnchorProjection
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional


interface BalanceAnchorContainerFactory {
    fun buildBalanceAnchorContainer(anchor: BalanceAnchorProjection): BalanceAnchorContainer
}

@Service
@Transactional(propagation = Propagation.MANDATORY)
class BalanceAnchorContainerFactoryImpl(
    private val balanceDAO: BalanceDAO
) : BalanceAnchorContainerFactory {

    override fun buildBalanceAnchorContainer(anchor: BalanceAnchorProjection): BalanceAnchorContainer =
        BalanceAnchorContainer(
            projection = anchor,
            difference = balanceDAO.getUnexplainedDifference(anchor.workspaceId, anchor.id)
        )
}