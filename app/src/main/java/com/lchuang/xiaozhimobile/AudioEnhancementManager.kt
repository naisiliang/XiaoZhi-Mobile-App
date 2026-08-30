package com.lchuang.xiaozhimobile

import android.media.AudioRecord
import android.media.audiofx.AudioEffect
import android.media.audiofx.NoiseSuppressor
import java.util.concurrent.atomic.AtomicBoolean

class AudioEnhancementManager {
    fun attach(record: AudioRecord): AutoCloseable {
        return try {
            if (!NoiseSuppressor.isAvailable()) return NO_OP
            val suppressor = NoiseSuppressor.create(record.audioSessionId) ?: return NO_OP
            try {
                val status = suppressor.setEnabled(true)
                if (status != AudioEffect.SUCCESS) {
                    try { suppressor.release() } catch (_: Throwable) {}
                    return NO_OP
                }
            } catch (_: Throwable) {
                try { suppressor.release() } catch (_: Throwable) {}
                return NO_OP
            }
            val closed = AtomicBoolean(false)
            AutoCloseable {
                if (closed.compareAndSet(false, true)) {
                    try { suppressor.release() } catch (_: Throwable) {}
                }
            }
        } catch (_: Throwable) {
            NO_OP
        }
    }

    private companion object {
        val NO_OP = AutoCloseable {}
    }
}
