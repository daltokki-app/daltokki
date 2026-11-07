package app.daltokki.drive.domain.fatigue

import app.daltokki.drive.domain.model.BusyReason
import app.daltokki.drive.domain.model.Gear
import app.daltokki.drive.domain.model.VehicleSnapshot
import kotlin.math.abs

/**
 * 운전에 집중해야 하는 순간(차선 변경, 제동·급감속, 큰 핸들 조작)을 알아냅니다.
 * 이때 달토끼가 말을 시작하면 운전자의 주의를 뺏으므로, 끝난 뒤 3초까지 새 말 걸기를 미룹니다.
 * (졸음 판정과는 별개입니다. 판정은 계속하고, 말하는 시점만 늦춥니다)
 */
class WorkloadGate(private val cfg: FatigueConfig) {

    private var prevT = -1L
    private var prevSpeed = 0f
    private var prevAngle: Float? = null
    private var busyUntil = -1L
    private var reason: BusyReason? = null

    /** @return 지금 바쁜 순간이면 그 이유, 아니면 null */
    fun update(s: VehicleSnapshot): BusyReason? {
        val t = s.timestampMs
        val dt = if (prevT < 0) 0L else t - prevT
        val moving = s.gear == Gear.DRIVE && s.speedKmh > 5f
        var now: BusyReason? = null

        if (s.turnSignalOn == true) now = BusyReason.LANE_CHANGE
        if (now == null && moving && s.brakePressed == true && s.speedKmh >= cfg.busyBrakeMinSpeedKmh) {
            now = BusyReason.BRAKING
        }
        if (now == null && dt in 1L..1_000L && prevSpeed > 20f &&
            prevSpeed - s.speedKmh >= cfg.hardDecelKmhPerSec * dt / 1_000f
        ) {
            now = BusyReason.BRAKING
        }
        val angle = s.steeringAngleDeg
        if (now == null && moving && angle != null) {
            val last = prevAngle
            val rate = if (last != null && dt in 1L..1_000L) abs(angle - last) / (dt / 1_000f) else 0f
            if (abs(angle) >= cfg.busyAngleDeg || rate >= cfg.busySteerRateDegPerSec) now = BusyReason.TURNING
        }

        prevT = t
        prevSpeed = s.speedKmh
        prevAngle = angle
        if (now != null) {
            busyUntil = t + cfg.busyHoldMs
            reason = now
        }
        return if (t < busyUntil) reason else null
    }
}
