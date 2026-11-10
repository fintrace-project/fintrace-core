package com.github.melancholic.fintrace.core.api.v1.controller

import com.github.melancholic.fintrace.core.TestWorkspaces
import com.github.melancholic.fintrace.core.TestcontainersConfiguration
import com.github.melancholic.fintrace.core.api.v1.dto.CreateWorkspaceRequest
import com.github.melancholic.fintrace.core.dao.UsersDAO
import com.github.melancholic.fintrace.core.dao.WorkspaceDAO
import com.github.melancholic.fintrace.core.dao.projection.AccountProjectionDAO
import com.github.melancholic.fintrace.core.dao.projection.BalanceAnchorProjectionDAO
import com.github.melancholic.fintrace.core.dao.projection.CategoryProjectionDAO
import com.github.melancholic.fintrace.core.dao.projection.OperationProjectionDAO
import com.github.melancholic.fintrace.core.domain.entity.OperationKind
import com.github.melancholic.fintrace.core.domain.projection.BalanceAnchorProjection
import com.github.melancholic.fintrace.core.domain.projection.OperationProjection
import com.jayway.jsonpath.JsonPath
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The HTTP contract for both feeds (§10.5), through the real filter chain against a real Postgres.
 *
 * Rows are written straight to the projections: what is under test is how they are read.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class FeedRestControllerTest(
    @Autowired private val mvc: MockMvc,
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

    private val workspaceFeed get() = "/api/v1/workspaces/$workspaceId/feed"
    private fun accountFeed(account: UUID = accountId) = "/api/v1/workspaces/$workspaceId/accounts/$account/feed"

    @BeforeEach
    fun clean() {
        TestWorkspaces.reset(jdbc)
        workspaceId = TestWorkspaces.create(workspaceDAO, usersDAO)
        accountId = TestWorkspaces.seedAccount(accountDAO, workspaceId, name = "wallet", currency = "EUR")
        otherAccountId = TestWorkspaces.seedAccount(accountDAO, workspaceId, name = "card", currency = "USD")
        categoryId = TestWorkspaces.seedCategory(categoryDAO, workspaceId)
    }

    @Test
    fun `a bare request answers the newest page in the envelope`() {
        operation(day(1), "-5.0000")
        operation(day(2), "7.0000")

        read(workspaceFeed)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items.length()").value(2))
            .andExpect(jsonPath("$.page.older").doesNotExist())
            .andExpect(jsonPath("$.page.newer").doesNotExist())
            .andExpect(jsonPath("$.items[0].type").value("OPERATION"))
            .andExpect(jsonPath("$.items[0].occurredAt").value("2026-03-02T10:00:00"))
            .andExpect(jsonPath("$.items[0].kind").value("INCOME"))
            .andExpect(jsonPath("$.items[0].currency").value("EUR"))
            .andExpect(jsonPath("$.items[0].accountId").value(accountId.toString()))
            .andExpect(jsonPath("$.items[0].categoryId").value(categoryId.toString()))
            .andExpect(jsonPath("$.items[1].kind").value("EXPENSE"))
    }

    @Test
    fun `an operation's amount is absolute on the wire`() {
        operation(day(1), "-5.0000")

        read(workspaceFeed).andExpect(jsonPath("$.items[0].amount").value(5.0))
    }

    @Test
    fun `an empty workspace answers an empty page`() {
        read(workspaceFeed)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items.length()").value(0))
            .andExpect(jsonPath("$.page").exists())
    }

    @Test
    fun `a cursor is opaque and safe in a query string`() {
        operation(day(1), "-1.0000")

        val cursor = JsonPath.read<String>(body(workspaceFeed), "$.items[0].cursor")

        assertTrue(Regex("[A-Za-z0-9_-]+").matches(cursor), cursor)
    }

    @Test
    fun `walking older reaches the end and newer walks back`() {
        (1..5).forEach { operation(day(it), "-1.0000") }

        val first = body("$workspaceFeed?size=2")
        val second = body("$workspaceFeed?size=2&older=${older(first)}")
        val third = body("$workspaceFeed?size=2&older=${older(second)}")

        assertEquals(listOf(5, 4), days(first))
        assertEquals(listOf(3, 2), days(second))
        assertEquals(listOf(1), days(third))

        assertNull(newer(first))
        assertNotNull(newer(second))
        assertNotNull(newer(third))
        assertNull(older(third))

        val backToSecond = body("$workspaceFeed?size=2&newer=${newer(third)}")
        val backToFirst = body("$workspaceFeed?size=2&newer=${newer(backToSecond)}")

        assertEquals(listOf(3, 2), days(backToSecond))
        assertEquals(listOf(5, 4), days(backToFirst))
        assertNull(newer(backToFirst))
    }

    @Test
    fun `a page that exactly fills the feed says nothing follows`() {
        (1..2).forEach { operation(day(it), "-1.0000") }

        assertNull(older(body("$workspaceFeed?size=2")))
    }

    @Test
    fun `operations at one instant page without repeating or skipping`() {
        val ids = (1..3).map { operation(day(1), "-1.0000") }

        val first = body("$workspaceFeed?size=2")
        val second = body("$workspaceFeed?size=2&older=${older(first)}")

        assertEquals(ids.map { it.toString() }.sortedDescending(), itemIds(first) + itemIds(second))
    }

    @Test
    fun `a page stays put when an operation is added after its cursor was read`() {
        (1..5).forEach { operation(day(it), "-1.0000") }
        val first = body("$workspaceFeed?size=2")

        operation(day(6), "-1.0000")

        // The case offset paging gets wrong (§10.5): day 4 would come round a second time.
        assertEquals(listOf(3, 2), days(body("$workspaceFeed?size=2&older=${older(first)}")))
    }

    @Test
    fun `an operation back-dated behind the cursor appears on the next page`() {
        (1..3).forEach { operation(day(it), "-1.0000") }
        val first = body("$workspaceFeed?size=1")

        operation(day(2).plusHours(1), "-1.0000")

        val next = body("$workspaceFeed?size=3&older=${older(first)}")
        assertEquals(listOf(2, 2, 1), days(next))
    }

    @Test
    fun `at opens on the item and says what lies each way`() {
        (1..5).forEach { operation(day(it), "-1.0000") }
        val second = body("$workspaceFeed?size=2&older=${older(body("$workspaceFeed?size=2"))}")

        val opened = body("$workspaceFeed?size=2&at=${cursor(second)}")

        assertEquals(listOf(3, 2), days(opened))
        assertNotNull(older(opened))
        assertNotNull(newer(opened))
    }

    @Test
    fun `at opens where a deleted item was`() {
        val ids = (1..3).map { operation(day(it), "-1.0000") }
        val page = body(workspaceFeed)
        val atSecond = JsonPath.read<String>(page, "$.items[1].cursor")

        jdbc.sql("DELETE FROM t_operations WHERE id = :id").param("id", ids[1]).update()

        assertEquals(listOf(1), days(body("$workspaceFeed?at=$atSecond")))
    }

    @Test
    fun `a transfer is one item with both legs and no side in the workspace's feed`() {
        val transferId = UUID.randomUUID()
        TestWorkspaces.seedTransferPair(
            operationDAO, workspaceId, accountId, otherAccountId, transferId = transferId,
            sourceAmount = BigDecimal("10.0000"), targetAmount = BigDecimal("11.0000"),
            comment = "to the card", occurredAt = day(1),
        )

        read(workspaceFeed)
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].type").value("TRANSFER"))
            .andExpect(jsonPath("$.items[0].id").value(transferId.toString()))
            .andExpect(jsonPath("$.items[0].comment").value("to the card"))
            .andExpect(jsonPath("$.items[0].source.accountId").value(accountId.toString()))
            .andExpect(jsonPath("$.items[0].source.currency").value("EUR"))
            .andExpect(jsonPath("$.items[0].source.amount").value(10.0))
            .andExpect(jsonPath("$.items[0].target.accountId").value(otherAccountId.toString()))
            .andExpect(jsonPath("$.items[0].target.currency").value("USD"))
            .andExpect(jsonPath("$.items[0].target.amount").value(11.0))
            .andExpect(jsonPath("$.items[0].rate").value(1.1))
            .andExpect(jsonPath("$.items[0].side").doesNotExist())
            .andExpect(jsonPath("$.items[0].amount").doesNotExist())
    }

    @Test
    fun `the period includes from and excludes to`() {
        (1..4).forEach { operation(day(it), "-1.0000") }

        val page = body("$workspaceFeed?from=${day(2)}&to=${day(4)}")

        assertEquals(listOf(3, 2), days(page))
    }

    @Test
    fun `either end of the period may be left open`() {
        (1..3).forEach { operation(day(it), "-1.0000") }

        assertEquals(listOf(3, 2), days(body("$workspaceFeed?from=${day(2)}")))
        assertEquals(listOf(1), days(body("$workspaceFeed?to=${day(2)}")))
    }

    @Test
    fun `kinds narrow the workspace's feed and the page's cursors with it`() {
        operation(day(1), "-1.0000")
        TestWorkspaces.seedTransferPair(operationDAO, workspaceId, accountId, otherAccountId, occurredAt = day(2))

        val operations = body("$workspaceFeed?kinds=OPERATION&size=1")

        assertEquals(listOf("OPERATION"), types(operations))
        // The transfer is newer, but the filtered feed has nothing that way.
        assertNull(newer(operations))
        assertNull(older(operations))
        assertEquals(listOf("TRANSFER"), types(body("$workspaceFeed?kinds=TRANSFER")))
        assertEquals(2, types(body("$workspaceFeed?kinds=OPERATION,TRANSFER")).size)
    }

    @Test
    fun `kinds the workspace's feed never has are refused`() {
        listOf("BALANCE_ANCHOR", "DIFFERENCE", "DIFFERENCE,OPERATION").forEach {
            read("$workspaceFeed?kinds=$it")
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        }
    }

    @Test
    fun `an unknown kind is refused`() {
        read("$workspaceFeed?kinds=NOPE").andExpect(status().isBadRequest)
    }

    @Test
    fun `size is between 1 and 100`() {
        read("$workspaceFeed?size=1").andExpect(status().isOk)
        read("$workspaceFeed?size=100").andExpect(status().isOk)

        listOf(-5, 0, 101).forEach {
            read("$workspaceFeed?size=$it")
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        }
    }

    @Test
    fun `from must be before to`() {
        read("$workspaceFeed?from=${day(2)}&to=${day(1)}").andExpect(status().isBadRequest)
        read("$workspaceFeed?from=${day(2)}&to=${day(2)}").andExpect(status().isBadRequest)
    }

    @Test
    fun `at most one cursor is accepted`() {
        operation(day(1), "-1.0000")
        val cursor = cursor(body(workspaceFeed))

        read("$workspaceFeed?older=$cursor&newer=$cursor").andExpect(status().isBadRequest)
        read("$workspaceFeed?older=$cursor&at=$cursor").andExpect(status().isBadRequest)
    }

    @Test
    fun `a malformed cursor is refused, not a server error`() {
        val base64OfThreeWrongParts = Base64.getUrlEncoder().withoutPadding().encodeToString("a|b|c".toByteArray())

        listOf("garbage", "%25%25%25", base64OfThreeWrongParts).forEach {
            read("$workspaceFeed?older=$it")
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        }
    }

    @Test
    fun `an archived workspace's feeds stay readable`() {
        operation(day(1), "-1.0000")
        jdbc.sql("UPDATE t_workspaces SET status = 'ARCHIVED' WHERE id = :id").param("id", workspaceId).update()

        read(workspaceFeed).andExpect(status().isOk).andExpect(jsonPath("$.items.length()").value(1))
        read(accountFeed()).andExpect(status().isOk).andExpect(jsonPath("$.items.length()").value(1))
    }

    @Test
    fun `someone else's workspace is a 404 on both feeds`() {
        val theirs = workspaceDAO.create(otherUser(), CreateWorkspaceRequest("theirs", "USD"))

        read("/api/v1/workspaces/$theirs/feed").andExpect(status().isNotFound)
        read("/api/v1/workspaces/$theirs/accounts/$accountId/feed").andExpect(status().isNotFound)
    }

    @Test
    fun `both feeds need a caller`() {
        mvc.perform(get(workspaceFeed)).andExpect(status().isUnauthorized)
        mvc.perform(get(accountFeed())).andExpect(status().isUnauthorized)
    }

    @Test
    fun `an account's feed holds only that account's items`() {
        operation(day(1), "-1.0000", accountId)
        operation(day(2), "-1.0000", otherAccountId)

        assertEquals(listOf(1), days(body(accountFeed())))
    }

    @Test
    fun `an account that does not exist or sits in another workspace is a 404`() {
        val elsewhere = TestWorkspaces.create(workspaceDAO, usersDAO, name = "elsewhere")
        val theirAccount = TestWorkspaces.seedAccount(accountDAO, elsewhere)

        read(accountFeed(UUID.randomUUID())).andExpect(status().isNotFound)
        read(accountFeed(theirAccount)).andExpect(status().isNotFound)
    }

    @Test
    fun `an archived account's feed stays readable`() {
        operation(day(1), "-1.0000")
        jdbc.sql("UPDATE t_accounts SET archived = true WHERE id = :id").param("id", accountId).update()

        read(accountFeed()).andExpect(status().isOk).andExpect(jsonPath("$.items.length()").value(1))
    }

    @Test
    fun `a bare request to an account's feed holds its anchors and differences`() {
        anchor(day(1), "0.0000")
        anchor(day(2), "50.0000")

        assertEquals(listOf("BALANCE_ANCHOR", "DIFFERENCE", "BALANCE_ANCHOR"), types(body(accountFeed())))
    }

    @Test
    fun `at one instant come the anchor, its difference, then operations`() {
        anchor(day(1), "0.0000")
        val atTheAnchor = operation(day(5), "-20.0000")
        val anchorId = anchor(day(5), "50.0000")
        val after = operation(day(5).plusNanos(1_000), "-1.0000")

        read(accountFeed())
            .andExpect(jsonPath("$.items[0].id").value(after.toString()))
            .andExpect(jsonPath("$.items[1].type").value("BALANCE_ANCHOR"))
            .andExpect(jsonPath("$.items[1].id").value(anchorId.toString()))
            .andExpect(jsonPath("$.items[1].value").value(50.0))
            .andExpect(jsonPath("$.items[1].difference").doesNotExist())
            .andExpect(jsonPath("$.items[2].type").value("DIFFERENCE"))
            .andExpect(jsonPath("$.items[2].anchorId").value(anchorId.toString()))
            .andExpect(jsonPath("$.items[3].id").value(atTheAnchor.toString()))
    }

    @Test
    fun `a difference keeps its sign`() {
        anchor(day(1), "100.0000")
        anchor(day(2), "70.0000")

        val page = body("${accountFeed()}?kinds=DIFFERENCE&to=${day(2).plusHours(1)}&from=${day(2)}")

        assertEquals(-30.0, JsonPath.read<Number>(page, "$.items[0].amount").toDouble())
    }

    @Test
    fun `an opening anchor has no difference item, whatever its value`() {
        anchor(day(1), "1500.0000")

        assertEquals(listOf("BALANCE_ANCHOR"), types(body(accountFeed())))
    }

    @Test
    fun `a zero difference is no item`() {
        anchor(day(1), "0.0000")
        operation(day(2), "30.0000")
        anchor(day(3), "30.0000")

        assertEquals(listOf("BALANCE_ANCHOR", "OPERATION", "BALANCE_ANCHOR"), types(body(accountFeed())))
    }

    @Test
    fun `a back-dated operation changes a later anchor's difference on the next read`() {
        anchor(day(1), "0.0000")
        anchor(day(3), "30.0000")
        assertEquals(30.0, differenceOf(body("${accountFeed()}?kinds=DIFFERENCE")))

        operation(day(2), "10.0000")

        assertEquals(20.0, differenceOf(body("${accountFeed()}?kinds=DIFFERENCE")))
    }

    @Test
    fun `a transfer in an account's feed says the account's side and own amount`() {
        TestWorkspaces.seedTransferPair(
            operationDAO, workspaceId, accountId, otherAccountId,
            sourceAmount = BigDecimal("10.0000"), targetAmount = BigDecimal("11.0000"), occurredAt = day(1),
        )

        read(accountFeed())
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].type").value("TRANSFER"))
            .andExpect(jsonPath("$.items[0].side").value("SOURCE"))
            .andExpect(jsonPath("$.items[0].amount").value(10.0))
            .andExpect(jsonPath("$.items[0].source.amount").value(10.0))
            .andExpect(jsonPath("$.items[0].target.amount").value(11.0))
            .andExpect(jsonPath("$.items[0].rate").value(1.1))
        read(accountFeed(otherAccountId))
            .andExpect(jsonPath("$.items[0].side").value("TARGET"))
            .andExpect(jsonPath("$.items[0].amount").value(11.0))
    }

    @Test
    fun `kinds narrow an account's feed to its anchors`() {
        anchor(day(1), "0.0000")
        operation(day(2), "1.0000")

        assertEquals(listOf("BALANCE_ANCHOR"), types(body("${accountFeed()}?kinds=BALANCE_ANCHOR")))
    }

    @Test
    fun `an account's feed pages across an anchor and its difference`() {
        anchor(day(1), "0.0000")
        anchor(day(2), "50.0000")
        operation(day(2), "-1.0000")

        val first = body("${accountFeed()}?size=1")
        val second = body("${accountFeed()}?size=1&older=${older(first)}")
        val third = body("${accountFeed()}?size=1&older=${older(second)}")

        assertEquals(listOf("BALANCE_ANCHOR"), types(first))
        assertEquals(listOf("DIFFERENCE"), types(second))
        assertEquals(listOf("OPERATION"), types(third))
    }

    @Test
    fun `the arrows jump between differences and open the feed there`() {
        anchor(day(1), "0.0000")
        anchor(day(2), "50.0000")
        operation(day(3), "-1.0000")
        anchor(day(4), "100.0000")

        val newest = body("${accountFeed()}?kinds=DIFFERENCE&size=1")
        val next = body("${accountFeed()}?kinds=DIFFERENCE&size=1&older=${cursor(newest)}")
        val opened = body("${accountFeed()}?at=${cursor(next)}")

        assertEquals(listOf(4), days(newest))
        assertEquals(listOf(2), days(next))
        assertEquals("DIFFERENCE", types(opened).first())
        assertEquals(2, days(opened).first())
        // The cursor is a position, so it holds with the filter gone: what is newer is found again.
        assertNotNull(newer(opened))
    }

    private fun read(url: String): ResultActions = mvc.perform(get(url).with(user(USER)))

    private fun body(url: String): String = read(url).andExpect(status().isOk).andReturn().response.contentAsString

    private fun older(json: String) = JsonPath.read<String?>(json, "$.page.older")

    private fun newer(json: String) = JsonPath.read<String?>(json, "$.page.newer")

    private fun cursor(json: String) = JsonPath.read<String>(json, "$.items[0].cursor")

    private fun types(json: String) = JsonPath.read<List<String>>(json, "$.items[*].type")

    private fun itemIds(json: String) = JsonPath.read<List<String>>(json, "$.items[*].id")

    private fun days(json: String) = JsonPath.read<List<String>>(json, "$.items[*].occurredAt")
        .map { LocalDateTime.parse(it).dayOfMonth }

    private fun differenceOf(json: String) = JsonPath.read<Number>(json, "$.items[0].amount").toDouble()

    private fun day(day: Int): LocalDateTime = LocalDateTime.of(2026, 3, day, 10, 0)

    private fun operation(occurredAt: LocalDateTime, amount: String, account: UUID = accountId): UUID =
        operationDAO.create(
            OperationProjection(
                id = UUID.randomUUID(),
                workspaceId = workspaceId,
                amount = BigDecimal(amount),
                kind = if (amount.startsWith("-")) OperationKind.EXPENSE else OperationKind.INCOME,
                accountId = account,
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

    private fun otherUser(): UUID {
        usersDAO.createIfAbsent("stub:someone-else", "someone-else")
        return usersDAO.getUserIdByExternalId("stub:someone-else").orElseThrow()
    }

    private companion object {
        val USER = TestWorkspaces.TEST_SUBJECT
    }
}
