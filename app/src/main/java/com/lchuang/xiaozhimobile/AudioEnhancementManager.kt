package com.lchuang.xiaozhimobile

import android.media.AudioRecord
import android.media.audiofx.AudioEffect
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

class AudioEnhancementManager {
    fun attach(record: AudioRecord): AutoCloseable {
        val available = try {
            NoiseSuppressor.isAvailable()
        } catch (_: Throwable) {
            logFallback("availability check threw")
            return NO_OP
        }
        if (!available) {
            logFallback("unavailable")
            return NO_OP
        }

        val suppressor = try {
            NoiseSuppressor.create(record.audioSessionId)
        } catch (_: Throwable) {
            logFallback("create threw")
            return NO_OP
        }
        if (suppressor == null) {
            logFallback("create returned null")
            return NO_OP
        }

        try {
            val status = suppressor.setEnabled(true)
            if (status != AudioEffect.SUCCESS) {
                try { suppressor.release() } catch (_: Throwable) {}
                logFallback("enable failed")
                return NO_OP
            }
        } catch (_: Throwable) {
            try { suppressor.release() } catch (_: Throwable) {}
            logFallback("enable threw")
            return NO_OP
        }

        val closed = AtomicBoolean(false)
        return AutoCloseable {
            if (closed.compareAndSet(false, true)) {
                try { suppressor.release() } catch (_: Throwable) {}
            }
        }
    }

    private fun logFallback(reason: String) {
        try {
            Log.w(TAG, "NoiseSuppressor fallback: $reason")
        } catch (_: Throwable) {
            // Diagnostics must never change the safe no-op behavior.
        }
    }

    private companion object {
        const val TAG = "AudioEnhancementManager"
        val NO_OP = AutoCloseable {}
    }
}
