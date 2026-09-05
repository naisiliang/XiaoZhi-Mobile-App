package com.lchuang.xiaozhimobile.tasks

enum class TaskProgressState {
    QUEUED,
    RUNNING,
    WAITING_USER,
    WAITING_PERMISSION,
    WAITING_CONFIRMATION,
    COMPLETED,
    FAILED,
    CANCELLED,
    BLOCKED,
    INTERRUPTED,
}

data class TaskProgressSnapshot(
    val state: TaskProgressState,
    val phase: String,
    val completedItemCount: Int,
    val totalItemCount: Int,
) {
    val progressPercent: Int?
        get() = if (totalItemCount == 0) {
            null
        } else {
            ((completedItemCount.toLong() * 100L) / totalItemCount).toInt()
        }
}

class TaskProgressTracker {
    private var currentSnapshot = TaskProgressSnapshot(
        state = TaskProgressState.QUEUED,
        phase = "",
        completedItemCount = 0,
        totalItemCount = 0,
    )

    fun snapshot(): TaskProgressSnapshot = currentSnapshot

    fun transitionTo(state: TaskProgressState): TaskProgressSnapshot {
        check(!currentSnapshot.state.isTerminal()) {
            "Terminal task state cannot transition: ${currentSnapshot.state}"
        }
        currentSnapshot = currentSnapshot.copy(state = state)
        return currentSnapshot
    }

    fun update(
        phase: String,
        completedItemCount: Int,
        totalItemCount: Int,
    ): TaskProgressSnapshot {
        check(!currentSnapshot.state.isTerminal()) {
            "Terminal task state cannot be updated: ${currentSnapshot.state}"
        }
        require(totalItemCount >= 0) { "totalItemCount must be non-negative" }
        require(completedItemCount >= 0) { "completedItemCount must be non-negative" }
        require(completedItemCount <= totalItemCount) {
            "completedItemCount must not exceed totalItemCount"
        }
        currentSnapshot = TaskProgressSnapshot(
            state = currentSnapshot.state,
            phase = phase,
            completedItemCount = completedItemCount,
            totalItemCount = totalItemCount,
        )
        return currentSnapshot
    }

    private fun TaskProgressState.isTerminal(): Boolean = when (this) {
        TaskProgressState.COMPLETED,
        TaskProgressState.FAILED,
        TaskProgressState.CANCELLED,
        TaskProgressState.BLOCKED,
        TaskProgressState.INTERRUPTED,
        -> true
        TaskProgressState.QUEUED,
        TaskProgressState.RUNNING,
        TaskProgressState.WAITING_USER,
        TaskProgressState.WAITING_PERMISSION,
        TaskProgressState.WAITING_CONFIRMATION,
        -> false
    }
}
