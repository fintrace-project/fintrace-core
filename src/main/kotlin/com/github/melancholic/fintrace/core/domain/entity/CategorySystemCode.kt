package com.github.melancholic.fintrace.core.domain.entity

enum class CategorySystemCode {
    INCOME_ROOT, INCOME_OTHERS, EXPENSE_ROOT, EXPENSE_OTHERS;

    fun isRootCategory(): Boolean = this in ROOTS
    fun isOthersCategory(): Boolean = this in OTHERS

    companion object {
        val OTHERS = setOf(INCOME_OTHERS, EXPENSE_OTHERS)
        val ROOTS = setOf(INCOME_ROOT, EXPENSE_ROOT)
    }
}