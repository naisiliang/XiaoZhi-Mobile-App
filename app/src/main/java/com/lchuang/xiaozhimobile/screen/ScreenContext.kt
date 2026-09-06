package com.lchuang.xiaozhimobile.screen

@JvmInline
value class GenerationId(val value: Long)

data class ScreenContext(
    val generationId: GenerationId,
    val packageName: String,
    val windowFingerprint: String,
    val root: ScreenNode,
    val capturedAtMs: Long,
)
