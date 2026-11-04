package com.github.melancholic.fintrace.core.api.v1.dto

data class ListResponse<T>(
    val items: List<T>
)

data class PagedListResponse<T>(
    val items: List<T>,
    val page: PageCursors
)

data class PageCursors(
    val older: String?,
    val newer: String?
)
