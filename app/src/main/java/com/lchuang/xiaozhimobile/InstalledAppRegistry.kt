package com.lchuang.xiaozhimobile

import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import android.os.SystemClock

class InstalledAppRegistry(private val context: Context) {
    data class AppEntry(
        val label: String,
        val packageName: String,
        val normalizedLabel: String
    )

    @Volatile private var cachedApps: List<AppEntry> = emptyList()
    @Volatile private var cacheAtMs: Long = 0L

    fun discover(force: Boolean = false): List<AppEntry> {
        val now = SystemClock.elapsedRealtime()
        if (!force && cachedApps.isNotEmpty() && now - cacheAtMs < CACHE_TTL_MS) return cachedApps

        val pm = context.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolved: List<ResolveInfo> = pm.queryIntentActivities(launcherIntent, 0)
        val apps = resolved.mapNotNull { info ->
            val packageName = info.activityInfo?.packageName ?: return@mapNotNull null
            if (packageName == context.packageName) return@mapNotNull null
            val label = info.loadLabel(pm)?.toString()?.trim().orEmpty()
            if (label.isBlank()) return@mapNotNull null
            AppEntry(label, packageName, AppNameMatcher.normalize(label))
        }.distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }

        cachedApps = apps
        cacheAtMs = now
        return apps
    }

    fun resolve(name: String, aliasesRaw: String = ""): AppEntry? {
        val aliases = AppNameMatcher.parseAliases(aliasesRaw)
        val aliasTarget = AppNameMatcher.aliasTarget(name, aliases)
        val requested = aliasTarget ?: name

        // An alias target may be a package name. Resolve it directly when possible.
        if (aliasTarget != null && aliasTarget.contains('.')) {
            return AppEntry(aliasTarget, aliasTarget, AppNameMatcher.normalize(aliasTarget))
        }

        val query = AppNameMatcher.extractRequestedAppName(requested)
        if (query.isBlank()) return null

        fun choose(apps: List<AppEntry>): AppEntry? {
            apps.firstOrNull { it.normalizedLabel == query }?.let { return it }
            apps.firstOrNull { it.normalizedLabel.contains(query) || query.contains(it.normalizedLabel) }?.let { return it }
            val best = apps.map { it to AppNameMatcher.similarity(query, it.normalizedLabel) }
                .maxByOrNull { it.second }
            return if (best != null && best.second >= FUZZY_THRESHOLD) best.first else null
        }

        choose(discover())?.let { return it }
        return choose(discover(force = true))
    }

    fun count(): Int = discover().size

    companion object {
        private const val CACHE_TTL_MS = 60_000L
        private const val FUZZY_THRESHOLD = 0.64
    }
}
