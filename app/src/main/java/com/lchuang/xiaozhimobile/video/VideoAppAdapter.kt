package com.lchuang.xiaozhimobile.video

import com.lchuang.xiaozhimobile.accessibility.UiActionProposal
import com.lchuang.xiaozhimobile.accessibility.UiActionType
import com.lchuang.xiaozhimobile.screen.ContextCandidate
import com.lchuang.xiaozhimobile.screen.ContextTargetKind
import com.lchuang.xiaozhimobile.screen.ScreenContext

/** App semantics only; execution remains in the central UI/route executor. */
interface VideoAppAdapter {
    val id: String

    fun canHandle(packageName: String): Boolean

    fun propose(
        intent: VideoIntent,
        screen: ScreenContext,
        currentVideo: ContextCandidate?,
    ): VideoAdapterResolution
}

sealed interface VideoAdapterResolution {
    data class Proposed(
        val intent: VideoIntent,
        val proposal: UiActionProposal,
        val route: VideoSaveRoute,
    ) : VideoAdapterResolution {
        init {
            require(proposal.target?.kind == ContextTargetKind.VIDEO) {
                "A video proposal must target a video candidate"
            }
        }
    }

    data object Unsupported : VideoAdapterResolution
}

/** Generic semantic adapter for a video package when no dedicated profile exists. */
class GenericVideoAppAdapter(
    private val supportedPackage: String,
    override val id: String = "generic_video",
) : VideoAppAdapter {
    override fun canHandle(packageName: String): Boolean =
        packageName.trim().equals(supportedPackage, ignoreCase = true)

    override fun propose(
        intent: VideoIntent,
        screen: ScreenContext,
        currentVideo: ContextCandidate?,
    ): VideoAdapterResolution {
        if (!canHandle(screen.packageName) || currentVideo == null ||
            currentVideo.kind != ContextTargetKind.VIDEO ||
            currentVideo.generationId != screen.generationId ||
            currentVideo.packageName != screen.packageName ||
            currentVideo.windowFingerprint != screen.windowFingerprint
        ) {
            return VideoAdapterResolution.Unsupported
        }

        val route = when (intent.type) {
            VideoIntentType.SAVE_CURRENT -> VideoSaveRoute.APP_NATIVE_SAVE
            VideoIntentType.SHARE_CURRENT -> VideoSaveRoute.ANDROID_SHARE
            VideoIntentType.SAVE_PUBLIC_URL -> VideoSaveRoute.PUBLIC_URL_TO_MEDIA_STORE
        }
        return VideoAdapterResolution.Proposed(
            intent = intent,
            proposal = UiActionProposal(
                action = UiActionType.CLICK,
                context = screen,
                target = currentVideo,
            ),
            route = route,
        )
    }
}
