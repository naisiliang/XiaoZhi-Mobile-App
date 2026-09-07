package com.lchuang.xiaozhimobile.screen

class ScreenContextStore(
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val clockMs: () -> Long = { System.nanoTime() / NANOS_PER_MILLISECOND },
) {
    private var nextGeneration = 0L
    private var current: ScreenContext? = null

    init {
        require(ttlMs > 0L) { "Screen context TTL must be positive" }
    }

    @Synchronized
    fun publish(packageName: String, windowFingerprint: String, root: ScreenNode): ScreenContext {
        val previous = current
        if (previous != null &&
            (previous.packageName != packageName || previous.windowFingerprint != windowFingerprint)
        ) {
            current = null
        }
        val context = ScreenContext(
            generationId = GenerationId(++nextGeneration),
            packageName = packageName,
            windowFingerprint = windowFingerprint,
            root = root,
            capturedAtMs = clockMs(),
        )
        current = context
        return context
    }

    @Synchronized
    fun get(packageName: String, windowFingerprint: String): ScreenContext? {
        val context = current ?: return null
        if (context.packageName != packageName || context.windowFingerprint != windowFingerprint) {
            return null
        }
        if (clockMs() - context.capturedAtMs >= ttlMs) {
            current = null
            return null
        }
        return context
    }

    /** Return true only when this exact generation is still the live, unexpired context. */
    @Synchronized
    fun isCurrent(context: ScreenContext): Boolean =
        get(context.packageName, context.windowFingerprint)?.generationId == context.generationId

    @Synchronized
    fun invalidate() {
        current = null
    }

    private companion object {
        const val DEFAULT_TTL_MS = 5_000L
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}
