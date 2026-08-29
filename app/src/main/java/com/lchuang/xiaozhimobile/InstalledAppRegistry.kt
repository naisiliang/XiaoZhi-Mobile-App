package com.lchuang.xiaozhimobile

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.SystemClock

class InstalledAppRegistry(private val context: Context) {
    enum class AppDiscoverySource { LAUNCHER_QUERY, INSTALLED_PACKAGES, KNOWN_FALLBACK }
    enum class AppMatchType { USER_ALIAS, EXACT, KNOWN_ALIAS, CONTAINS, FUZZY, NONE }

    data class AppEntry(
        val label: String,
        val packageName: String,
        val normalizedLabel: String,
        val launchActivities: List<String> = emptyList(),
        val source: AppDiscoverySource = AppDiscoverySource.LAUNCHER_QUERY
    )

    data class AppResolution(
        val requested: String,
        val normalizedQuery: String,
        val entry: AppEntry?,
        val matchType: AppMatchType,
        val score: Double,
        val explanation: String
    )

    @Volatile private var cachedApps: List<AppEntry> = emptyList()
    @Volatile private var cacheAtMs: Long = 0L
    @Volatile private var lastExplanation: String = "尚未执行 App 匹配"

    fun discover(force: Boolean = false): List<AppEntry> {
        val now = SystemClock.elapsedRealtime()
        if (!force && cachedApps.isNotEmpty() && now - cacheAtMs < CACHE_TTL_MS) return cachedApps

        val pm = context.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
        val resolved: List<ResolveInfo> = pm.queryIntentActivities(launcherIntent, 0)
        val launcherByPackage = resolved.groupBy { it.activityInfo?.packageName.orEmpty() }
            .filterKeys { it.isNotBlank() && it != context.packageName }

        val merged = linkedMapOf<String, AppEntry>()
        launcherByPackage.forEach { (packageName, infos) ->
            val first = infos.first()
            val label = first.loadLabel(pm)?.toString()?.trim().orEmpty().ifBlank { packageName }
            val activities = infos.mapNotNull { it.activityInfo?.name }.distinct()
            merged[packageName] = AppEntry(
                label = label,
                packageName = packageName,
                normalizedLabel = AppNameMatcher.normalize(label),
                launchActivities = activities,
                source = AppDiscoverySource.LAUNCHER_QUERY
            )
        }

        @Suppress("DEPRECATION")
        val installed = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        installed.forEach { app ->
            val packageName = app.packageName ?: return@forEach
            if (packageName == context.packageName || merged.containsKey(packageName)) return@forEach
            val launch = pm.getLaunchIntentForPackage(packageName)
            val scoped = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                setPackage(packageName)
            }
            val activities = pm.queryIntentActivities(scoped, 0).mapNotNull { it.activityInfo?.name }.distinct()
            if (launch == null && activities.isEmpty()) return@forEach
            val label = pm.getApplicationLabel(app)?.toString()?.trim().orEmpty().ifBlank { packageName }
            merged[packageName] = AppEntry(
                label = label,
                packageName = packageName,
                normalizedLabel = AppNameMatcher.normalize(label),
                launchActivities = activities,
                source = AppDiscoverySource.INSTALLED_PACKAGES
            )
        }

        cachedApps = merged.values.sortedBy { it.label.lowercase() }
        cacheAtMs = now
        return cachedApps
    }

    fun resolveDetailed(name: String, aliasesRaw: String = ""): AppResolution {
        val requested = name.trim()
        val aliases = AppNameMatcher.parseAliases(aliasesRaw)
        val userTarget = AppNameMatcher.aliasTarget(requested, aliases)
        val knownTarget = if (userTarget == null) AppNameMatcher.knownAliasTarget(requested) else null
        val target = userTarget ?: knownTarget ?: requested
        val query = AppNameMatcher.extractRequestedAppName(target)
        val baseType = when {
            userTarget != null -> AppMatchType.USER_ALIAS
            knownTarget != null -> AppMatchType.KNOWN_ALIAS
            else -> null
        }
        if (query.isBlank()) return remember(AppResolution(requested, query, null, AppMatchType.NONE, 0.0, "请求名称为空"))

        val apps = discover()
        fun packageAliasResolution(value: String): AppEntry? = if (value.contains('.')) {
            apps.firstOrNull { it.packageName.equals(value, ignoreCase = true) }
        } else null
        packageAliasResolution(target)?.let {
            return remember(AppResolution(requested, query, it, baseType ?: AppMatchType.EXACT, 1.0, "按包名别名匹配 ${it.label} (${it.packageName})"))
        }

        apps.firstOrNull { it.normalizedLabel == query }?.let {
            val type = baseType ?: AppMatchType.EXACT
            return remember(AppResolution(requested, query, it, type, 1.0, "${type.name} 匹配 ${it.label} (${it.packageName})"))
        }
        apps.firstOrNull { it.normalizedLabel.contains(query) || query.contains(it.normalizedLabel) }?.let {
            val score = AppNameMatcher.similarity(query, it.normalizedLabel).coerceAtLeast(0.75)
            return remember(AppResolution(requested, query, it, baseType ?: AppMatchType.CONTAINS, score, "包含匹配 ${it.label} (${it.packageName})"))
        }
        val best = apps.map { it to AppNameMatcher.similarity(query, it.normalizedLabel) }.maxByOrNull { it.second }
        if (best != null && best.second >= FUZZY_THRESHOLD) {
            return remember(AppResolution(requested, query, best.first, AppMatchType.FUZZY, best.second, "模糊匹配 ${best.first.label} (${best.first.packageName}) score=${"%.2f".format(best.second)}"))
        }

        if (!cachedApps.isEmpty()) {
            val refreshed = discover(force = true)
            if (refreshed !== apps) {
                refreshed.firstOrNull { it.normalizedLabel == query }?.let {
                    return remember(AppResolution(requested, query, it, baseType ?: AppMatchType.EXACT, 1.0, "刷新后精确匹配 ${it.label} (${it.packageName})"))
                }
            }
        }
        return remember(AppResolution(requested, query, null, AppMatchType.NONE, best?.second ?: 0.0, "未找到可启动应用：$requested；已发现 ${apps.size} 个"))
    }

    fun resolve(name: String, aliasesRaw: String = ""): AppEntry? = resolveDetailed(name, aliasesRaw).entry
    fun count(): Int = discover().size
    fun lastResolutionExplanation(): String = lastExplanation

    private fun remember(result: AppResolution): AppResolution {
        lastExplanation = result.explanation
        return result
    }

    companion object {
        private const val CACHE_TTL_MS = 60_000L
        private const val FUZZY_THRESHOLD = 0.64
    }
}
