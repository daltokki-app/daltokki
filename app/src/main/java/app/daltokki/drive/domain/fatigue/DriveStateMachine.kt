package app.daltokki.drive.domain.fatigue

import app.daltokki.drive.domain.model.DriveEvent
import app.daltokki.drive.domain.model.DriveState
import app.daltokki.drive.domain.model.Gear

/**
 * 주차 → (기준 학습) → 주행 → 휴식 → 주행 … → 운행 종료 흐름을 관리합니다.
 */
class DriveStateMachine(private val cfg: FatigueConfig) {

    var state: DriveState = DriveState.PARKED
        private set

    /** 연속 운전 시간(충분한 휴식 시 0으로 초기화) */
    var continuousDriveMs = 0L
        private set

    /** 이번 운행의 총 주행 시간 */
    var tripDrivingMs = 0L
        private set

    var tripStartMs = -1L
        private set

    var parkStartMs = -1L
        private set

    private var restCredited = false
    private var lastT = -1L

    fun update(t: Long, gear: Gear, speedKmh: Float, baselineReady: Boolean): List<DriveEvent> {
        val dt = if (lastT < 0) 0L else (t - lastT).coerceIn(0L, 1_000L)
        lastT = t
        val events = mutableListOf<DriveEvent>()
        val moving = gear == Gear.DRIVE && speedKmh > 3f

        when (state) {
            DriveState.PARKED -> if (moving) {
                state = if (baselineReady) DriveState.MONITORING else DriveState.CALIBRATING
                tripStartMs = t
                continuousDriveMs = 0L
                tripDrivingMs = 0L
                events += DriveEvent.TripStarted
            }

            DriveState.CALIBRATING, DriveState.MONITORING -> {
                if (gear == Gear.PARK) {
                    state = DriveState.RESTING
                    parkStartMs = t
                    restCredited = false
                    events += DriveEvent.RestStarted
                } else {
                    continuousDriveMs += dt
                    tripDrivingMs += dt
                    if (state == DriveState.CALIBRATING && baselineReady) {
                        state = DriveState.MONITORING
                        events += DriveEvent.CalibrationDone
                    }
                }
            }

            DriveState.RESTING -> {
                val parked = t - parkStartMs
                if (!restCredited && parked >= cfg.restResetMs) {
                    restCredited = true
                    continuousDriveMs = 0L
                    events += DriveEvent.RestCompleted
                }
                if (moving) {
                    state = if (baselineReady) DriveState.MONITORING else DriveState.CALIBRATING
                    events += DriveEvent.DrivingResumed(restCredited)
                } else if (parked >= cfg.tripEndParkMs) {
                    state = DriveState.PARKED
                    events += DriveEvent.TripEnded
                }
            }
        }
        return events
    }

    /** 사용자가 직접 운행을 끝낼 때 */
    fun endTrip(): List<DriveEvent> {
        if (state == DriveState.PARKED) return emptyList()
        state = DriveState.PARKED
        return listOf(DriveEvent.TripEnded)
    }
}
