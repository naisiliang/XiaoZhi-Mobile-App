package com.lchuang.xiaozhimobile

enum class CommandAudioCaptureFailureKind {
    PERMISSION,
    AUDIO_INIT,
    AUDIO_START
}

class CommandAudioCaptureException(
    val kind: CommandAudioCaptureFailureKind,
    cause: Throwable? = null
) : IllegalStateException(kind.name, cause)

object CommandAudioCaptureRecovery {
    fun message(kind: CommandAudioCaptureFailureKind): String = when (kind) {
        CommandAudioCaptureFailureKind.PERMISSION -> "请先授予麦克风权限，我才能继续听你说话。"
        CommandAudioCaptureFailureKind.AUDIO_INIT -> "麦克风暂时不可用，我先结束这次会话。"
        CommandAudioCaptureFailureKind.AUDIO_START -> "麦克风启动失败，我先结束这次会话。"
    }
}
