package com.lchuang.xiaozhimobile

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class AiClientLifecycleHardeningTest {
    @Test
    fun settings_activity_closes_the_ai_client_executor() {
        val aiClientSource = source("AiClient.kt")
        val settingsSource = source("SettingsActivity.kt")

        assertTrue(aiClientSource.contains("executor.shutdownNow()"))
        assertTrue(settingsSource.contains("aiClient.close()"))
    }

    private fun source(name: String): String = listOf(
        File("app/src/main/java/com/lchuang/xiaozhimobile/$name"),
        File("src/main/java/com/lchuang/xiaozhimobile/$name"),
    ).firstOrNull(File::isFile)?.readText().orEmpty()
}
