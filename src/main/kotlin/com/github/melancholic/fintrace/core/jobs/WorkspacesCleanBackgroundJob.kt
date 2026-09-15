package com.github.melancholic.fintrace.core.jobs

import com.github.melancholic.fintrace.core.config.MDCConstants.MDC_WORKSPACE_ID
import com.github.melancholic.fintrace.core.domain.entity.WorkspacePurgeData
import com.github.melancholic.fintrace.core.service.WorkspaceRetentionService
import com.github.melancholic.fintrace.core.util.TimestampProvider
import com.github.melancholic.fintrace.core.util.TransactionsUtil.runInNewTransaction
import io.github.oshai.kotlinlogging.KotlinLogging
import org.slf4j.MDC
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import java.time.Duration
import java.time.LocalDateTime
import kotlin.system.measureTimeMillis

@Component
class WorkspacesCleanBackgroundJob(
    private val retentionService: WorkspaceRetentionService,
    @Value("\${jobs.cleanWorkspacesBackgroundJob.params.persistPeriod:30d}") private val persistPeriod: Duration,
    private val timestampProvider: TimestampProvider,
    private val platformTransactionManager: PlatformTransactionManager
) {
    init {
        require(persistPeriod.toDays() >= 1) { "persistPeriod must be at least 1 day" }
    }

    @Scheduled(cron = "\${jobs.cleanWorkspacesBackgroundJob.cron}")
    fun execute() {
        val cutoff = evaluateCutoff(persistPeriod)
        logger.info { "Workspace purge started: window=${persistPeriod.toDays()}d, cutoff=$cutoff" }
        var totalCleaned = 0
        val duration = measureTimeMillis {
            lateinit var candidates: List<WorkspacePurgeData>
            do {
                val curTotal = totalCleaned
                candidates = runInNewTransaction(platformTransactionManager, readOnly = true) {
                    retentionService.findDeletedBefore(cutoff)
                }
                logger.debug { "Fetched ${candidates.size} workspaces to purge (cutoff=$cutoff)" }
                candidates.forEach { workspacePurged ->
                    MDC.putCloseable(MDC_WORKSPACE_ID, workspacePurged.id.toString()).use {
                        try {
                            val purged = runInNewTransaction(
                                platformTransactionManager,
                                TransactionDefinition.PROPAGATION_REQUIRES_NEW
                            ) {
                                retentionService.purgeWorkspace(workspacePurged)
                            }
                            if (purged) {
                                totalCleaned++
                                logger.info { "Purged workspace id=${workspacePurged.id}: ${describe(workspacePurged)}" }
                            } else {
                                logger.warn {
                                    "Workspace id=${workspacePurged.id} was not purged: no row matched " +
                                            "(already removed, or no longer DELETED)"
                                }
                            }
                        } catch (e: Exception) {
                            logger.error(e) {
                                "Failed to purge workspace id=${workspacePurged.id}: ${
                                    describe(
                                        workspacePurged
                                    )
                                }"
                            }
                        }
                    }
                }
                if (candidates.isNotEmpty() && curTotal == totalCleaned) {
                    logger.error {
                        "Workspace purge stopped: none of the ${candidates.size} remaining workspaces deleted before $cutoff " +
                                "could be purged (ids=${candidates.map { it.id }}); see the errors above"
                    }
                    break
                }
            } while (candidates.isNotEmpty())
        }
        logger.info { "Workspace purge finished: $totalCleaned purged in ${duration}ms (cutoff=$cutoff)" }
    }

    private fun describe(data: WorkspacePurgeData): String =
        "deleted at ${data.deletedAt} (${evaluateInactiveDays(data.deletedAt)} days ago), " +
                "rows: events=${data.numOfEvents}, accounts=${data.numOfAccounts}, " +
                "categories=${data.numOfCategories}, operations=${data.numOfOperations}, anchors=${data.numOfBalanceAnchors}"

    private fun evaluateInactiveDays(deletedAt: LocalDateTime): Long =
        Duration.between(deletedAt, timestampProvider.now()).toDays()

    private fun evaluateCutoff(persistPeriod: Duration): LocalDateTime =
        timestampProvider.now().minus(persistPeriod)


    companion object {
        private val logger = KotlinLogging.logger {}
    }
}