package app.daltokki.drive.data.vehicle

import android.car.Car
import android.car.drivingstate.CarUxRestrictionsManager
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOf

/**
 * AAOS Car 서비스 연결 관리.
 * Android Automotive가 아닌 기기(휴대폰 등)에서는 Car 클래스를 아예 건드리지 않고 null/false를 돌려주므로
 * android.car 라이브러리가 없는 휴대폰에서도 앱이 죽지 않습니다.
 */
object CarConnection {
    private const val TAG = "CarConnection"

    /** Car 객체 (휴대폰에서 Car 클래스를 불러오지 않도록 Any로 보관) */
    private var carRef: Any? = null

    fun isAutomotive(context: Context): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)

    @Synchronized
    fun get(context: Context): Car? {
        if (!isAutomotive(context)) return null
        (carRef as? Car)?.let { if (it.isConnected) return it }
        return try {
            Car.createCar(context.applicationContext).also { carRef = it }
        } catch (t: Throwable) {
            Log.w(TAG, "Car API를 사용할 수 없습니다", t)
            null
        }
    }

    /**
     * 주행 중 화면 제한(UX Restriction) 여부.
     * true면 글자가 많은 화면·입력창을 숨기고 음성 위주 화면만 보여야 합니다.
     * 휴대폰에서는 항상 false.
     */
    fun distractionRestrictedFlow(context: Context): Flow<Boolean> {
        if (!isAutomotive(context)) return flowOf(false)
        return uxRestrictionFlow(context)
    }

    private fun uxRestrictionFlow(context: Context): Flow<Boolean> = callbackFlow {
        val c = get(context)
        val manager = try {
            c?.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as? CarUxRestrictionsManager
        } catch (t: Throwable) {
            null
        }
        if (manager == null) {
            trySend(false)
            awaitClose { }
            return@callbackFlow
        }
        trySend(manager.currentCarUxRestrictions?.isRequiresDistractionOptimization ?: false)
        val listener = CarUxRestrictionsManager.OnUxRestrictionsChangedListener { r ->
            trySend(r.isRequiresDistractionOptimization)
        }
        manager.registerListener(listener)
        awaitClose { manager.unregisterListener() }
    }
}
