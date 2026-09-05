package com.lchuang.xiaozhimobile.safety

fun interface PermissionBroker {
    fun check(invocation: ToolInvocation): Boolean
}
