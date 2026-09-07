package com.lchuang.xiaozhimobile

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicBoolean

class LocationProvider(private val context: Context) {
    fun getCurrentLocation(timeoutMs: Long = 4000L, callback: (Result<Location>) -> Unit) {
        val fine = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) {
            callback(Result.failure(IllegalStateException("PERMISSION_DENIED")))
            return
        }

        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val provider = when {
            fine && isEnabled(manager, LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            isEnabled(manager, LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> null
        }
        if (provider == null) {
            bestLastKnown(manager, fine)?.let { callback(Result.success(it)); return }
            callback(Result.failure(IllegalStateException("PROVIDER_UNAVAILABLE")))
            return
        }

        val delivered = AtomicBoolean(false)
        val handler = Handler(Looper.getMainLooper())
        var cancellationSignal: CancellationSignal? = null
        var legacyListener: LocationListener? = null
        var timeoutRunnable: Runnable? = null
        fun cleanupRequest() {
            try { cancellationSignal?.cancel() } catch (_: Throwable) {}
            legacyListener?.let { listener ->
                try { manager.removeUpdates(listener) } catch (_: Throwable) {}
            }
            timeoutRunnable?.let(handler::removeCallbacks)
        }
        val finish: (Result<Location>) -> Unit = { result ->
            if (delivered.compareAndSet(false, true)) {
                cleanupRequest()
                callback(result)
            }
        }
        val timeout = Runnable {
            if (!delivered.get()) {
                bestLastKnown(manager, fine)?.let { finish(Result.success(it)) }
                    ?: finish(Result.failure(IllegalStateException("TIMEOUT")))
            }
        }
        timeoutRunnable = timeout
        handler.postDelayed(timeout, timeoutMs.coerceAtLeast(500L))

        try {
            if (Build.VERSION.SDK_INT >= 30) {
                cancellationSignal = CancellationSignal()
                manager.getCurrentLocation(provider, cancellationSignal, context.mainExecutor) { location ->
                    if (location != null) finish(Result.success(location))
                    else bestLastKnown(manager, fine)?.let { finish(Result.success(it)) }
                        ?: finish(Result.failure(IllegalStateException("TIMEOUT")))
                }
            } else {
                @Suppress("DEPRECATION")
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        try { manager.removeUpdates(this) } catch (_: SecurityException) {}
                        finish(Result.success(location))
                    }
                    @Deprecated("Deprecated in Java") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                    override fun onProviderEnabled(provider: String) {}
                    override fun onProviderDisabled(provider: String) {}
                }
                legacyListener = listener
                @Suppress("DEPRECATION")
                manager.requestSingleUpdate(provider, listener, Looper.getMainLooper())
            }
        } catch (_: SecurityException) {
            finish(Result.failure(IllegalStateException("PERMISSION_DENIED")))
        } catch (_: IllegalArgumentException) {
            finish(Result.failure(IllegalStateException("PROVIDER_UNAVAILABLE")))
        }
    }

    private fun isEnabled(manager: LocationManager, provider: String): Boolean = try {
        manager.isProviderEnabled(provider)
    } catch (_: Throwable) { false }

    private fun bestLastKnown(manager: LocationManager, fine: Boolean): Location? {
        val providers = if (fine) listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        else listOf(LocationManager.NETWORK_PROVIDER)
        return providers.mapNotNull { provider ->
            try { manager.getLastKnownLocation(provider) } catch (_: SecurityException) { null }
        }.maxByOrNull { it.time }
    }
}
