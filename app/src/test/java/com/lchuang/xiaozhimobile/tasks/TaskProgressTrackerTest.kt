package com.lchuang.xiaozhimobile.tasks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TaskProgressTrackerTest {
    @Test
    fun `supports exactly the specified task states`() {
        assertEquals(
            setOf(
                TaskProgressState.QUEUED,
                TaskProgressState.RUNNING,
                TaskProgressState.WAITING_USER,
                TaskProgressState.WAITING_PERMISSION,
                TaskProgressState.WAITING_CONFIRMATION,
                TaskProgressState.COMPLETED,
                TaskProgressState.FAILED,
                TaskProgressState.CANCELLED,
                TaskProgressState.BLOCKED,
                TaskProgressState.INTERRUPTED,
            ),
            TaskProgressState.entries.toSet(),
        )
    }

    @Test
    fun `snapshot reports actual phase counts and derived percentage`() {
        val tracker = TaskProgressTracker()

        tracker.transitionTo(TaskProgressState.RUNNING)
        val snapshot = tracker.update("indexing", completedItemCount = 2, totalItemCount = 5)

        assertEquals(TaskProgressState.RUNNING, snapshot.state)
        assertEquals("indexing", snapshot.phase)
        assertEquals(2, snapshot.completedItemCount)
        assertEquals(5, snapshot.totalItemCount)
        assertEquals(40, snapshot.progressPercent)
    }

    @Test
    fun `zero total does not invent a percentage`() {
        val snapshot = TaskProgressTracker().snapshot()

        assertEquals(0, snapshot.completedItemCount)
        assertEquals(0, snapshot.totalItemCount)
        assertNull(snapshot.progressPercent)
    }

    @Test
    fun `terminal state rejects later transitions`() {
        val tracker = TaskProgressTracker()
        tracker.transitionTo(TaskProgressState.COMPLETED)

        try {
            tracker.transitionTo(TaskProgressState.RUNNING)
        } catch (_: IllegalStateException) {
            return
        }
        throw AssertionError("terminal tracker accepted a later transition")
    }

    @Test
    fun `every terminal state rejects later updates and transitions`() {
        val terminalStates = listOf(
            TaskProgressState.COMPLETED,
            TaskProgressState.FAILED,
            TaskProgressState.CANCELLED,
            TaskProgressState.BLOCKED,
            TaskProgressState.INTERRUPTED,
        )

        terminalStates.forEach { terminalState ->
            val tracker = TaskProgressTracker()
            tracker.transitionTo(terminalState)

            assertThrows("update after $terminalState") {
                tracker.update("late phase", completedItemCount = 1, totalItemCount = 1)
            }
            assertThrows("transition after $terminalState") {
                tracker.transitionTo(TaskProgressState.RUNNING)
            }
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects negative completed count`() {
        TaskProgressTracker().update("work", completedItemCount = -1, totalItemCount = 2)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects completed count greater than total`() {
        TaskProgressTracker().update("work", completedItemCount = 3, totalItemCount = 2)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects negative total count`() {
        TaskProgressTracker().update("work", completedItemCount = 0, totalItemCount = -1)
    }

    private fun assertThrows(message: String, action: () -> Unit) {
        try {
            action()
        } catch (_: IllegalStateException) {
            return
        }
        throw AssertionError(message)
    }
}
