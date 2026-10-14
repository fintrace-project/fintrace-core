package com.github.melancholic.fintrace.core.util

import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate

object TransactionsUtil {

    fun <T> runInNewTransaction(
        platformTransactionManager: PlatformTransactionManager,
        propagation: Int = TransactionDefinition.PROPAGATION_REQUIRED,
        readOnly: Boolean = false,
        timeoutSeconds: Int = TransactionDefinition.TIMEOUT_DEFAULT,
        action: () -> T
    ): T {
        val transactionTemplate = TransactionTemplate(platformTransactionManager)
        transactionTemplate.propagationBehavior = propagation
        transactionTemplate.isReadOnly = readOnly
        transactionTemplate.timeout = timeoutSeconds
        return transactionTemplate.execute { _ -> action() }
    }
}