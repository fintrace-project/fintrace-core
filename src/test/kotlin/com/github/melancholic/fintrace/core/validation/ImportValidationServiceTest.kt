package com.github.melancholic.fintrace.core.validation

import com.github.melancholic.fintrace.core.api.v1.dto.ImportAccountRequest
import com.github.melancholic.fintrace.core.api.v1.dto.ImportBalanceAnchorRequest
import com.github.melancholic.fintrace.core.api.v1.dto.ImportCategoryRequest
import com.github.melancholic.fintrace.core.api.v1.dto.ImportOperationRequest
import com.github.melancholic.fintrace.core.api.v1.dto.ImportPayloadRequest
import com.github.melancholic.fintrace.core.api.v1.dto.ImportTransferLegRequest
import com.github.melancholic.fintrace.core.api.v1.dto.ImportTransferRequest
import com.github.melancholic.fintrace.core.domain.entity.CategoryKind
import com.github.melancholic.fintrace.core.domain.entity.ImportProblem
import com.github.melancholic.fintrace.core.domain.entity.ImportProblemCode
import com.github.melancholic.fintrace.core.domain.entity.OperationKind
import com.github.melancholic.fintrace.core.domain.event.EntityType
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The pre-pass's id rules, checked without Spring or a database (2.20) — the same shape as the other
 * validation tests.
 *
 * Why v7 is enforced at all: nothing in Core reads a UUID's version, but four statements order
 * anchors by `id` as a tiebreaker (§4.6), and that reads as creation order only while every id is
 * v7. The check is a contract assertion, so these tests are about the *report* it produces.
 */
class ImportValidationServiceTest {

    private val validation = ImportValidationServiceImpl()

    @Test
    fun `accepts a payload whose ids are all version 7`() {
        val problems = validation.validate(WORKSPACE, fullPayload())

        assertEquals(emptyList(), problems)
    }

    @Test
    fun `accepts an empty payload`() {
        assertEquals(emptyList(), validation.validate(WORKSPACE, ImportPayloadRequest()))
    }

    @Test
    fun `rejects a version 4 account id, and names the account section`() {
        val problems = validation.validate(WORKSPACE, ImportPayloadRequest(accounts = listOf(account(V4))))

        val problem = problems.single()
        assertEquals(ImportProblemCode.WRONG_UUID_VERSION, problem.code)
        assertEquals(EntityType.ACCOUNT, problem.aggregateType)
        assertEquals(setOf(V4), problem.affectedIDs)
    }

    /**
     * One problem per section carrying every offending id, rather than one problem per id — a dump
     * with 300 bad ids has to be reportable in one answer (2.21).
     */
    @Test
    fun `collects every offending id in a section into one problem`() {
        val problems = validation.validate(
            WORKSPACE,
            ImportPayloadRequest(accounts = listOf(account(V4), account(V4_OTHER), account(CASH)))
        )

        assertEquals(setOf(V4, V4_OTHER), problems.single().affectedIDs)
    }

    /**
     * The section each problem names is what tells the importer author where to look, so every
     * section must report its own — a single hard-coded `EntityType` would misdirect four of five.
     */
    @Test
    fun `names the right section for each kind of entity`() {
        val expected = mapOf(
            EntityType.ACCOUNT to ImportPayloadRequest(accounts = listOf(account(V4))),
            EntityType.CATEGORY to ImportPayloadRequest(categories = listOf(category(V4))),
            EntityType.OPERATION to ImportPayloadRequest(operations = listOf(operation(V4))),
            EntityType.TRANSFER to ImportPayloadRequest(transfers = listOf(transfer(V4))),
            EntityType.BALANCE_ANCHOR to ImportPayloadRequest(balanceAnchors = listOf(anchor(V4))),
        )

        expected.forEach { (entityType, payload) ->
            val problem = validation.validate(WORKSPACE, payload).single()
            assertEquals(entityType, problem.aggregateType, "the problem should name $entityType")
            assertEquals(setOf(V4), problem.affectedIDs)
        }
    }

    @Test
    fun `reports each section separately when several carry bad ids`() {
        val problems = validation.validate(
            WORKSPACE,
            ImportPayloadRequest(accounts = listOf(account(V4)), categories = listOf(category(V4_OTHER)))
        )

        assertEquals(2, problems.size)
        assertEquals(
            setOf(EntityType.ACCOUNT, EntityType.CATEGORY),
            problems.map { it.aggregateType }.toSet()
        )
    }

    @Test
    fun `the message says which version was required`() {
        val problem = validation.validate(WORKSPACE, ImportPayloadRequest(accounts = listOf(account(V4)))).single()

        assertTrue(problem.message.contains("7"), "the message should name the required version: '${problem.message}'")
    }

