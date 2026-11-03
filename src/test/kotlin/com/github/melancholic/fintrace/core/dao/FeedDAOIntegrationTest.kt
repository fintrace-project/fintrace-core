package com.github.melancholic.fintrace.core.dao

import com.github.melancholic.fintrace.core.TestWorkspaces
import com.github.melancholic.fintrace.core.TestcontainersConfiguration
import com.github.melancholic.fintrace.core.dao.projection.AccountProjectionDAO
import com.github.melancholic.fintrace.core.dao.projection.BalanceAnchorProjectionDAO
import com.github.melancholic.fintrace.core.dao.projection.CategoryProjectionDAO
import com.github.melancholic.fintrace.core.dao.projection.OperationProjectionDAO
import com.github.melancholic.fintrace.core.domain.entity.AnchorFeedItem
import com.github.melancholic.fintrace.core.domain.entity.DifferenceFeedItem
import com.github.melancholic.fintrace.core.domain.entity.FeedFilter
import com.github.melancholic.fintrace.core.domain.entity.FeedItem
import com.github.melancholic.fintrace.core.domain.entity.FeedItemType
import com.github.melancholic.fintrace.core.domain.entity.FeedSeek
import com.github.melancholic.fintrace.core.domain.entity.OperationFeedItem
import com.github.melancholic.fintrace.core.domain.entity.OperationKind
import com.github.melancholic.fintrace.core.domain.entity.TransferFeedItem
import com.github.melancholic.fintrace.core.domain.entity.TransferSide
import com.github.melancholic.fintrace.core.domain.projection.BalanceAnchorProjection
import com.github.melancholic.fintrace.core.domain.projection.OperationProjection
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Import(TestcontainersConfiguration::class)
@SpringBootTest
class FeedDAOIntegrationTest(
    @Autowired private val feedDAO: FeedDAO,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val workspaceDAO: WorkspaceDAO,
    @Autowired private val usersDAO: UsersDAO,
    @Autowired private val accountDAO: AccountProjectionDAO,
    @Autowired private val categoryDAO: CategoryProjectionDAO,
    @Autowired private val operationDAO: OperationProjectionDAO,
    @Autowired private val anchorDAO: BalanceAnchorProjectionDAO,
) {

    private lateinit var workspaceId: UUID
    private lateinit var accountId: UUID
    private lateinit var otherAccountId: UUID
    private lateinit var categoryId: UUID

    @BeforeEach
    fun clean() {
        TestWorkspaces.reset(jdbc)
        workspaceId = TestWorkspaces.create(workspaceDAO, usersDAO)
        accountId = TestWorkspaces.seedAccount(accountDAO, workspaceId, name = "wallet", currency = "EUR")
        otherAccountId = TestWorkspaces.seedAccount(accountDAO, workspaceId, name = "card", currency = "USD")
        categoryId = TestWorkspaces.seedCategory(categoryDAO, workspaceId)
    }

    @Test
    fun `at one instant the anchor comes first, then its difference, then operations`() {
        anchor(MARCH_01, "0.0000")
        val atAnchor = operation(MARCH_15, "-20.0000")
        val anchorId = anchor(MARCH_15, "50.0000")

        val items = feedDAO.page(accountFeed(), FeedSeek.Newest, 10)

        assertEquals(
            listOf(FeedItemType.BALANCE_ANCHOR, FeedItemType.DIFFERENCE, FeedItemType.OPERATION, FeedItemType.BALANCE_ANCHOR),
            items.map { it.type() }
        )
        assertEquals(anchorId, (items[0] as AnchorFeedItem).id)
        assertEquals(BigDecimal("50.0000"), (items[1] as DifferenceFeedItem).amount)
        assertEquals(atAnchor, (items[2] as OperationFeedItem).id)
    }

    @Test
    fun `a zero difference is no item`() {
        anchor(MARCH_01, "0.0000")
        operation(MARCH_05, "30.0000")
        anchor(MARCH_15, "30.0000")

        val types = feedDAO.page(accountFeed(), FeedSeek.Newest, 10).map { it.type() }

        assertEquals(listOf(FeedItemType.BALANCE_ANCHOR, FeedItemType.OPERATION, FeedItemType.BALANCE_ANCHOR), types)
    }

    @Test
    fun `a transfer is one item with both legs, its side only in an account's feed`() {
        val transferId = UUID.randomUUID()
        TestWorkspaces.seedTransferPair(
            operationDAO, workspaceId, accountId, otherAccountId, transferId = transferId,
            sourceAmount = BigDecimal("10.0000"), targetAmount = BigDecimal("11.0000"), occurredAt = MARCH_05,
        )

        val inWorkspace = feedDAO.page(workspaceFeed(), FeedSeek.Newest, 10).single() as TransferFeedItem
        val inSource = feedDAO.page(accountFeed(), FeedSeek.Newest, 10).single() as TransferFeedItem
        val inTarget = feedDAO.page(accountFeed(otherAccountId), FeedSeek.Newest, 10).single() as TransferFeedItem

        assertEquals(transferId, inWorkspace.id)
        assertEquals("EUR", inWorkspace.source.currency)
        assertEquals("USD", inWorkspace.target.currency)
        assertNull(inWorkspace.side)
        assertEquals(TransferSide.SOURCE to BigDecimal("-10.0000"), inSource.side to inSource.amount)
        assertEquals(TransferSide.TARGET to BigDecimal("11.0000"), inTarget.side to inTarget.amount)
    }

    @Test
    fun `paging older then newer walks the same sequence`() {
        listOf(MARCH_01, MARCH_05, MARCH_10, MARCH_15, MARCH_20).forEach { operation(it, "-1.0000") }
        val all = feedDAO.page(workspaceFeed(), FeedSeek.Newest, 10)

        val first = feedDAO.page(workspaceFeed(), FeedSeek.Newest, 2)
        val second = feedDAO.page(workspaceFeed(), FeedSeek.Older(first.last().position), 2)
        val back = feedDAO.page(workspaceFeed(), FeedSeek.Newer(second.first().position), 2)

        assertEquals(all.subList(0, 2), first)
        assertEquals(all.subList(2, 4), second)
        assertEquals(first, back)
    }

    @Test
    fun `at opens on the item itself`() {
        listOf(MARCH_01, MARCH_05, MARCH_10).forEach { operation(it, "-1.0000") }
        val all = feedDAO.page(workspaceFeed(), FeedSeek.Newest, 10)

        val page = feedDAO.page(workspaceFeed(), FeedSeek.At(all[1].position), 10)

        assertEquals(all.subList(1, 3), page)
    }

    @Test
    fun `exists answers whether anything lies that way`() {
        operation(MARCH_01, "-1.0000")
        val only = feedDAO.page(workspaceFeed(), FeedSeek.Newest, 1).single()

        assertFalse(feedDAO.exists(workspaceFeed(), FeedSeek.Older(only.position)))
        assertFalse(feedDAO.exists(workspaceFeed(), FeedSeek.Newer(only.position)))
        assertTrue(feedDAO.exists(workspaceFeed(), FeedSeek.At(only.position)))
    }

    @Test
    fun `the period is half-open and kinds narrow the items`() {
        anchor(MARCH_01, "0.0000")
        operation(MARCH_05, "-1.0000")
        operation(MARCH_10, "-1.0000")
        anchor(MARCH_15, "100.0000")

        val period = feedDAO.page(accountFeed().copy(from = MARCH_05, to = MARCH_15), FeedSeek.Newest, 10)
        val differences = feedDAO.page(
            accountFeed().copy(types = setOf(FeedItemType.DIFFERENCE)), FeedSeek.Newest, 10
        )

        assertEquals(listOf(MARCH_10, MARCH_05), period.map { it.position.occurredAt })
        assertEquals(listOf(FeedItemType.DIFFERENCE), differences.map { it.type() })
    }

    @Test
    fun `another workspace's rows are never read`() {
        operation(MARCH_01, "-1.0000")
        val other = TestWorkspaces.create(workspaceDAO, usersDAO, name = "other-workspace")

        assertTrue(feedDAO.page(FeedFilter(other, null, null, null, emptySet()), FeedSeek.Newest, 10).isEmpty())
        assertTrue(feedDAO.page(FeedFilter(other, accountId, null, null, emptySet()), FeedSeek.Newest, 10).isEmpty())
    }

    private fun workspaceFeed() = FeedFilter(workspaceId, null, null, null, emptySet())

    private fun accountFeed(account: UUID = accountId) = FeedFilter(workspaceId, account, null, null, emptySet())

    private fun FeedItem.type() = when (this) {
        is OperationFeedItem -> FeedItemType.OPERATION
        is TransferFeedItem -> FeedItemType.TRANSFER
        is AnchorFeedItem -> FeedItemType.BALANCE_ANCHOR
        is DifferenceFeedItem -> FeedItemType.DIFFERENCE
    }

    private fun operation(occurredAt: LocalDateTime, amount: String): UUID = operationDAO.create(
        OperationProjection(
            id = UUID.randomUUID(),
            workspaceId = workspaceId,
            amount = BigDecimal(amount),
            kind = if (amount.startsWith("-")) OperationKind.EXPENSE else OperationKind.INCOME,
            accountId = accountId,
            categoryId = categoryId,
            transferId = null,
            counterpartId = null,
            comment = null,
            externalRef = null,
            occurredAt = occurredAt,
            recordedAt = occurredAt,
        )
    )

    private fun anchor(occurredAt: LocalDateTime, value: String): UUID = anchorDAO.create(
        BalanceAnchorProjection(
            id = UUID.randomUUID(),
            workspaceId = workspaceId,
            accountId = accountId,
            value = BigDecimal(value),
            occurredAt = occurredAt,
            externalRef = null,
            recordedAt = occurredAt,
        )
    )

    private companion object {
        val MARCH_01: LocalDateTime = LocalDateTime.parse("2026-03-01T10:00:00")
        val MARCH_05: LocalDateTime = LocalDateTime.parse("2026-03-05T10:00:00")
        val MARCH_10: LocalDateTime = LocalDateTime.parse("2026-03-10T10:00:00")
        val MARCH_15: LocalDateTime = LocalDateTime.parse("2026-03-15T10:00:00")
        val MARCH_20: LocalDateTime = LocalDateTime.parse("2026-03-20T10:00:00")
    }
}
