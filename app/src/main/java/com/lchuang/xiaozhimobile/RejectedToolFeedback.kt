package com.lchuang.xiaozhimobile

fun reportRejectedToolFeedback(
    _rejected: SafeToolPlan.Rejected,
    notifier: CommandResultNotifier,
    recover: (CommandFailureKind) -> Unit
) {
    val failureKind = CommandFailureKind.SAFETY_REJECTED
    notifier.failure("❌ 执行失败：${failureKind.name}")
    recover(failureKind)
}
