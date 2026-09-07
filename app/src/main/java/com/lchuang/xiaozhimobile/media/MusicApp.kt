package com.lchuang.xiaozhimobile.media

import java.util.Locale

enum class MusicAppKind {
    NETEASE,
    QISHUI,
    KUGOU,
    QQ_MUSIC,
    OTHER,
}

data class MusicApp(
    val packageName: String,
    val displayName: String,
    val kind: MusicAppKind,
) {
    init {
        require(packageName.isNotBlank()) { "Music app package must not be blank" }
        require(displayName.isNotBlank()) { "Music app display name must not be blank" }
    }

    companion object {
        fun fromInstalled(packageName: String, displayName: String): MusicApp? {
            val normalizedPackage = packageName.trim().lowercase(Locale.ROOT)
            val label = displayName.trim()
            if (normalizedPackage.isBlank() || label.isBlank()) return null
            val knownKind = KNOWN_PACKAGES[normalizedPackage]
            if (knownKind != null) return MusicApp(normalizedPackage, label, knownKind)
            if (!MUSIC_LABEL_MARKERS.any(label.lowercase(Locale.ROOT)::contains)) return null
            return MusicApp(normalizedPackage, label, MusicAppKind.OTHER)
        }

        private val KNOWN_PACKAGES = mapOf(
            "com.netease.cloudmusic" to MusicAppKind.NETEASE,
            "com.luna.music" to MusicAppKind.QISHUI,
            "com.kugou.android" to MusicAppKind.KUGOU,
            "com.tencent.qqmusic" to MusicAppKind.QQ_MUSIC,
        )

        private val MUSIC_LABEL_MARKERS = setOf(
            "音乐",
            "music",
            "spotify",
            "酷我",
            "咪咕",
        )
    }
}
