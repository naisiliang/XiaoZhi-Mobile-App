package com.lchuang.xiaozhimobile

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.view.KeyEvent
import java.util.Locale

class PhoneController(private val context: Context) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val knownPackages = linkedMapOf(
        "微信" to "com.tencent.mm",
        "qq" to "com.tencent.mobileqq",
        "qq音乐" to "com.tencent.qqmusic",
        "网易云音乐" to "com.netease.cloudmusic",
        "网易云" to "com.netease.cloudmusic",
        "酷狗音乐" to "com.kugou.android",
        "酷狗" to "com.kugou.android",
        "抖音" to "com.ss.android.ugc.aweme",
        "支付宝" to "com.eg.android.AlipayGphone",
        "淘宝" to "com.taobao.taobao",
        "高德地图" to "com.autonavi.minimap",
        "高德" to "com.autonavi.minimap",
        "百度地图" to "com.baidu.BaiduMap",
        "chrome" to "com.android.chrome",
        "谷歌浏览器" to "com.android.chrome",
        "spotify" to "com.spotify.music"
    )

    fun mediaPlay() = dispatchMedia(KeyEvent.KEYCODE_MEDIA_PLAY)
    fun mediaPause() = dispatchMedia(KeyEvent.KEYCODE_MEDIA_PAUSE)
    fun mediaNext() = dispatchMedia(KeyEvent.KEYCODE_MEDIA_NEXT)
    fun mediaPrevious() = dispatchMedia(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
    fun mediaPlayPause() = dispatchMedia(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)

    private fun dispatchMedia(code: Int) {
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    fun volumeUp() {
        audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, 0)
    }

    fun volumeDown() {
        audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, 0)
    }

    fun setMediaVolume(percent: Int) {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val value = ((percent.coerceIn(0, 100) / 100.0) * max).toInt()
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, value, 0)
    }

    fun openApp(appName: String): Boolean {
        val normalized = appName.lowercase(Locale.getDefault()).replace(" ", "")
        val pm = context.packageManager

        knownPackages.entries.firstOrNull {
            normalized.contains(it.key.lowercase(Locale.getDefault())) ||
                it.key.lowercase(Locale.getDefault()).contains(normalized)
        }?.let { entry ->
            val launch = pm.getLaunchIntentForPackage(entry.value)
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launch)
                return true
            }
        }

        val launcherIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val matches = pm.queryIntentActivities(launcherIntent, 0)
        val match = matches.firstOrNull { info ->
            val label = info.loadLabel(pm)?.toString()?.lowercase(Locale.getDefault()) ?: ""
            label.contains(normalized) || normalized.contains(label)
        }
        if (match != null) {
            val launch = pm.getLaunchIntentForPackage(match.activityInfo.packageName)
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launch)
                return true
            }
        }
        return false
    }

    fun openBrowser(urlOrQuery: String): Boolean {
        return try {
            val raw = urlOrQuery.trim()
            val url = when {
                raw.startsWith("http://") || raw.startsWith("https://") -> raw
                raw.contains(".") && !raw.contains(" ") -> "https://$raw"
                else -> "https://www.google.com/search?q=" + Uri.encode(raw)
            }
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            true
        } catch (_: Exception) {
            false
        }
    }

    fun navigate(destination: String): Boolean {
        return try {
            val uri = Uri.parse("geo:0,0?q=${Uri.encode(destination)}")
            context.startActivity(Intent(Intent.ACTION_VIEW, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            true
        } catch (_: Exception) {
            false
        }
    }

    fun setFlashlight(enabled: Boolean): Boolean {
        return try {
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = manager.cameraIdList.firstOrNull { id ->
                val c = manager.getCameraCharacteristics(id)
                c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                    c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: manager.cameraIdList.firstOrNull { id ->
                manager.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return false
            manager.setTorchMode(cameraId, enabled)
            true
        } catch (_: Exception) {
            false
        }
    }
}
