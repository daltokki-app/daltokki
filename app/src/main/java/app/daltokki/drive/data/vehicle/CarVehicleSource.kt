package app.daltokki.drive.data.vehicle

import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.util.Log
import app.daltokki.drive.domain.model.Gear
import app.daltokki.drive.domain.model.VehicleSnapshot
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 실차 데이터 공급원.
 * - 속도·기어: AAOS 표준 Car API (CarPropertyManager)
 * - 핸들 각도·방향지시등·경고등(차로 유지 보조, 운전자 주의 경고)·내기순환: Pleos Vehicle SDK → [VehicleSignalsProvider]
 *
 * 10Hz로 최신 값을 묶어 VehicleSnapshot을 내보냅니다.
 * 이 클래스는 차량(Android Automotive)에서만 만들어집니다. 휴대폰에서는 시뮬레이션만 씁니다.
 */
class CarVehicleSource(
    private val context: Context,
    private val signals: VehicleSignalsProvider,
) : VehicleDataSource {

    override val name: String = "실차 (Car API + Pleos Vehicle SDK)"

    private val _status = MutableStateFlow("대기")
    override val status: StateFlow<String> = _status

    private class Latest {
        @Volatile var speedKmh = 0f
        @Volatile var gear = Gear.UNKNOWN
    }

    override fun snapshots(): Flow<VehicleSnapshot> = callbackFlow {
        val car = CarConnection.get(context)
        if (car == null) {
            _status.value = "Car API 없음 (Android Automotive 기기에서 실행하세요)"
            close()
            return@callbackFlow
        }
        val pm = car.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
        val latest = Latest()
        val callback = object : CarPropertyManager.CarPropertyEventCallback {
            override fun onChangeEvent(value: CarPropertyValue<*>) {
                when (value.propertyId) {
                    VehiclePropertyIds.PERF_VEHICLE_SPEED ->
                        latest.speedKmh = abs((value.value as? Float) ?: 0f) * 3.6f
                    VehiclePropertyIds.GEAR_SELECTION ->
                        latest.gear = mapGear((value.value as? Int) ?: 0)
                }
            }

            override fun onErrorEvent(propertyId: Int, zone: Int) {
                Log.w(TAG, "Car property error: $propertyId")
            }
        }

        val messages = mutableListOf<String>()
        try {
            pm.registerCallback(callback, VehiclePropertyIds.PERF_VEHICLE_SPEED, CarPropertyManager.SENSOR_RATE_NORMAL)
            messages += "속도 OK"
        } catch (e: SecurityException) {
            messages += "속도 권한 필요(CAR_SPEED)"
        }
        try {
            pm.registerCallback(callback, VehiclePropertyIds.GEAR_SELECTION, CarPropertyManager.SENSOR_RATE_ONCHANGE)
            messages += "기어 OK"
        } catch (e: SecurityException) {
            messages += "기어 권한 필요(CAR_POWERTRAIN)"
        }
        try {
            signals.start()
        } catch (t: Throwable) {
            Log.w(TAG, "Pleos Vehicle SDK 시작 실패", t)
        }
        val carStatus = messages.joinToString(" · ")
        _status.value = "$carStatus · ${signals.status}"

        val ticker = launch {
            var n = 0
            while (isActive) {
                trySend(
                    VehicleSnapshot(
                        timestampMs = System.currentTimeMillis(),
                        speedKmh = latest.speedKmh,
                        gear = latest.gear,
                        steeringAngleDeg = signals.steeringAngleDeg(),
                        laneAssistActive = signals.laneAssistActive(),
                        laneDepartureWarning = signals.laneInterventionActive(),
                        turnSignalOn = signals.turnSignalOn(),
                        driverAttentionWarning = signals.driverAttentionWarning(),
                        recirculationOn = signals.recirculationOn(),
                        occupants = signals.occupants(),
                        fanSpeed = signals.fanSpeed(),
                    ),
                )
                // Pleos 연결 상태는 늦게 바뀌므로 2초마다 상태 문구를 새로 고침
                if (++n % 20 == 0) _status.value = "$carStatus · ${signals.status}"
                delay(100)
            }
        }

        awaitClose {
            ticker.cancel()
            try { pm.unregisterCallback(callback) } catch (_: Throwable) { }
            try { signals.stop() } catch (_: Throwable) { }
        }
    }

    private fun mapGear(v: Int): Gear = when (v) {
        GEAR_PARK -> Gear.PARK
        GEAR_REVERSE -> Gear.REVERSE
        GEAR_NEUTRAL -> Gear.NEUTRAL
        GEAR_DRIVE -> Gear.DRIVE
        in GEAR_FIRST..GEAR_NINTH -> Gear.DRIVE // 수동 단수
        else -> Gear.UNKNOWN
    }

    companion object {
        private const val TAG = "CarVehicleSource"
        // android.car.VehicleGear 값
        private const val GEAR_NEUTRAL = 0x0001
        private const val GEAR_REVERSE = 0x0002
        private const val GEAR_PARK = 0x0004
        private const val GEAR_DRIVE = 0x0008
        private const val GEAR_FIRST = 0x0010
        private const val GEAR_NINTH = 0x1000
    }
}
