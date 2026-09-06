package com.lchuang.xiaozhimobile.runtime

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import com.lchuang.xiaozhimobile.WakeService

object WakeServiceController {
    const val SETTINGS_FILE = "xiaozhi_settings"
    const val BACKGROUND_WAKE_ENABLED_KEY = "backgroundWakeEnabled"

    fun start(context: Context) {
        dispatchStart(context, Intent(context, WakeService::class.java))
    }

    fun stop(context: Context) {
        val appContext = context.applicationContext
        appContext.stopService(
            Intent(context, WakeService::class.java).setAction(WakeService.ACTION_STOP),
        )
        WakeRuntimeStatusStoreProvider.instance().publish(
            WakeRuntimeStatus.STOPPED,
            "stop requested",
        )
    }

    fun applyWakeSettings(context: Context) {
        dispatchCommand(
            context,
            Intent(context, WakeService::class.java).setAction(WakeService.ACTION_APPLY_WAKE_SETTINGS),
        )
    }

    fun submitText(context: Context, text: String) {
        if (text.trim().isBlank()) return
        val intent = Intent(context, WakeService::class.java)
            .apply {
                action = WakeService.ACTION_SUBMIT_TEXT
                putExtra(WakeService.EXTRA_TEXT, text.trim())
            }
        val appContext = context.applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(appContext, intent)
        } else {
            appContext.startService(intent)
        }
    }

    fun isRunning(context: Context): Boolean {
        @Suppress("UNUSED_PARAMETER")
        val ignoredContext = context
        return when (WakeRuntimeStatusStoreProvider.instance().current) {
            WakeRuntimeStatus.STARTING,
            WakeRuntimeStatus.KWS_LISTENING,
            WakeRuntimeStatus.SESSION_ACTIVE,
            -> true
            WakeRuntimeStatus.STOPPED,
            WakeRuntimeStatus.ERROR,
            -> false
        }
    }

    fun isBackgroundWakeEnabled(context: Context): Boolean = context.applicationContext
        .getSharedPreferences(SETTINGS_FILE, Context.MODE_PRIVATE)
        .getBoolean(BACKGROUND_WAKE_ENABLED_KEY, true)

    fun setBackgroundWakeEnabled(context: Context, enabled: Boolean) {
        context.applicationContext
            .getSharedPreferences(SETTINGS_FILE, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(BACKGROUND_WAKE_ENABLED_KEY, enabled)
            .apply()
    }

    private fun dispatchStart(context: Context, intent: Intent) {
        dispatchCommand(context, intent)
    }

    private fun dispatchCommand(context: Context, intent: Intent) {
        val appContext = context.applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(appContext, intent)
        } else {
            appContext.startService(intent)
        }
    }
}
