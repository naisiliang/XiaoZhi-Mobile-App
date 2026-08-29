package com.lchuang.xiaozhimobile

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent

class AppLauncher(private val context: Context) {
    enum class AppLaunchError { PACKAGE_NOT_VISIBLE, PACKAGE_NOT_INSTALLED, NO_LAUNCH_ACTIVITY, START_ACTIVITY_FAILED }

    sealed class AppLaunchResult {
        data class Success(val packageName: String, val label: String) : AppLaunchResult()
        data class Failure(val error: AppLaunchError, val detail: String) : AppLaunchResult()
    }

    fun launch(entry: InstalledAppRegistry.AppEntry): AppLaunchResult {
        val pm = context.packageManager
        try {
            val launch = pm.getLaunchIntentForPackage(entry.packageName)
            if (launch != null && tryStart(launch)) return AppLaunchResult.Success(entry.packageName, entry.label)
        } catch (_: SecurityException) {
            return AppLaunchResult.Failure(AppLaunchError.PACKAGE_NOT_VISIBLE, "系统限制了应用可见性")
        } catch (_: RuntimeException) { }

        val scoped = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            setPackage(entry.packageName)
        }
        if (tryStart(scoped)) return AppLaunchResult.Success(entry.packageName, entry.label)

        entry.launchActivities.forEach { activity ->
            val explicit = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                component = ComponentName(entry.packageName, activity)
            }
            if (tryStart(explicit)) return AppLaunchResult.Success(entry.packageName, entry.label)
        }
        return if (entry.launchActivities.isEmpty()) {
            AppLaunchResult.Failure(AppLaunchError.NO_LAUNCH_ACTIVITY, "没有找到 Launcher Activity")
        } else {
            AppLaunchResult.Failure(AppLaunchError.START_ACTIVITY_FAILED, "系统未能启动该应用")
        }
    }

    private fun tryStart(intent: Intent): Boolean {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        } catch (_: RuntimeException) {
            false
        }
    }
}
