package com.lchuang.xiaozhimobile

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationProviderLifecycleHardeningTest {
    @Test
    fun location_request_cancels_all_platform_request_handles_when_it_finishes() {
        val source = listOf(
            File("app/src/main/java/com/lchuang/xiaozhimobile/LocationProvider.kt"),
            File("src/main/java/com/lchuang/xiaozhimobile/LocationProvider.kt"),
        ).firstOrNull(File::isFile)?.readText().orEmpty()

        assertTrue(source.contains("cancellationSignal?.cancel()"))
        assertTrue(source.contains("manager.removeUpdates(listener)"))
        assertTrue(source.contains("removeCallbacks"))
        assertTrue(source.contains("timeoutRunnable"))
    }
}
