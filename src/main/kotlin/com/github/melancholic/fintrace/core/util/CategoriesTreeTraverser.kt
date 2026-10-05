package com.github.melancholic.fintrace.core.util

import java.util.*

object CategoriesTreeTraverser {

    private fun <T> traverse(items: List<T>, id: (T) -> UUID, parentId: (T) -> UUID?): ParentOrdering<T> {
        val visited = linkedSetOf<UUID>()
        val cycles = mutableListOf<Set<UUID>>()

        val nodes: Map<UUID, T> = items.associateBy { id(it) }

        for (item in items) {
            if (visited.contains(id(item))) {
                continue
            }
            val path = linkedSetOf<UUID>()
            var cur: T? = item
            while (cur != null && !visited.contains(id(cur))) {
                if (path.contains(id(cur))) {
                    cycles.add(path.dropWhile { it != id(cur) }.toSet())
                    break
                } else {
                    path.add(id(cur))
                    cur = nodes[parentId(cur)]
                }
            }
            visited.addAll(path.reversed())
        }
        return ParentOrdering(
            visited.asSequence()
                .map { nodes.getValue(it) }
                .toList(),
            cycles
        )
    }

    fun <T> findCycles(
        items: List<T>,
        id: (T) -> UUID,
        parentId: (T) -> UUID?
    ): List<Set<UUID>> = traverse(items, id, parentId).cycles

    fun <T> parentsFirstOrdering(
        items: List<T>,
        id: (T) -> UUID,
        parentId: (T) -> UUID?
    ): List<T> {
        val res = traverse(items, id, parentId)
        check(res.cycles.isEmpty()) { "Categories tree can't be ordered: cycles among ${res.cycles}" }
        return res.ordered
    }

    private data class ParentOrdering<T>(val ordered: List<T>, val cycles: List<Set<UUID>>)
}

