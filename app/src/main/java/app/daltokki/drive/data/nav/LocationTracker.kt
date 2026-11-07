package app.daltokki.drive.data.nav

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * 현재 위치(휴게소 찾기용). 안드로이드 LocationManager를 씁니다.
 * TODO(Pleos FusedLocation): Pleos FusedLocation SDK가 '사용 가능'이 되면 차량 위치로 교체하면 더 정확합니다.
 *  (현재 Playground 문서 기준 '준비 중')
 */
class LocationTracker(context: Context) {

    private val ctx = context.applicationContext
    private val manager = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    @Volatile
    var last: Location? = null
        private set

    private var started = false

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            last = location
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        override fun onProviderEnabled(provider: String) = Unit
        override fun onProviderDisabled(provider: String) = Unit
    }

    val hasPermission: Boolean
        get() = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun start() {
        val lm = manager ?: return
        if (started || !hasPermission) return
        started = true
        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            try {
                if (!lm.isProviderEnabled(provider)) continue
                lm.getLastKnownLocation(provider)?.let { if (last == null) last = it }
                lm.requestLocationUpdates(provider, 2_000L, 10f, listener, Looper.getMainLooper())
            } catch (t: Throwable) {
                Log.w(TAG, "위치 수신 실패: $provider", t)
            }
        }
    }

    fun stop() {
        if (!started) return
        started = false
        try {
            manager?.removeUpdates(listener)
        } catch (_: Throwable) {
        }
    }

    private companion object {
        const val TAG = "LocationTracker"
    }
}
