package com.lchuang.xiaozhimobile

import android.content.Context
import android.content.Intent
import android.location.Location
import android.net.Uri

class MapController(
    private val context: Context,
    private val locationProvider: LocationProvider = LocationProvider(context)
) {
    data class MapActionResult(
        val success: Boolean,
        val usedMap: MapAppPreference,
        val message: String,
        val code: String = "OK"
    )

    fun openMap(preference: MapAppPreference): MapActionResult {
        val order = preferenceOrder(preference)
        for (p in order) {
            val intent = when (p) {
                MapAppPreference.AMAP -> Intent(Intent.ACTION_VIEW, Uri.parse("androidamap://poi?sourceApplication=XiaoZhiMobile&keywords=地图&dev=1")).setPackage(AMAP_PACKAGE)
                MapAppPreference.BAIDU -> Intent(Intent.ACTION_VIEW, Uri.parse("baidumap://map/place/search?query=地图&src=andr.lchuang.xiaozhimobile")).setPackage(BAIDU_PACKAGE)
                MapAppPreference.SYSTEM -> Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=地图"))
                MapAppPreference.AUTO -> null
            } ?: continue
            if (start(intent)) return MapActionResult(true, p, "正在打开地图")
        }
        return MapActionResult(false, preference, "没有找到可用地图应用", "NO_MAP_APP")
    }

    fun navigate(destination: String, preference: MapAppPreference): MapActionResult {
        val encoded = Uri.encode(destination.trim())
        if (encoded.isBlank()) return MapActionResult(false, preference, "导航目的地为空", "INVALID_DESTINATION")
        for (p in preferenceOrder(preference)) {
            val intent = when (p) {
                MapAppPreference.AMAP -> Intent(Intent.ACTION_VIEW, Uri.parse("androidamap://keywordNavi?sourceApplication=XiaoZhiMobile&keyword=$encoded&style=2")).setPackage(AMAP_PACKAGE)
                MapAppPreference.BAIDU -> Intent(Intent.ACTION_VIEW, Uri.parse("baidumap://map/navi?query=$encoded&coord_type=wgs84&src=andr.lchuang.xiaozhimobile")).setPackage(BAIDU_PACKAGE)
                MapAppPreference.SYSTEM -> Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$encoded"))
                MapAppPreference.AUTO -> null
            } ?: continue
            if (start(intent)) return MapActionResult(true, p, "正在打开导航")
        }
        return MapActionResult(false, preference, "导航没有成功打开", "NAVIGATION_FAILED")
    }

    fun searchNearby(keyword: String, preference: MapAppPreference, callback: (MapActionResult) -> Unit) {
        val clean = keyword.trim()
        if (clean.isBlank()) {
            callback(MapActionResult(false, preference, "附近搜索关键词为空", "INVALID_KEYWORD"))
            return
        }
        locationProvider.getCurrentLocation { result ->
            val location = result.getOrNull()
            val action = openNearby(clean, preference, location)
            if (action.success) callback(action)
            else callback(action.copy(message = if (location == null) "已尝试打开地图，请在地图中定位后搜索" else action.message))
        }
    }

    private fun openNearby(keyword: String, preference: MapAppPreference, location: Location?): MapActionResult {
        val encoded = Uri.encode(keyword)
        for (p in preferenceOrder(preference)) {
            val intent = when (p) {
                MapAppPreference.AMAP -> {
                    val base = StringBuilder("androidamap://poi?sourceApplication=XiaoZhiMobile&keywords=$encoded&dev=1")
                    if (location != null) {
                        val delta = 0.05
                        base.append("&lat1=${location.latitude - delta}&lon1=${location.longitude - delta}")
                        base.append("&lat2=${location.latitude + delta}&lon2=${location.longitude + delta}")
                    }
                    Intent(Intent.ACTION_VIEW, Uri.parse(base.toString())).setPackage(AMAP_PACKAGE)
                }
                MapAppPreference.BAIDU -> {
                    val center = if (location != null) "&center=${location.latitude},${location.longitude}" else ""
                    Intent(Intent.ACTION_VIEW, Uri.parse("baidumap://map/place/nearby?query=$encoded$center&coord_type=wgs84&radius=5000&src=andr.lchuang.xiaozhimobile")).setPackage(BAIDU_PACKAGE)
                }
                MapAppPreference.SYSTEM -> {
                    val uri = if (location != null) "geo:${location.latitude},${location.longitude}?q=$encoded" else "geo:0,0?q=$encoded"
                    Intent(Intent.ACTION_VIEW, Uri.parse(uri))
                }
                MapAppPreference.AUTO -> null
            } ?: continue
            if (start(intent)) {
                val suffix = if (location == null) "（由地图应用确定当前位置）" else ""
                return MapActionResult(true, p, "正在搜索附近的$keyword$suffix")
            }
        }
        return MapActionResult(false, preference, "没有成功打开附近搜索", "NEARBY_SEARCH_FAILED")
    }

    private fun preferenceOrder(preference: MapAppPreference): List<MapAppPreference> = when (preference) {
        MapAppPreference.AMAP -> listOf(MapAppPreference.AMAP, MapAppPreference.SYSTEM)
        MapAppPreference.BAIDU -> listOf(MapAppPreference.BAIDU, MapAppPreference.SYSTEM)
        MapAppPreference.SYSTEM -> listOf(MapAppPreference.SYSTEM)
        MapAppPreference.AUTO -> listOf(MapAppPreference.AMAP, MapAppPreference.BAIDU, MapAppPreference.SYSTEM)
    }

    private fun start(intent: Intent): Boolean = try {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    } catch (_: Throwable) { false }

    companion object {
        const val AMAP_PACKAGE = "com.autonavi.minimap"
        const val BAIDU_PACKAGE = "com.baidu.BaiduMap"
    }
}
