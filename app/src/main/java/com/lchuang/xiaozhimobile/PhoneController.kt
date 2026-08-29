package com.lchuang.xiaozhimobile

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.view.KeyEvent

class PhoneController(
    private val context: Context,
    val appRegistry: InstalledAppRegistry = InstalledAppRegistry(context),
    private val appLauncher: AppLauncher = AppLauncher(context),
    val mapController: MapController = MapController(context)
) {
    private val settings = SettingsStore(context)
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
    fun mediaStop() {
        dispatchMedia(KeyEvent.KEYCODE_MEDIA_STOP)
        // Many Android music apps ignore STOP but honor PAUSE. PAUSE is idempotent.
        dispatchMedia(KeyEvent.KEYCODE_MEDIA_PAUSE)
    }

    private fun dispatchMedia(code: Int) {
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    data class MediaVolumeResult(
        val requestedPercent: Int?,
        val actualPercent: Int,
        val success: Boolean
    )

    fun currentMediaVolumePercent(): Int {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        return kotlin.math.round(current * 100.0 / max).toInt().coerceIn(0, 100)
    }

    fun setMediaVolumePercent(percent: Int): MediaVolumeResult {
        val requested = percent.coerceIn(0, 100)
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val target = kotlin.math.round(requested * max / 100.0).toInt().coerceIn(0, max)
        return try {
            audioManager.setStreamVolume(
                AudioManager.STREAM_MUSIC,
                target,
                AudioManager.FLAG_SHOW_UI
            )
            val actualStep = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            val actual = kotlin.math.round(actualStep * 100.0 / max).toInt().coerceIn(0, 100)
            val tolerance = kotlin.math.ceil(100.0 / max).toInt().coerceAtLeast(1)
            MediaVolumeResult(requested, actual, kotlin.math.abs(actual - requested) <= tolerance)
        } catch (_: Throwable) {
            MediaVolumeResult(requested, currentMediaVolumePercent(), false)
        }
    }

    fun volumeUpVerified(): MediaVolumeResult {
        return try {
            audioManager.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                AudioManager.ADJUST_RAISE,
                AudioManager.FLAG_SHOW_UI
            )
            MediaVolumeResult(null, currentMediaVolumePercent(), true)
        } catch (_: Throwable) {
            MediaVolumeResult(null, currentMediaVolumePercent(), false)
        }
    }

    fun volumeDownVerified(): MediaVolumeResult {
        return try {
            audioManager.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                AudioManager.ADJUST_LOWER,
                AudioManager.FLAG_SHOW_UI
            )
            MediaVolumeResult(null, currentMediaVolumePercent(), true)
        } catch (_: Throwable) {
            MediaVolumeResult(null, currentMediaVolumePercent(), false)
        }
    }

    fun volumeUp() { volumeUpVerified() }
    fun volumeDown() { volumeDownVerified() }
    fun setMediaVolume(percent: Int) { setMediaVolumePercent(percent) }

    fun openApp(appName: String): AppLauncher.AppLaunchResult {
        val resolution = appRegistry.resolveDetailed(appName, settings.appAliases)
        val entry = resolution.entry
        if (entry != null) return appLauncher.launch(entry)

        val normalized = AppNameMatcher.extractRequestedAppName(appName)
        val known = knownPackages.entries.firstOrNull {
            normalized.contains(AppNameMatcher.normalize(it.key)) ||
                AppNameMatcher.normalize(it.key).contains(normalized)
        }
        if (known != null) {
            val fallback = InstalledAppRegistry.AppEntry(
                label = known.key,
                packageName = known.value,
                normalizedLabel = AppNameMatcher.normalize(known.key),
                launchActivities = emptyList(),
                source = InstalledAppRegistry.AppDiscoverySource.KNOWN_FALLBACK
            )
            return appLauncher.launch(fallback)
        }
        return AppLauncher.AppLaunchResult.Failure(
            AppLauncher.AppLaunchError.PACKAGE_NOT_INSTALLED,
            resolution.explanation
        )
    }

    fun installedAppCount(): Int = appRegistry.count()

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

    fun openMap(preference: MapAppPreference = settings.defaultMapApp): MapController.MapActionResult =
        mapController.openMap(preference)

    fun navigate(destination: String, preference: MapAppPreference = settings.defaultMapApp): MapController.MapActionResult =
        mapController.navigate(destination, preference)

    fun searchNearby(keyword: String, preference: MapAppPreference = settings.defaultMapApp, callback: (MapController.MapActionResult) -> Unit) =
        mapController.searchNearby(keyword, preference, callback)

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
