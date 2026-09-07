package com.lchuang.xiaozhimobile.media

import java.util.Locale

enum class MusicResolutionSource {
    EXPLICIT,
    EXPLICIT_UNAVAILABLE,
    SAVED_DEFAULT,
    ACTIVE_MEDIA_SESSION,
    SINGLE_INSTALLED,
    USER_CHOICE_REQUIRED,
    NONE,
}

data class MusicResolution(
    val app: MusicApp? = null,
    val candidates: List<MusicApp> = emptyList(),
    val source: MusicResolutionSource,
    val repairedMissingDefault: Boolean = false,
) {
    init {
        require(candidates.distinctBy(MusicApp::packageName).size == candidates.size) {
            "Music candidates must have unique packages"
        }
        if (source == MusicResolutionSource.USER_CHOICE_REQUIRED) {
            require(app == null) { "A user-choice resolution cannot preselect an app" }
            require(candidates.size > 1) { "User choice requires multiple candidates" }
        }
    }
}

/** Chooses a music app without executing an Android action. */
class MusicAppResolver(
    private val installedApps: () -> List<MusicApp>,
    private val savedDefaultPackage: () -> String = { "" },
    private val persistDefaultPackage: (String) -> Unit = {},
    private val activeMediaSession: () -> MusicApp? = { null },
) {
    fun installedMusicApps(): List<MusicApp> = installedApps()
        .asSequence()
        .filter { it.packageName.isNotBlank() && it.displayName.isNotBlank() }
        .distinctBy { it.packageName.lowercase(Locale.ROOT) }
        .toList()

    fun resolve(explicit: String? = null): MusicResolution {
        val installed = installedMusicApps()
        val explicitReference = explicit?.trim()?.takeIf(String::isNotBlank)
        if (explicitReference != null) {
            val selected = uniqueMatch(installed, explicitReference)
            return if (selected == null) {
                MusicResolution(source = MusicResolutionSource.EXPLICIT_UNAVAILABLE)
            } else {
                MusicResolution(app = selected, source = MusicResolutionSource.EXPLICIT)
            }
        }

        var repaired = false
        val saved = savedDefaultPackage().trim()
        if (saved.isNotBlank()) {
            val selected = installed.singleOrNull {
                it.packageName.equals(saved, ignoreCase = true)
            }
            if (selected != null) {
                return MusicResolution(app = selected, source = MusicResolutionSource.SAVED_DEFAULT)
            }
            persistDefaultPackage("")
            repaired = true
        }

        activeMediaSession()?.takeIf { it.packageName.isNotBlank() }?.let { active ->
            val installedActive = installed.singleOrNull {
                it.packageName.equals(active.packageName, ignoreCase = true)
            }
            return MusicResolution(
                app = installedActive ?: active,
                source = MusicResolutionSource.ACTIVE_MEDIA_SESSION,
                repairedMissingDefault = repaired,
            )
        }

        return when (installed.size) {
            0 -> MusicResolution(
                source = MusicResolutionSource.NONE,
                repairedMissingDefault = repaired,
            )
            1 -> MusicResolution(
                app = installed.single(),
                source = MusicResolutionSource.SINGLE_INSTALLED,
                repairedMissingDefault = repaired,
            )
            else -> MusicResolution(
                candidates = installed,
                source = MusicResolutionSource.USER_CHOICE_REQUIRED,
                repairedMissingDefault = repaired,
            )
        }
    }

    fun saveDefault(app: MusicApp): Boolean {
        val selected = installedMusicApps().singleOrNull {
            it.packageName.equals(app.packageName, ignoreCase = true)
        } ?: return false
        persistDefaultPackage(selected.packageName)
        return true
    }

    fun clearDefault() {
        persistDefaultPackage("")
    }

    private fun uniqueMatch(installed: List<MusicApp>, reference: String): MusicApp? {
        val byPackage = installed.filter { it.packageName.equals(reference, ignoreCase = true) }
        if (byPackage.size == 1) return byPackage.single()
        return installed.filter { normalize(it.displayName) == normalize(reference) }.singleOrNull()
    }

    private fun normalize(value: String): String =
        value.lowercase(Locale.ROOT).replace(WHITESPACE, " ").trim()

    private companion object {
        val WHITESPACE = Regex("\\s+")
    }
}
