package com.lchuang.xiaozhimobile.tasks

import java.util.concurrent.atomic.AtomicBoolean

enum class TaskCancellationCode {
    CANCELLED,
    ALREADY_TERMINAL,
}

enum class TaskRunCode {
    EXECUTED,
    CANCELLED,
    NO_WORK,
    NOT_RUNNABLE,
    ALREADY_RUNNING,
    FAILED,
}

/** A cooperative cancellation signal passed to work that has already started. */
class TaskCancellationSignal internal constructor(
    private val cancelled: AtomicBoolean,
) {
    fun isCancellationRequested(): Boolean = cancelled.get()

    fun throwIfCancelled() {
        if (isCancellationRequested()) throw CancellationRequestedException()
    }

    internal fun cancel() {
        cancelled.set(true)
    }

    internal class CancellationRequestedException : RuntimeException(
        "task cancellation requested",
        null,
        false,
        false,
    )
}

/** A queued Tool plus the cleanup that owns only its temporary/intermediate resources. */
data class TaskToolWork(
    val id: String,
    val execute: (TaskCancellationSignal) -> Unit,
    val cleanup: () -> Unit = {},
) {
    init {
        require(id.isNotBlank()) { "task tool id must not be blank" }
    }
}

data class TaskCancellationResult(
    val code: TaskCancellationCode,
    val snapshot: TaskProgressSnapshot,
    val cleanedToolIds: List<String> = emptyList(),
    val cleanupFailures: List<String> = emptyList(),
)

data class TaskRunResult(
    val code: TaskRunCode,
    val toolId: String? = null,
    val snapshot: TaskProgressSnapshot,
    val cleanupFailures: List<String> = emptyList(),
)

/**
 * Owns one long-running task's Tool queue and cancellation boundary. Work is
 * started one item at a time, and queued work is never invoked after cancel.
 * A running Tool must check its signal at safe points; cancellation also runs
 * each registered temporary-resource cleanup at most once.
 */
