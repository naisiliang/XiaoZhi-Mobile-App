package com.lchuang.xiaozhimobile.media

/** Minimal identity for the currently active media session. */
data class ActiveMediaSession(val packageName: String) {
    init {
        require(packageName.isNotBlank()) { "Active media session package must not be blank" }
    }
}

enum class MediaKeyAction {
    PLAY,
    PAUSE,
    PREVIOUS,
    NEXT,
}

fun interface MediaKeyDispatcher {
    fun dispatch(action: MediaKeyAction): Boolean
}

enum class MediaSessionFallbackCode {
    DISPATCHED,
    DELEGATE_TO_ADAPTER,
    NO_ACTIVE_SESSION,
    UNSUPPORTED_INTENT,
    DISPATCH_FAILED,
}

data class MediaSessionFallbackResult(
    val success: Boolean,
    val code: MediaSessionFallbackCode,
    val sessionPackage: String? = null,
)

/**
 * Dispatches only the existing Android media-key controls when no dedicated
 * app adapter can handle the typed intent.
 */
class MediaSessionFallback(
    private val activeSession: () -> ActiveMediaSession?,
    private val mediaKeyDispatcher: (MediaKeyAction) -> Boolean,
) {
    fun execute(
        intent: MusicIntent,
        dedicatedAdapterAvailable: Boolean,
    ): MediaSessionFallbackResult {
        if (dedicatedAdapterAvailable) {
            return MediaSessionFallbackResult(
                success = false,
                code = MediaSessionFallbackCode.DELEGATE_TO_ADAPTER,
            )
        }

        val action = actionFor(intent) ?: return MediaSessionFallbackResult(
            success = false,
            code = MediaSessionFallbackCode.UNSUPPORTED_INTENT,
        )
        val session = runCatching { activeSession() }.getOrNull()
            ?: return MediaSessionFallbackResult(
                success = false,
                code = MediaSessionFallbackCode.NO_ACTIVE_SESSION,
            )
        val dispatched = runCatching { mediaKeyDispatcher(action) }.getOrDefault(false)
        return if (dispatched) {
            MediaSessionFallbackResult(
                success = true,
                code = MediaSessionFallbackCode.DISPATCHED,
                sessionPackage = session.packageName,
            )
        } else {
            MediaSessionFallbackResult(
                success = false,
                code = MediaSessionFallbackCode.DISPATCH_FAILED,
                sessionPackage = session.packageName,
            )
        }
    }

    private fun actionFor(intent: MusicIntent): MediaKeyAction? = when (intent.type) {
        MusicIntentType.PLAY -> MediaKeyAction.PLAY
        MusicIntentType.PAUSE -> MediaKeyAction.PAUSE
        MusicIntentType.PREVIOUS -> MediaKeyAction.PREVIOUS
        MusicIntentType.NEXT -> MediaKeyAction.NEXT
        MusicIntentType.OPEN,
        MusicIntentType.SEARCH,
        MusicIntentType.SELECT_FIRST,
        MusicIntentType.SELECT_SECOND -> null
    }
}
