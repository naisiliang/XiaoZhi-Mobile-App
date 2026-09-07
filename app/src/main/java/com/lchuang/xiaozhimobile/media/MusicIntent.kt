package com.lchuang.xiaozhimobile.media

/** The only media operations understood by the structured music pipeline. */
enum class MusicIntentType {
    OPEN,
    PLAY,
    PAUSE,
    PREVIOUS,
    NEXT,
    SEARCH,
    SELECT_FIRST,
    SELECT_SECOND,
}

/** Search fields remain typed so an adapter can use song and artist semantics separately. */
data class MusicSearchQuery(
    val song: String? = null,
    val artist: String? = null,
) {
    init {
        require(!song.isNullOrBlank() || !artist.isNullOrBlank()) {
            "A music search requires a song or artist"
        }
    }

    val normalizedSong: String? = song?.trim()?.takeIf(String::isNotBlank)
    val normalizedArtist: String? = artist?.trim()?.takeIf(String::isNotBlank)
}

/** A normalized intent; natural-language parsing belongs in MusicIntentResolver. */
data class MusicIntent(
    val type: MusicIntentType,
    val query: MusicSearchQuery? = null,
) {
    init {
        if (type == MusicIntentType.SEARCH) {
            require(query != null) { "Search intents require a query" }
        } else {
            require(query == null) { "Only search intents may contain a query" }
        }
    }
}

enum class MusicClarificationReason {
    CONFLICTING_COMMANDS,
    MULTIPLE_RESULTS,
    MISSING_SEARCH_TARGET,
    MISSING_PLAY_TARGET,
}

sealed interface MusicIntentResolution {
    data class Resolved(val intent: MusicIntent) : MusicIntentResolution

    data class NeedsClarification(
        val reason: MusicClarificationReason,
        val prompt: String? = null,
    ) : MusicIntentResolution

    data object Empty : MusicIntentResolution

    data object Unsupported : MusicIntentResolution
}