    // ---------------------------------------------------------------- id uniqueness

    /**
     * Every section on its own with a distinct v7 id — a uniqueness check that mislabels a section
     * reports a collision where there is none, and would refuse every real payload.
     */
    @Test
    fun `accepts each section on its own when its ids are distinct`() {
        val payloads = listOf(
            ImportPayloadRequest(accounts = listOf(account(CASH), account(CARD))),
            ImportPayloadRequest(categories = listOf(category(FOOD))),
            ImportPayloadRequest(operations = listOf(operation(OPERATION))),
            ImportPayloadRequest(transfers = listOf(transfer(TRANSFER))),
            ImportPayloadRequest(balanceAnchors = listOf(anchor(ANCHOR))),
        )

        payloads.forEach { assertEquals(emptyList(), validation.validate(WORKSPACE, it), "for $it") }
    }

    /**
     * The case a per-section pass cannot see. Both sections are named, because the importer author
     * cannot tell from one side alone which of the two entities got the wrong id.
     */
    @Test
    fun `rejects an id shared by two sections, and names both`() {
        val problems = validation.validate(
            WORKSPACE,
            ImportPayloadRequest(accounts = listOf(account(SHARED)), categories = listOf(category(SHARED)))
        )

        assertEquals(setOf(EntityType.ACCOUNT, EntityType.CATEGORY), sectionsNaming(problems, SHARED))
        assertTrue(problems.all { it.code == ImportProblemCode.DUPLICATED_ENTITY_ID_ACROSS_SECTIONS })
    }

    @Test
    fun `rejects an id shared by any pair of sections`() {
        val sections: Map<EntityType, (UUID) -> ImportPayloadRequest> = mapOf(
            EntityType.ACCOUNT to { id -> ImportPayloadRequest(accounts = listOf(account(id))) },
            EntityType.CATEGORY to { id -> ImportPayloadRequest(categories = listOf(category(id))) },
            EntityType.OPERATION to { id -> ImportPayloadRequest(operations = listOf(operation(id))) },
            EntityType.TRANSFER to { id -> ImportPayloadRequest(transfers = listOf(transfer(id))) },
            EntityType.BALANCE_ANCHOR to { id -> ImportPayloadRequest(balanceAnchors = listOf(anchor(id))) },
        )

        sections.keys.forEach { a ->
            sections.keys.filter { it > a }.forEach { b ->
                val payload = merge(sections.getValue(a)(SHARED), sections.getValue(b)(SHARED))
                val problems = validation.validate(WORKSPACE, payload)

                assertEquals(setOf(a, b), sectionsNaming(problems, SHARED), "for $a and $b")
            }
        }
    }

    /**
     * A repeat inside one section is the same defect: without the pre-pass it surfaces as the
     * primary key's 409 thousands of commands into the import (2.21).
     */
    @Test
    fun `rejects an id repeated within one section`() {
        val problems = validation.validate(
            WORKSPACE,
            ImportPayloadRequest(operations = listOf(operation(SHARED), operation(SHARED)))
        )

        val problem = problems.single()
        assertEquals(ImportProblemCode.DUPLICATED_ENTITY_ID_WITHIN_SECTION, problem.code)
        assertEquals(EntityType.OPERATION, problem.aggregateType)
        assertEquals(setOf(SHARED), problem.affectedIDs)
    }

    /** The two defects are distinct and an id can have both, so the section reports both. */
    @Test
    fun `reports an id repeated within a section and shared with another as two problems`() {
        val problems = validation.validate(
            WORKSPACE,
            ImportPayloadRequest(
                operations = listOf(operation(SHARED), operation(SHARED)),
                balanceAnchors = listOf(anchor(SHARED)),
            )
        )

        assertEquals(
            setOf(ImportProblemCode.DUPLICATED_ENTITY_ID_WITHIN_SECTION, ImportProblemCode.DUPLICATED_ENTITY_ID_ACROSS_SECTIONS),
            problems.filter { it.aggregateType == EntityType.OPERATION }.map { it.code }.toSet()
        )
        assertEquals(
            listOf(ImportProblemCode.DUPLICATED_ENTITY_ID_ACROSS_SECTIONS),
            problems.filter { it.aggregateType == EntityType.BALANCE_ANCHOR }.map { it.code }
        )
    }

