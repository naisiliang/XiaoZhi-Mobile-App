package com.lchuang.xiaozhimobile.media

import java.util.Locale

/**
 * Converts a short voice/text command into a typed music intent.
 *
 * This class deliberately does not select an app, inspect a screen, or emit
 * accessibility actions. It only recognizes safe, bounded intent shapes.
 */
class MusicIntentResolver {
    fun resolve(command: String): MusicIntentResolution {
        val normalized = normalize(command)
        if (normalized.isBlank()) return MusicIntentResolution.Empty

        if (hasConflictingControls(normalized)) {
            return MusicIntentResolution.NeedsClarification(
                reason = MusicClarificationReason.CONFLICTING_COMMANDS,
                prompt = "你想让我执行哪一个音乐操作？",
            )
        }

        if (isSelectionAmbiguous(normalized)) {
            return MusicIntentResolution.NeedsClarification(
                reason = MusicClarificationReason.MULTIPLE_RESULTS,
                prompt = "结果不止一个，请说第一首或第二首。",
            )
        }

        when {
            isOpen(normalized) -> return resolved(MusicIntentType.OPEN)
            isPause(normalized) -> return resolved(MusicIntentType.PAUSE)
            isPrevious(normalized) -> return resolved(MusicIntentType.PREVIOUS)
            isNext(normalized) -> return resolved(MusicIntentType.NEXT)
            isFirstSelection(normalized) -> return resolved(MusicIntentType.SELECT_FIRST)
            isSecondSelection(normalized) -> return resolved(MusicIntentType.SELECT_SECOND)
        }

        if (isSearchCommand(normalized)) {
            return parseSearch(normalized)
        }

        if (isPlayCommand(normalized)) {
            return resolved(MusicIntentType.PLAY)
        }

        if (looksLikeIncompletePlay(normalized)) {
            return MusicIntentResolution.NeedsClarification(
                reason = MusicClarificationReason.MISSING_PLAY_TARGET,
                prompt = "你想播放当前歌曲，还是搜索一首歌？",
            )
        }

        return MusicIntentResolution.Unsupported
    }

    private fun parseSearch(normalized: String): MusicIntentResolution {
        val body = searchBody(normalized)
        if (body.isBlank()) {
            return MusicIntentResolution.NeedsClarification(
                reason = MusicClarificationReason.MISSING_SEARCH_TARGET,
                prompt = "请告诉我歌名或歌手。",
            )
        }

        val songAndArtist = SONG_AND_ARTIST.find(body)
        val artistAndSong = ARTIST_AND_SONG.find(body)
        val query = when {
            songAndArtist != null -> queryOrNull(
                song = cleanQueryPart(songAndArtist.groupValues[1]),
                artist = cleanQueryPart(songAndArtist.groupValues[2]),
            )
            artistAndSong != null -> queryOrNull(
                artist = cleanQueryPart(artistAndSong.groupValues[1]),
                song = cleanQueryPart(artistAndSong.groupValues[2]),
            )
            else -> {
                val quoted = QUOTED_SONG.find(body)
                if (quoted != null) {
                    val song = cleanQueryPart(quoted.groupValues[1])
                    val artist = cleanQueryPart(body.removeRange(quoted.range))
                    queryOrNull(song = song, artist = artist)
                } else {
                    queryOrNull(song = cleanQueryPart(body))
                }
            }
        }

        if (query == null) {
            return MusicIntentResolution.NeedsClarification(
                reason = MusicClarificationReason.MISSING_SEARCH_TARGET,
                prompt = "请告诉我歌名或歌手。",
            )
        }

        return MusicIntentResolution.Resolved(
            MusicIntent(type = MusicIntentType.SEARCH, query = query),
        )
    }

    private fun queryOrNull(song: String? = null, artist: String? = null): MusicSearchQuery? {
        val cleanSong = song?.trim()?.takeIf(String::isNotBlank)
        val cleanArtist = artist?.trim()?.takeIf(String::isNotBlank)
        return if (cleanSong == null && cleanArtist == null) {
            null
        } else {
            MusicSearchQuery(song = cleanSong, artist = cleanArtist)
        }
    }

    private fun searchBody(normalized: String): String {
        val withoutPrefix = SEARCH_PREFIX.replaceFirst(normalized, "")
        if (withoutPrefix != normalized) return withoutPrefix.trim()

        // A non-generic “播放 + title” phrase is a search request. Plain
        // “播放音乐/继续播放” is handled by isPlayCommand instead.
        return PLAY_SEARCH_PREFIX.replaceFirst(normalized, "").trim()
    }

