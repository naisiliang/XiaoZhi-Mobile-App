package com.lchuang.xiaozhimobile.conversation

data class ConversationHistoryPage(
    val sessions: List<ConversationSession>,
    val offset: Int,
    val limit: Int,
    val hasMore: Boolean,
)

/** Validates and shapes bounded history pages independently of the database implementation. */
object ConversationHistoryPagination {
    const val DEFAULT_PAGE_SIZE = 50
    const val MAX_PAGE_SIZE = 100

    fun page(
        rows: List<ConversationSession>,
        offset: Int,
        limit: Int,
    ): ConversationHistoryPage {
        validate(offset, limit)
        return ConversationHistoryPage(
            sessions = rows.take(limit),
            offset = offset,
            limit = limit,
            hasMore = rows.size > limit,
        )
    }

    fun validate(offset: Int, limit: Int) {
        require(offset >= 0) { "history offset must not be negative" }
        require(limit in 1..MAX_PAGE_SIZE) { "history limit must be in 1..$MAX_PAGE_SIZE" }
    }
}