    @Test
    fun `collects every shared id in a section into one problem`() {
        val problems = validation.validate(
            WORKSPACE,
            ImportPayloadRequest(
                accounts = listOf(account(SHARED), account(SHARED_OTHER), account(CASH)),
                categories = listOf(category(SHARED), category(SHARED_OTHER), category(FOOD)),
            )
        )

        val accountProblem = problems.single { it.aggregateType == EntityType.ACCOUNT }
        assertEquals(setOf(SHARED, SHARED_OTHER), accountProblem.affectedIDs)
    }

    /** Collect, don't fail fast (2.21): a payload with both defects reports both. */
    @Test
    fun `reports a shared id and a bad version together`() {
        val problems = validation.validate(
            WORKSPACE,
            ImportPayloadRequest(
                accounts = listOf(account(SHARED), account(V4)),
                categories = listOf(category(SHARED)),
            )
        )

        assertEquals(setOf(EntityType.ACCOUNT, EntityType.CATEGORY), sectionsNaming(problems, SHARED))
        assertEquals(setOf(EntityType.ACCOUNT), sectionsNaming(problems, V4))
    }

    // ---------------------------------------------------------------- fixtures

    private fun sectionsNaming(problems: List<ImportProblem>, id: UUID): Set<EntityType> =
        problems.filter { id in it.affectedIDs }.map { it.aggregateType }.toSet()

    private fun merge(a: ImportPayloadRequest, b: ImportPayloadRequest) = ImportPayloadRequest(
        accounts = a.accounts + b.accounts,
        categories = a.categories + b.categories,
        operations = a.operations + b.operations,
        transfers = a.transfers + b.transfers,
        balanceAnchors = a.balanceAnchors + b.balanceAnchors,
    )

    private fun fullPayload() = ImportPayloadRequest(
        accounts = listOf(account(CASH)),
        categories = listOf(category(FOOD)),
        operations = listOf(operation(OPERATION)),
        transfers = listOf(transfer(TRANSFER)),
        balanceAnchors = listOf(anchor(ANCHOR)),
    )

    private fun account(id: UUID) = ImportAccountRequest(
        id = id,
        externalRef = null,
        name = "Cash",
        currency = "EUR",
        icon = null,
        archived = false,
        initialBalance = null,
        initialBalanceAt = null,
    )

    private fun category(id: UUID) = ImportCategoryRequest(
        id = id,
        externalRef = null,
        name = "Food",
        kind = CategoryKind.EXPENSE,
        parentId = null,
        icon = null,
    )

    private fun operation(id: UUID) = ImportOperationRequest(
        id = id,
        externalRef = null,
        occurredAt = OCCURRED_AT,
        amount = BigDecimal("10.0000"),
        kind = OperationKind.EXPENSE,
        accountId = CASH,
        categoryId = FOOD,
        comment = null,
    )

    private fun transfer(id: UUID) = ImportTransferRequest(
        id = id,
        externalRef = null,
        occurredAt = OCCURRED_AT,
        source = ImportTransferLegRequest(accountId = CASH, amount = BigDecimal("5.0000")),
        target = ImportTransferLegRequest(accountId = CARD, amount = BigDecimal("5.0000")),
        comment = null,
    )

    private fun anchor(id: UUID) = ImportBalanceAnchorRequest(
        id = id,
        externalRef = null,
        accountId = CASH,
        occurredAt = OCCURRED_AT,
        value = BigDecimal("100.0000"),
    )

    private companion object {
        val WORKSPACE: UUID = UUID.fromString("01930000-0000-7000-8000-000000000951")

        val CASH: UUID = UUID.fromString("01930000-0000-7000-8000-00000000cca1")
        val CARD: UUID = UUID.fromString("01930000-0000-7000-8000-00000000cca2")
        val FOOD: UUID = UUID.fromString("01930000-0000-7000-8000-0000000000f0")
        val OPERATION: UUID = UUID.fromString("01930000-0000-7000-8000-00000000000a")
        val TRANSFER: UUID = UUID.fromString("01930000-0000-7000-8000-00000000000b")
        val ANCHOR: UUID = UUID.fromString("01930000-0000-7000-8000-00000000000c")

        /** Version 4 — the nibble after the third dash is what `UUID.version()` reads. */
        val V4: UUID = UUID.fromString("01930000-0000-4000-8000-00000000dead")
        val V4_OTHER: UUID = UUID.fromString("01930000-0000-4000-8000-00000000beef")

        val SHARED: UUID = UUID.fromString("01930000-0000-7000-8000-0000000005a1")
        val SHARED_OTHER: UUID = UUID.fromString("01930000-0000-7000-8000-0000000005a2")

        val OCCURRED_AT: LocalDateTime = LocalDateTime.parse("2025-03-10T12:00:00")
    }
}
