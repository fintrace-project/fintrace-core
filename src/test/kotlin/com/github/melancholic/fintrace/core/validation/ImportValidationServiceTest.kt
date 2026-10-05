package com.github.melancholic.fintrace.core.validation

import com.github.melancholic.fintrace.core.api.v1.dto.ImportAccountRequest
import com.github.melancholic.fintrace.core.api.v1.dto.ImportBalanceAnchorRequest
import com.github.melancholic.fintrace.core.api.v1.dto.ImportCategoryRequest
import com.github.melancholic.fintrace.core.api.v1.dto.ImportOperationRequest
import com.github.melancholic.fintrace.core.api.v1.dto.ImportPayloadRequest
import com.github.melancholic.fintrace.core.api.v1.dto.ImportTransferLegRequest
import com.github.melancholic.fintrace.core.api.v1.dto.ImportTransferRequest
import com.github.melancholic.fintrace.core.dao.projection.CategoryProjectionDAO
import com.github.melancholic.fintrace.core.domain.entity.CategoryKind
import com.github.melancholic.fintrace.core.domain.entity.CategorySystemCode
import com.github.melancholic.fintrace.core.domain.entity.ImportProblem
import com.github.melancholic.fintrace.core.domain.entity.ImportProblemCode
import com.github.melancholic.fintrace.core.domain.entity.OperationKind
import com.github.melancholic.fintrace.core.domain.event.EntityType
import com.github.melancholic.fintrace.core.domain.projection.CategoryProjection
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

    private val validation = ImportValidationServiceImpl(FakeSystemCategoriesDAO())

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
            val problem = idProblems(validation.validate(WORKSPACE, payload)).single()
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
     * reports a collision where there is none, and would refuse every real payload. A section on its
     * own leaves its references dangling, so only the id problems are asserted on.
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

        payloads.forEach { assertEquals(emptyList(), idProblems(validation.validate(WORKSPACE, it)), "for $it") }
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

        val problem = idProblems(problems).single()
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
            idProblems(problems).filter { it.aggregateType == EntityType.OPERATION }.map { it.code }.toSet()
        )
        assertEquals(
            listOf(ImportProblemCode.DUPLICATED_ENTITY_ID_ACROSS_SECTIONS),
            idProblems(problems).filter { it.aggregateType == EntityType.BALANCE_ANCHOR }.map { it.code }
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

    // ---------------------------------------------------------------- category parent references

    /** The section is flat and Core orders it (2.20), so payload order is not the importer's contract. */
    @Test
    fun `accepts a child listed before its parent`() {
        val problems = validation.validate(
            WORKSPACE,
            ImportPayloadRequest(categories = listOf(category(CHILD, parentId = PARENT), category(PARENT)))
        )

        assertEquals(emptyList(), problems)
    }

    @Test
    fun `accepts a chain listed leaf first`() {
        val problems = validation.validate(
            WORKSPACE,
            ImportPayloadRequest(
                categories = listOf(
                    category(GRANDCHILD, parentId = CHILD),
                    category(CHILD, parentId = PARENT),
                    category(PARENT),
                )
            )
        )

        assertEquals(emptyList(), problems)
    }

    /** The one parent that lives outside the payload: seeded with the workspace (§5.1). */
    @Test
    fun `accepts a seeded system category as a parent`() {
        val problems = validation.validate(
            WORKSPACE,
            ImportPayloadRequest(categories = listOf(category(CHILD, parentId = EXPENSE_ROOT)))
        )

        assertEquals(emptyList(), problems)
    }

    /** The missing id is reported, not the referring ones — one absent parent may be named by many children. */
    @Test
    fun `rejects a parent no section defines, and names the missing id`() {
        val problems = validation.validate(
            WORKSPACE,
            ImportPayloadRequest(categories = listOf(category(CHILD, parentId = NOWHERE)))
        )

        val problem = problems.single()
        assertEquals(ImportProblemCode.UNRESOLVED_REFERENCE, problem.code)
        assertEquals(EntityType.CATEGORY, problem.aggregateType)
        assertEquals(setOf(NOWHERE), problem.affectedIDs)
    }

    /** An id that exists in the payload but in the wrong section — the wrong-variable bug in a port. */
    @Test
    fun `rejects a parent that is another section's id`() {
        val problems = validation.validate(
            WORKSPACE,
            ImportPayloadRequest(accounts = listOf(account(CASH)), categories = listOf(category(CHILD, parentId = CASH)))
        )

        val problem = problems.single()
        assertEquals(ImportProblemCode.UNRESOLVED_REFERENCE, problem.code)
        assertEquals(setOf(CASH), problem.affectedIDs)
    }

    @Test
    fun `rejects a system category of another workspace`() {
        val problems = validation.validate(
            WORKSPACE,
            ImportPayloadRequest(categories = listOf(category(CHILD, parentId = FOREIGN_ROOT)))
        )

        assertEquals(ImportProblemCode.UNRESOLVED_REFERENCE, problems.single().code)
    }

    @Test
    fun `collects every missing parent into one problem`() {
        val problems = validation.validate(
            WORKSPACE,
            ImportPayloadRequest(
                categories = listOf(category(CHILD, parentId = NOWHERE), category(GRANDCHILD, parentId = NOWHERE_OTHER))
            )
        )

        assertEquals(setOf(NOWHERE, NOWHERE_OTHER), problems.single().affectedIDs)
    }

    @Test
    fun `names a missing parent once, however many categories refer to it`() {
        val problems = validation.validate(
            WORKSPACE,
            ImportPayloadRequest(
                categories = listOf(category(CHILD, parentId = NOWHERE), category(GRANDCHILD, parentId = NOWHERE))
            )
        )

        assertEquals(setOf(NOWHERE), problems.single().affectedIDs)
    }

    // ---------------------------------------------------------------- operation references (2.21b)

    @Test
    fun `accepts an operation whose account and category are in the payload`() {
        val problems = validation.validate(WORKSPACE, withReferences(operations = listOf(operation(OPERATION))))

        assertEquals(emptyList(), problems)
    }

    /** Null stays legal: the command resolves it to that branch's `Others`. */
    @Test
    fun `accepts an operation without a category`() {
        val problems = validation.validate(
            WORKSPACE,
            withReferences(operations = listOf(operation(OPERATION, categoryId = null)))
        )

        assertEquals(emptyList(), problems)
    }

    @Test
    fun `accepts an operation in a seeded system category`() {
        val problems = validation.validate(
            WORKSPACE,
            withReferences(operations = listOf(operation(OPERATION, categoryId = EXPENSE_OTHERS)))
        )

        assertEquals(emptyList(), problems)
    }

    @Test
    fun `rejects an operation whose account no section defines`() {
        val problems = validation.validate(
            WORKSPACE,
            withReferences(operations = listOf(operation(OPERATION, accountId = NOWHERE)))
        )

        val problem = problems.single()
        assertEquals(ImportProblemCode.UNRESOLVED_REFERENCE, problem.code)
        assertEquals(EntityType.OPERATION, problem.aggregateType)
        assertEquals(setOf(NOWHERE), problem.affectedIDs)
        assertNamesField(problem, "accountId")
    }

    @Test
    fun `rejects an operation whose category no section defines`() {
        val problems = validation.validate(
            WORKSPACE,
            withReferences(operations = listOf(operation(OPERATION, categoryId = NOWHERE)))
        )

        val problem = problems.single()
        assertEquals(setOf(NOWHERE), problem.affectedIDs)
        assertNamesField(problem, "categoryId")
    }

    /** Each id must be in the section its field refers to — an account is not a category, and back. */
    @Test
    fun `rejects references into the wrong section`() {
        val problems = validation.validate(
            WORKSPACE,
            withReferences(operations = listOf(operation(OPERATION, accountId = FOOD, categoryId = CASH)))
        )

        assertEquals(setOf(FOOD), problemNaming(problems, "accountId").affectedIDs)
        assertEquals(setOf(CASH), problemNaming(problems, "categoryId").affectedIDs)
    }

    @Test
    fun `rejects a system category of another workspace as an operation's category`() {
        val problems = validation.validate(
            WORKSPACE,
            withReferences(operations = listOf(operation(OPERATION, categoryId = FOREIGN_ROOT)))
        )

        assertEquals(setOf(FOREIGN_ROOT), problems.single().affectedIDs)
    }

    /** The same missing id named by many operations is reported once. */
    @Test
    fun `names a missing account once, however many operations refer to it`() {
        val problems = validation.validate(
            WORKSPACE,
            withReferences(
                operations = listOf(operation(OPERATION, accountId = NOWHERE), operation(OPERATION_OTHER, accountId = NOWHERE))
            )
        )

        assertEquals(setOf(NOWHERE), problems.single().affectedIDs)
    }

    // ---------------------------------------------------------------- transfer references (2.21c)

    @Test
    fun `accepts a transfer whose legs are both payload accounts`() {
        assertEquals(emptyList(), validation.validate(WORKSPACE, withReferences(transfers = listOf(transfer(TRANSFER)))))
    }

    @Test
    fun `rejects a transfer whose source account is missing, naming the source leg`() {
        val problems = validation.validate(
            WORKSPACE,
            withReferences(transfers = listOf(transfer(TRANSFER, source = NOWHERE)))
        )

        val problem = problems.single()
        assertEquals(ImportProblemCode.UNRESOLVED_REFERENCE, problem.code)
        assertEquals(EntityType.TRANSFER, problem.aggregateType)
        assertEquals(setOf(NOWHERE), problem.affectedIDs)
        assertNamesField(problem, "source.accountId")
    }

    /** The case a copy-paste between the two legs gets wrong: only the target is missing. */
    @Test
    fun `rejects a transfer whose target account is missing, naming the target leg`() {
        val problems = validation.validate(
            WORKSPACE,
            withReferences(transfers = listOf(transfer(TRANSFER, target = NOWHERE)))
        )

        val problem = problems.single()
        assertEquals(setOf(NOWHERE), problem.affectedIDs)
        assertNamesField(problem, "target.accountId")
    }

    @Test
    fun `reports each leg's missing account under its own leg`() {
        val problems = validation.validate(
            WORKSPACE,
            withReferences(transfers = listOf(transfer(TRANSFER, source = NOWHERE, target = NOWHERE_OTHER)))
        )

        assertEquals(2, problems.size)
        assertEquals(setOf(NOWHERE), problemNaming(problems, "source.accountId").affectedIDs)
        assertEquals(setOf(NOWHERE_OTHER), problemNaming(problems, "target.accountId").affectedIDs)
    }

    @Test
    fun `rejects a transfer leg pointing at a category`() {
        val problems = validation.validate(
            WORKSPACE,
            withReferences(transfers = listOf(transfer(TRANSFER, target = FOOD)))
        )

        assertEquals(setOf(FOOD), problems.single().affectedIDs)
    }

    // ---------------------------------------------------------------- balance anchor references (2.21d)

    @Test
    fun `accepts an anchor on a payload account`() {
        assertEquals(emptyList(), validation.validate(WORKSPACE, withReferences(anchors = listOf(anchor(ANCHOR)))))
    }

    @Test
    fun `rejects an anchor whose account no section defines`() {
        val problems = validation.validate(
            WORKSPACE,
            withReferences(anchors = listOf(anchor(ANCHOR, accountId = NOWHERE)))
        )

        val problem = problems.single()
        assertEquals(ImportProblemCode.UNRESOLVED_REFERENCE, problem.code)
        assertEquals(EntityType.BALANCE_ANCHOR, problem.aggregateType)
        assertEquals(setOf(NOWHERE), problem.affectedIDs)
        assertNamesField(problem, "accountId")
    }

    @Test
    fun `rejects an anchor pointing at a category`() {
        val problems = validation.validate(
            WORKSPACE,
            withReferences(anchors = listOf(anchor(ANCHOR, accountId = FOOD)))
        )

        assertEquals(setOf(FOOD), problems.single().affectedIDs)
    }

    // ---------------------------------------------------------------- category parent cycles

    @Test
    fun `rejects a category that is its own parent`() {
        val problems = validation.validate(WORKSPACE, ImportPayloadRequest(categories = listOf(category(A, parentId = A))))

        val problem = problems.single()
        assertEquals(ImportProblemCode.CYCLIC_REFERENCES, problem.code)
        assertEquals(EntityType.CATEGORY, problem.aggregateType)
        assertEquals(setOf(A), problem.affectedIDs)
    }

    /** One cycle is one problem, however many of its members a walk could start from. */
    @Test
    fun `reports a two-node cycle once`() {
        val problems = validation.validate(
            WORKSPACE,
            ImportPayloadRequest(categories = listOf(category(A, parentId = B), category(B, parentId = A)))
        )

        assertEquals(setOf(A, B), problems.single().affectedIDs)
    }

    /** D cannot be dispatched, but it is not defective — naming it would hide where the cycle is. */
    @Test
    fun `reports only the members of a cycle, not a category hanging off it`() {
        val problems = validation.validate(
            WORKSPACE,
            ImportPayloadRequest(
                categories = listOf(category(D, parentId = A), category(A, parentId = B), category(B, parentId = A))
            )
        )

        assertEquals(setOf(A, B), problems.single().affectedIDs)
    }

    @Test
    fun `reports two separate cycles as two problems`() {
        val problems = validation.validate(
            WORKSPACE,
            ImportPayloadRequest(
                categories = listOf(
                    category(A, parentId = B), category(B, parentId = A),
                    category(C, parentId = E), category(E, parentId = C),
                )
            )
        )

        assertEquals(2, problems.size)
        assertEquals(setOf(setOf(A, B), setOf(C, E)), problems.map { it.affectedIDs }.toSet())
        assertTrue(problems.all { it.code == ImportProblemCode.CYCLIC_REFERENCES })
    }

    // ---------------------------------------------------------------- fixtures

    /** Serves the four seeded categories of [WORKSPACE] and one root of another workspace. */
    private class FakeSystemCategoriesDAO : CategoryProjectionDAO {
        private val seeded: Map<UUID, Map<CategorySystemCode, UUID>> = mapOf(
            WORKSPACE to mapOf(
                CategorySystemCode.INCOME_ROOT to INCOME_ROOT,
                CategorySystemCode.INCOME_OTHERS to INCOME_OTHERS,
                CategorySystemCode.EXPENSE_ROOT to EXPENSE_ROOT,
                CategorySystemCode.EXPENSE_OTHERS to EXPENSE_OTHERS,
            ),
            OTHER_WORKSPACE to mapOf(CategorySystemCode.EXPENSE_ROOT to FOREIGN_ROOT),
        )

        override fun getSystemCategories(workspaceId: UUID): Map<CategorySystemCode, CategoryProjection> =
            seeded[workspaceId].orEmpty().mapValues { (code, id) -> systemCategory(workspaceId, id, code) }

        private fun systemCategory(workspaceId: UUID, id: UUID, code: CategorySystemCode) = CategoryProjection(
            id = id,
            workspaceId = workspaceId,
            parentId = null,
            name = code.name,
            kind = if (code.name.startsWith("INCOME")) CategoryKind.INCOME else CategoryKind.EXPENSE,
            archived = false,
            systemCode = code,
            icon = null,
            externalRef = null,
            recordedAt = OCCURRED_AT,
        )

        override fun getById(workspaceId: UUID, categoryId: UUID): CategoryProjection = unsupported()
        override fun create(projection: CategoryProjection): UUID = unsupported()
        override fun update(row: CategoryProjection): CategoryProjection = unsupported()
        override fun getByIdAsOptional(workspaceId: UUID, categoryId: UUID): Optional<CategoryProjection> =
            unsupported()

        override fun getAllCategories(workspaceId: UUID, includeArchived: Boolean): List<CategoryProjection> =
            unsupported()

        override fun findSubtreeIds(workspaceId: UUID, categoryId: UUID): List<UUID> = unsupported()
        override fun getFallbackCategory(workspaceId: UUID, categoryKind: CategoryKind): CategoryProjection =
            unsupported()

        override fun getBySystemCode(workspaceId: UUID, systemCode: CategorySystemCode): CategoryProjection =
            unsupported()

        override fun removeAll(workspaceId: UUID): Unit = unsupported()
        override fun remove(workspaceId: UUID, categoryId: UUID): Unit = unsupported()
        override fun remove(workspaceId: UUID, ids: Set<UUID>): Unit = unsupported()

        private fun unsupported(): Nothing = throw UnsupportedOperationException("the pre-pass reads system categories only")
    }

    /** The id rules' problems only — the tests that build one section on its own leave references dangling. */
    private fun idProblems(problems: List<ImportProblem>): List<ImportProblem> =
        problems.filter { it.code in ID_PROBLEM_CODES }

    /** A payload carrying what the fixtures refer to — the CASH and CARD accounts and the FOOD category. */
    private fun withReferences(
        operations: List<ImportOperationRequest> = emptyList(),
        transfers: List<ImportTransferRequest> = emptyList(),
        anchors: List<ImportBalanceAnchorRequest> = emptyList(),
    ) = ImportPayloadRequest(
        accounts = listOf(account(CASH), account(CARD)),
        categories = listOf(category(FOOD)),
        operations = operations,
        transfers = transfers,
        balanceAnchors = anchors,
    )

    // The field is carried in the message only; the quotes keep "accountId" from matching "source.accountId".
    private fun assertNamesField(problem: ImportProblem, field: String) =
        assertTrue(problem.message.contains("'$field'"), "the problem should name '$field': '${problem.message}'")

    private fun problemNaming(problems: List<ImportProblem>, field: String): ImportProblem =
        problems.single { it.message.contains("'$field'") }

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
        accounts = listOf(account(CASH), account(CARD)),
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

    private fun category(id: UUID, parentId: UUID? = null) = ImportCategoryRequest(
        id = id,
        externalRef = null,
        name = "Food",
        kind = CategoryKind.EXPENSE,
        parentId = parentId,
        icon = null,
    )

    private fun operation(id: UUID, accountId: UUID = CASH, categoryId: UUID? = FOOD) = ImportOperationRequest(
        id = id,
        externalRef = null,
        occurredAt = OCCURRED_AT,
        amount = BigDecimal("10.0000"),
        kind = OperationKind.EXPENSE,
        accountId = accountId,
        categoryId = categoryId,
        comment = null,
    )

    private fun transfer(id: UUID, source: UUID = CASH, target: UUID = CARD) = ImportTransferRequest(
        id = id,
        externalRef = null,
        occurredAt = OCCURRED_AT,
        source = ImportTransferLegRequest(accountId = source, amount = BigDecimal("5.0000")),
        target = ImportTransferLegRequest(accountId = target, amount = BigDecimal("5.0000")),
        comment = null,
    )

    private fun anchor(id: UUID, accountId: UUID = CASH) = ImportBalanceAnchorRequest(
        id = id,
        externalRef = null,
        accountId = accountId,
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

        val ID_PROBLEM_CODES = setOf(
            ImportProblemCode.WRONG_UUID_VERSION,
            ImportProblemCode.DUPLICATED_ENTITY_ID_WITHIN_SECTION,
            ImportProblemCode.DUPLICATED_ENTITY_ID_ACROSS_SECTIONS,
        )

        val OPERATION_OTHER: UUID = UUID.fromString("01930000-0000-7000-8000-00000000001a")

        val OTHER_WORKSPACE: UUID = UUID.fromString("01930000-0000-7000-8000-000000000952")
        val INCOME_ROOT: UUID = UUID.fromString("01930000-0000-7000-8000-000000005e01")
        val INCOME_OTHERS: UUID = UUID.fromString("01930000-0000-7000-8000-000000005e02")
        val EXPENSE_ROOT: UUID = UUID.fromString("01930000-0000-7000-8000-000000005e03")
        val EXPENSE_OTHERS: UUID = UUID.fromString("01930000-0000-7000-8000-000000005e04")
        val FOREIGN_ROOT: UUID = UUID.fromString("01930000-0000-7000-8000-000000005e05")

        val PARENT: UUID = UUID.fromString("01930000-0000-7000-8000-00000000ca01")
        val CHILD: UUID = UUID.fromString("01930000-0000-7000-8000-00000000ca02")
        val GRANDCHILD: UUID = UUID.fromString("01930000-0000-7000-8000-00000000ca03")
        val NOWHERE: UUID = UUID.fromString("01930000-0000-7000-8000-0000000000ff")
        val NOWHERE_OTHER: UUID = UUID.fromString("01930000-0000-7000-8000-0000000000fe")

        // Cycle members
        val A: UUID = UUID.fromString("01930000-0000-7000-8000-00000000c0a1")
        val B: UUID = UUID.fromString("01930000-0000-7000-8000-00000000c0a2")
        val C: UUID = UUID.fromString("01930000-0000-7000-8000-00000000c0a3")
        val D: UUID = UUID.fromString("01930000-0000-7000-8000-00000000c0a4")
        val E: UUID = UUID.fromString("01930000-0000-7000-8000-00000000c0a5")

        val OCCURRED_AT: LocalDateTime = LocalDateTime.parse("2025-03-10T12:00:00")
    }
}
