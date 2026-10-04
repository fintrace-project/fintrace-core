package com.github.melancholic.fintrace.core.util

import org.junit.jupiter.api.Test
import java.util.*
import kotlin.test.assertEquals

/**
 * The one walk behind both the pre-pass's cycle check and the import's dispatch order (2.20).
 * The validator tests see only its cycles; the order is pinned here.
 */
class CategoriesTreeTraverserTest {

    private data class Node(val id: UUID, val parentId: UUID? = null)

    private fun order(vararg nodes: Node): List<UUID> =
        CategoriesTreeTraverser.parentsFirstOrdering(nodes.toList(), Node::id, Node::parentId).map { it.id }

    private fun cycles(vararg nodes: Node): List<Set<UUID>> =
        CategoriesTreeTraverser.findCycles(nodes.toList(), Node::id, Node::parentId)

    // ---------------------------------------------------------------- ordering

    @Test
    fun `orders an empty list as empty`() {
        assertEquals(emptyList(), order())
    }

    /** The event log must not depend on anything but the payload, so untouched items stay put. */
    @Test
    fun `keeps payload order for independent items`() {
        assertEquals(listOf(A, B, C), order(Node(A), Node(B), Node(C)))
    }

    @Test
    fun `leaves an already parents-first list unchanged`() {
        assertEquals(listOf(A, B, C), order(Node(A), Node(B, parentId = A), Node(C, parentId = B)))
    }

    /** The parent moves forward to just before its child; nothing else moves. */
    @Test
    fun `hoists a parent listed after its child`() {
        assertEquals(listOf(A, B, C), order(Node(B, parentId = A), Node(C), Node(A)))
    }

    @Test
    fun `orders a chain listed leaf first root first`() {
        assertEquals(listOf(A, B, C), order(Node(C, parentId = B), Node(B, parentId = A), Node(A)))
    }

    @Test
    fun `keeps siblings in payload order behind their parent`() {
        assertEquals(listOf(A, B, C), order(Node(B, parentId = A), Node(C, parentId = A), Node(A)))
    }

    /** A seeded system category, or an id already reported unresolved, ends the chain. */
    @Test
    fun `treats a parent outside the list as a root`() {
        assertEquals(listOf(A, B), order(Node(B, parentId = A), Node(A, parentId = OUTSIDE)))
    }

    @Test
    fun `emits every item exactly once when several share a parent`() {
        val ordered = order(Node(B, parentId = A), Node(C, parentId = A), Node(D, parentId = B), Node(A))

        assertEquals(listOf(A, B, C, D), ordered)
    }

    // ---------------------------------------------------------------- cycles

    @Test
    fun `finds no cycle in a forest`() {
        assertEquals(emptyList(), cycles(Node(B, parentId = A), Node(A), Node(C, parentId = OUTSIDE)))
    }

    @Test
    fun `finds an item that is its own parent`() {
        assertEquals(listOf(setOf(A)), cycles(Node(A, parentId = A)))
    }

    /** One cycle is found once, whichever member the walk starts from. */
    @Test
    fun `finds a two-node cycle once`() {
        assertEquals(listOf(setOf(A, B)), cycles(Node(A, parentId = B), Node(B, parentId = A)))
    }

    @Test
    fun `leaves an item hanging off a cycle out of it`() {
        assertEquals(
            listOf(setOf(A, B)),
            cycles(Node(D, parentId = A), Node(A, parentId = B), Node(B, parentId = A)),
        )
    }

    @Test
    fun `finds two separate cycles`() {
        assertEquals(
            listOf(setOf(A, B), setOf(C, D)),
            cycles(Node(A, parentId = B), Node(B, parentId = A), Node(C, parentId = D), Node(D, parentId = C)),
        )
    }

    private companion object {
        val A: UUID = UUID.fromString("01930000-0000-7000-8000-00000000000a")
        val B: UUID = UUID.fromString("01930000-0000-7000-8000-00000000000b")
        val C: UUID = UUID.fromString("01930000-0000-7000-8000-00000000000c")
        val D: UUID = UUID.fromString("01930000-0000-7000-8000-00000000000d")
        val OUTSIDE: UUID = UUID.fromString("01930000-0000-7000-8000-0000000000ff")
    }
}
