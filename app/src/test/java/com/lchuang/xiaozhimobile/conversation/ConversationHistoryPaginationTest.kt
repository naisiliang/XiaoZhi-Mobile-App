package com.lchuang.xiaozhimobile.conversation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationHistoryPaginationTest {
    @Test
    fun page_window_returns_only_the_requested_slice_and_exposes_more_rows() {
        val sessions = (0 until 3).map { index ->
            ConversationSession(
                id = "session-$index",
                startedAtMs = index.toLong(),
            )
        }

        val page = ConversationHistoryPagination.page(
            rows = sessions,
            offset = 0,
            limit = 2,
        )

        assertEquals(listOf("session-0", "session-1"), page.sessions.map { it.id })
        assertEquals(0, page.offset)
        assertEquals(2, page.limit)
        assertTrue(page.hasMore)
    }

    @Test
    fun final_page_has_no_more_rows_and_nonzero_offset_is_preserved() {
        val sessions = (0 until 3).map { index ->
            ConversationSession(
                id = "session-$index",
                startedAtMs = index.toLong(),
            )
        }

        val page = ConversationHistoryPagination.page(
            rows = sessions,
            offset = 2,
            limit = 2,
        )

        assertEquals(listOf("session-2"), page.sessions.map { it.id })
        assertEquals(2, page.offset)
        assertEquals(2, page.limit)
        assertFalse(page.hasMore)
    }

    @Test
    fun query_window_shaping_does_not_apply_the_database_offset_twice() {
        val sessions = (0 until 3).map { index ->
            ConversationSession(
                id = "session-$index",
                startedAtMs = index.toLong(),
            )
        }

        val page = ConversationHistoryPagination.fromQueryRows(
            rows = sessions.drop(2),
            offset = 2,
            limit = 2,
        )

        assertEquals(listOf("session-2"), page.sessions.map { it.id })
        assertFalse(page.hasMore)
    }

    @Test
    fun invalid_page_window_is_rejected_before_database_query() {
        listOf(
            { ConversationHistoryPagination.page(emptyList(), offset = -1, limit = 20) },
            { ConversationHistoryPagination.page(emptyList(), offset = 0, limit = 0) },
            { ConversationHistoryPagination.page(emptyList(), offset = 0, limit = ConversationHistoryPagination.MAX_PAGE_SIZE + 1) },
        ).forEach { invalid ->
            assertTrue(runCatching { invalid() }.exceptionOrNull() is IllegalArgumentException)
        }
    }

    @Test
    fun repository_exposes_the_bounded_history_page_entry_point() {
        val method = ConversationRepository::class.java.methods.single {
            it.name == "loadHistoryPage" && it.parameterTypes.contentEquals(
                arrayOf(Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!),
            )
        }

        assertEquals(ConversationHistoryPage::class.java, method.returnType)

        val sqliteMethod = SqliteConversationRepository::class.java.methods.single {
            it.name == "loadHistoryPage" && it.parameterTypes.contentEquals(
                arrayOf(Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!),
            )
        }
        assertEquals(ConversationHistoryPage::class.java, sqliteMethod.returnType)
    }

    @Test
    fun schema_has_a_non_destructive_index_migration_for_history_queries() {
        assertTrue(ConversationDatabase.VERSION >= 2)
        assertTrue(
            ConversationDatabase.UPGRADE_TO_VERSION_2_STATEMENTS.all {
                it.trimStart().startsWith("CREATE INDEX IF NOT EXISTS", ignoreCase = true)
            },
        )
        assertTrue(
            ConversationDatabase.UPGRADE_TO_VERSION_2_STATEMENTS.any {
                it.contains("conversation_sessions", ignoreCase = true)
            },
        )
    }
}
