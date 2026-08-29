package com.lchuang.xiaozhimobile

import android.content.Context
import android.content.Intent

class AppExitController(private val context: Context) {
    data class HomeResult(val success: Boolean, val code: String)

    fun goHome(): HomeResult = try {
        context.startActivity(Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
        HomeResult(true, "GO_HOME_OK")
    } catch (_: Throwable) {
        HomeResult(false, "GO_HOME_FAILED")
    }
}
