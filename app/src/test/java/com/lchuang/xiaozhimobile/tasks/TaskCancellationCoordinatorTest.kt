package com.lchuang.xiaozhimobile.tasks

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskCancellationCoordinatorTest {
    @Test
    fun `cancellation stops current work cooperatively and cleans current plus queued temporary files`() {
        val currentTemp = Files.createTempFile("task-current-", ".part").toFile()
        val queuedTemp = Files.createTempFile("task-queued-", ".part").toFile()
        val executed = mutableListOf<String>()
        lateinit var coordinator: TaskCancellationCoordinator
        var cancellation: TaskCancellationResult? = null
        coordinator = TaskCancellationCoordinator()
        coordinator.transitionTo(TaskProgressState.RUNNING)
        coordinator.enqueue(
            TaskToolWork(
                id = "current-tool",
                execute = { signal ->
                    executed += "current-tool"
                    cancellation = coordinator.cancel()
                    signal.throwIfCancelled()
                },
                cleanup = { check(currentTemp.delete()) },
            ),
        )
        coordinator.enqueue(
            TaskToolWork(
                id = "queued-tool",
                execute = { executed += "queued-tool" },
                cleanup = { check(queuedTemp.delete()) },
            ),
        )

        val run = coordinator.runNext()

        assertEquals(TaskRunCode.CANCELLED, run.code)
        assertEquals(TaskProgressState.CANCELLED, run.snapshot.state)
        assertEquals(listOf("current-tool"), executed)
        assertFalse(currentTemp.exists())
        assertFalse(queuedTemp.exists())
        assertEquals(TaskCancellationCode.CANCELLED, cancellation?.code)
        assertTrue(cancellation?.cleanupFailures.orEmpty().isEmpty())
    }

    @Test
    fun `cancellation before execution prevents every queued tool and is one shot`() {
        val firstTemp = Files.createTempFile("task-first-", ".part").toFile()
        val secondTemp = Files.createTempFile("task-second-", ".part").toFile()
        val executed = mutableListOf<String>()
        val coordinator = TaskCancellationCoordinator()
        coordinator.enqueue(
            TaskToolWork(
                id = "first-tool",
                execute = { executed += "first-tool" },
                cleanup = { check(firstTemp.delete()) },
            ),
        )
        coordinator.enqueue(
            TaskToolWork(
                id = "second-tool",
                execute = { executed += "second-tool" },
                cleanup = { check(secondTemp.delete()) },
            ),
        )

        val cancelled = coordinator.cancel()
        val run = coordinator.runNext()
        val repeated = coordinator.cancel()

        assertEquals(TaskCancellationCode.CANCELLED, cancelled.code)
        assertEquals(TaskRunCode.CANCELLED, run.code)
        assertEquals(TaskCancellationCode.ALREADY_TERMINAL, repeated.code)
        assertTrue(executed.isEmpty())
        assertFalse(firstTemp.exists())
        assertFalse(secondTemp.exists())
    }

    @Test
    fun `tool failure cleans all remaining temporary work and marks task failed`() {
        val currentTemp = Files.createTempFile("task-failed-current-", ".part").toFile()
        val queuedTemp = Files.createTempFile("task-failed-queued-", ".part").toFile()
        val coordinator = TaskCancellationCoordinator()
        coordinator.transitionTo(TaskProgressState.RUNNING)
        coordinator.enqueue(
            TaskToolWork(
                id = "failed-tool",
                execute = { error("tool failed") },
                cleanup = { check(currentTemp.delete()) },
            ),
        )
        coordinator.enqueue(
            TaskToolWork(
                id = "remaining-tool",
                execute = {},
                cleanup = { check(queuedTemp.delete()) },
            ),
        )

        val result = coordinator.runNext()

        assertEquals(TaskRunCode.FAILED, result.code)
        assertEquals(TaskProgressState.FAILED, result.snapshot.state)
        assertTrue(result.cleanupFailures.isEmpty())
        assertFalse(currentTemp.exists())
        assertFalse(queuedTemp.exists())
        assertEquals(TaskRunCode.NOT_RUNNABLE, coordinator.runNext().code)
    }
}