    private fun cleanQueryPart(value: String): String = value
        .trim()
        .trim(',', '，', '。', '.', '！', '!', '？', '?', ':', '：', '、')
        .removePrefix("歌曲")
        .removePrefix("音乐")
        .trim()

    private fun isOpen(normalized: String): Boolean = normalized == "打开音乐" ||
        normalized == "打开音乐app" || normalized == "进入音乐" ||
        normalized == "打开播放器" || normalized == "打开音乐播放器"

    private fun isPause(normalized: String): Boolean = normalized.contains("暂停") ||
        normalized == "停一下音乐" || normalized == "停止音乐"

    private fun isPrevious(normalized: String): Boolean =
        normalized.contains("上一首") || normalized.contains("上一曲") ||
            normalized.contains("切上一首")

    private fun isNext(normalized: String): Boolean =
        normalized.contains("下一首") || normalized.contains("下一曲") ||
            normalized.contains("切下一首")

    private fun isFirstSelection(normalized: String): Boolean =
        FIRST_RESULT_MARKER.containsMatchIn(normalized)

    private fun isSecondSelection(normalized: String): Boolean =
        SECOND_RESULT_MARKER.containsMatchIn(normalized)

    private fun isSelectionAmbiguous(normalized: String): Boolean =
        normalized.contains("前两个") || normalized.contains("前两首") ||
            normalized.contains("哪一首") || normalized.contains("哪个结果") ||
            normalized.contains("哪个") && normalized.contains("结果")

    private fun isSearchCommand(normalized: String): Boolean =
        SEARCH_PREFIX.containsMatchIn(normalized) ||
            PLAY_SEARCH_PREFIX.containsMatchIn(normalized) ||
            normalized.startsWith("找一首") || normalized.startsWith("来一首")

    private fun isPlayCommand(normalized: String): Boolean = normalized in PLAY_COMMANDS

    private fun looksLikeIncompletePlay(normalized: String): Boolean = normalized in INCOMPLETE_PLAY_COMMANDS

    private fun hasConflictingControls(normalized: String): Boolean {
        val previous = isPrevious(normalized)
        val next = isNext(normalized)
        val pause = isPause(normalized)
        val play = isPlayCommand(normalized) || normalized == "播放"
        return previous && next || pause && play
    }

    private fun resolved(type: MusicIntentType) =
        MusicIntentResolution.Resolved(MusicIntent(type))

    private fun normalize(value: String): String = value
        .lowercase(Locale.ROOT)
        .replace(WHITESPACE, " ")
        .trim()
        .trim(',', '，', '。', '.', '！', '!', '？', '?')

    private companion object {
        val WHITESPACE = Regex("\\s+")
        val SEARCH_PREFIX = Regex("^(搜索|搜一下|搜索歌曲|查找|查一下|找一下|找一首|来一首)\\s*")
        val PLAY_SEARCH_PREFIX = Regex("^(播放歌曲|播放一首|点播)\\s*")
        val SONG_AND_ARTIST = Regex("^(.+?)[,，、 ]*歌手(?:是)?\\s*(.+)$")
        val ARTIST_AND_SONG = Regex("^(.+?)(?:的|演唱的|演唱)\\s*(.+)$")
        val QUOTED_SONG = Regex("[《『“\\\"](.+?)[》』”\\\"]")
        val FIRST_RESULT_MARKER = Regex("(?:选|选择|播放|点播)?\\s*(?:第\\s*一(?:首|个|项|条|个结果)?|第一首|第一个结果|first(?: song| result| one)?)$")
        val SECOND_RESULT_MARKER = Regex("(?:选|选择|播放|点播)?\\s*(?:第\\s*二(?:首|个|项|条|个结果)?|第二首|第二个结果|second(?: song| result| one)?)$")
        val PLAY_COMMANDS = setOf(
            "播放音乐",
            "继续播放",
            "继续播放音乐",
            "开始播放",
            "开始播放音乐",
            "放音乐",
            "放一下音乐",
            "播放一下音乐",
            "来首歌",
            "来一首歌",
        )
        val INCOMPLETE_PLAY_COMMANDS = setOf("播放", "播放一下", "来一首", "找歌", "搜索")
    }
}
