package com.lchuang.xiaozhimobile.conversation

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationHistoryActivityHardeningTest {
    @Test
    fun history_activity_closes_database_repository_on_destroy() {
        val source = listOf(
            File("app/src/main/java/com/lchuang/xiaozhimobile/conversation/ConversationHistoryActivity.kt"),
            File("src/main/java/com/lchuang/xiaozhimobile/conversation/ConversationHistoryActivity.kt"),
        ).firstOrNull(File::isFile)?.readText().orEmpty()

        assertTrue(source.contains("repository.close()"))
    }
}