class TaskCancellationCoordinator(
    private val tracker: TaskProgressTracker = TaskProgressTracker(),
) {
    private sealed interface NextWork {
        data object Cancelled : NextWork
        data object AlreadyRunning : NextWork
        data object NotRunnable : NextWork
        data object NoWork : NextWork
        data class Ready(val active: ActiveTool) : NextWork
    }

    private data class ActiveTool(
        val work: TaskToolWork,
        val signal: TaskCancellationSignal,
        var cleanupClaimed: Boolean = false,
    )

    private data class CleanupPlan(
        val tools: List<TaskToolWork>,
    )

    private val lock = Any()
    private val queuedTools = ArrayDeque<TaskToolWork>()
    private var activeTool: ActiveTool? = null
    private var cancellationRequested = false

    fun snapshot(): TaskProgressSnapshot = synchronized(lock) { tracker.snapshot() }

    fun transitionTo(state: TaskProgressState): TaskProgressSnapshot = synchronized(lock) {
        tracker.transitionTo(state)
    }

    fun update(
        phase: String,
        completedItemCount: Int,
        totalItemCount: Int,
    ): TaskProgressSnapshot = synchronized(lock) {
        tracker.update(phase, completedItemCount, totalItemCount)
    }

    /** Add work that has not started yet; duplicate Tool ids are rejected. */
    fun enqueue(work: TaskToolWork): Boolean = synchronized(lock) {
        if (cancellationRequested || tracker.snapshot().state.isTerminal()) return false
        require(activeTool?.work?.id != work.id && queuedTools.none { it.id == work.id }) {
            "task tool id must be unique: ${work.id}"
        }
        queuedTools.addLast(work)
        true
    }

    /** Execute exactly one queued Tool, or report why no Tool was started. */
    fun runNext(): TaskRunResult {
        val next: NextWork = synchronized(lock) {
            if (cancellationRequested || tracker.snapshot().state == TaskProgressState.CANCELLED) {
                return@synchronized NextWork.Cancelled
            }
            if (activeTool != null) return@synchronized NextWork.AlreadyRunning
            val state = tracker.snapshot().state
            if (state == TaskProgressState.QUEUED) tracker.transitionTo(TaskProgressState.RUNNING)
            if (state !in RUNNABLE_STATES) return@synchronized NextWork.NotRunnable
            val work = queuedTools.removeFirstOrNull() ?: return@synchronized NextWork.NoWork
            NextWork.Ready(ActiveTool(
                work = work,
                signal = TaskCancellationSignal(AtomicBoolean(false)),
            ).also { activeTool = it })
        }

        when (next) {
            NextWork.Cancelled -> return TaskRunResult(TaskRunCode.CANCELLED, snapshot = snapshot())
            NextWork.AlreadyRunning -> return TaskRunResult(TaskRunCode.ALREADY_RUNNING, snapshot = snapshot())
            NextWork.NotRunnable -> return TaskRunResult(TaskRunCode.NOT_RUNNABLE, snapshot = snapshot())
            NextWork.NoWork -> return TaskRunResult(TaskRunCode.NO_WORK, snapshot = snapshot())
            is NextWork.Ready -> Unit
        }

        val running = (next as NextWork.Ready).active
        return try {
            running.signal.throwIfCancelled()
            running.work.execute(running.signal)
            val cancelled = synchronized(lock) {
                clearActive(running)
                cancellationRequested || running.signal.isCancellationRequested()
            }
            TaskRunResult(
                code = if (cancelled) TaskRunCode.CANCELLED else TaskRunCode.EXECUTED,
                toolId = running.work.id,
                snapshot = snapshot(),
            )
        } catch (_: TaskCancellationSignal.CancellationRequestedException) {
            clearActive(running)
            TaskRunResult(
                code = TaskRunCode.CANCELLED,
                toolId = running.work.id,
                snapshot = snapshot(),
            )
        } catch (_: Exception) {
            val cleanup = synchronized(lock) {
                val plan = takeCleanupLocked(running, includeQueued = true)
                if (!cancellationRequested) tracker.transitionTo(TaskProgressState.FAILED)
                plan
            }
            val cleanupResult = performCleanup(cleanup)
            TaskRunResult(
                code = if (cancellationRequested) TaskRunCode.CANCELLED else TaskRunCode.FAILED,
                toolId = running.work.id,
                snapshot = snapshot(),
                cleanupFailures = cleanupResult.cleanupFailures,
            )
        }
    }

    /**
     * Cancel the task and clean current/queued temporary resources. Cleanup
     * failures are surfaced to the caller; the task remains visibly cancelled.
     */
    fun cancel(): TaskCancellationResult {
        val plan = synchronized(lock) {
            if (tracker.snapshot().state.isTerminal()) {
                return@synchronized null
            }
            cancellationRequested = true
            activeTool?.signal?.cancel()
            val cleanup = takeCleanupLocked(activeTool, includeQueued = true)
            val snapshot = tracker.transitionTo(TaskProgressState.CANCELLED)
            CancellationPlanWithSnapshot(cleanup, snapshot)
        }
        if (plan == null) {
            return TaskCancellationResult(
                code = TaskCancellationCode.ALREADY_TERMINAL,
                snapshot = snapshot(),
            )
        }
        val cleanupResult = performCleanup(plan.plan)
        return TaskCancellationResult(
            code = TaskCancellationCode.CANCELLED,
            snapshot = plan.snapshot,
            cleanedToolIds = cleanupResult.cleanedToolIds,
            cleanupFailures = cleanupResult.cleanupFailures,
        )
    }

    private data class CancellationPlanWithSnapshot(
        val plan: CleanupPlan,
        val snapshot: TaskProgressSnapshot,
    )

    private data class CleanupResult(
        val cleanedToolIds: List<String>,
        val cleanupFailures: List<String>,
    )

    private fun takeCleanupLocked(
        active: ActiveTool?,
        includeQueued: Boolean,
    ): CleanupPlan {
        val cleanup = mutableListOf<TaskToolWork>()
        if (includeQueued) {
            cleanup += queuedTools
            queuedTools.clear()
        }
        if (active != null && activeTool === active && !active.cleanupClaimed) {
            active.cleanupClaimed = true
            cleanup += active.work
        }
        if (active != null && activeTool === active) activeTool = null
        return CleanupPlan(cleanup)
    }

    private fun clearActive(active: ActiveTool) {
        synchronized(lock) {
            if (activeTool === active) activeTool = null
        }
    }

    private fun performCleanup(plan: CleanupPlan): CleanupResult {
        val cleaned = mutableListOf<String>()
        val failures = mutableListOf<String>()
        plan.tools.forEach { work ->
            try {
                work.cleanup()
                cleaned += work.id
            } catch (_: Exception) {
                failures += work.id
            }
        }
        return CleanupResult(cleaned, failures)
    }

    private fun TaskProgressState.isTerminal(): Boolean = this in TERMINAL_STATES

    private companion object {
        val RUNNABLE_STATES = setOf(TaskProgressState.QUEUED, TaskProgressState.RUNNING)
        val TERMINAL_STATES = setOf(
            TaskProgressState.COMPLETED,
            TaskProgressState.FAILED,
            TaskProgressState.CANCELLED,
            TaskProgressState.BLOCKED,
            TaskProgressState.INTERRUPTED,
        )
    }
}
