package com.lchuang.xiaozhimobile

data class CommandTransaction(
    val rawText: String,
    val normalizedText: String,
    val action: DeviceAction,
    val announcement: String,
    val startedAtMs: Long? = null,
    val result: DeviceExecutionResult? = null
)
