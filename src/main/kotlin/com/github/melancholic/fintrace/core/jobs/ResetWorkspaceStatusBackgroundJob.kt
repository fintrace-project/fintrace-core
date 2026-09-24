package com.github.melancholic.fintrace.core.jobs

import com.github.melancholic.fintrace.core.config.MDCConstants.MDC_WORKSPACE_ID
import com.github.melancholic.fintrace.core.config.WorkspaceImportConstants.IMPORT_LEASE_TIME
import com.github.melancholic.fintrace.core.service.ImportLifecycleService
import com.github.melancholic.fintrace.core.util.TransactionsUtil.runInNewTransaction
import io.github.oshai.kotlinlogging.KotlinLogging
import org.slf4j.MDC
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import java.util.*
import kotlin.system.measureTimeMillis

@Component
class ResetWorkspaceStatusBackgroundJob(
    private val importLifecycleService: ImportLifecycleService,
    private val platformTransactionManager: PlatformTransactionManager,
) {

    @Scheduled(cron = "\${jobs.resetWorkspaceStatusBackgroundJob.cron}")
    fun execute() {
        logger.info { "Workspace status reset for abandoned imports starting" }
        var totalReset = 0
        val duration = measureTimeMillis {
            lateinit var candidates: List<UUID>
            do {
                candidates = runInNewTransaction(platformTransactionManager) {
                    importLifecycleService.recoverAbandoned(CHUNK_SIZE)
                }
                logger.info { "Was reset ${candidates.size} workspaces to NEW status due abandoned import (import lease time: $IMPORT_LEASE_TIME)" }
                candidates.forEach { workspaceId ->
                    MDC.putCloseable(MDC_WORKSPACE_ID, workspaceId.toString()).use {
                        logger.debug { "Workspace status reset to NEW due abandoned import" }
                    }
                }
                totalReset += candidates.size
            } while (candidates.isNotEmpty())
        }
        logger.info { "Workspace status reset finished: $totalReset rest in ${duration}ms (import lease time: $IMPORT_LEASE_TIME)" }
    }

    companion object {
        private val logger = KotlinLogging.logger {}
        const val CHUNK_SIZE = 1000
    }
}