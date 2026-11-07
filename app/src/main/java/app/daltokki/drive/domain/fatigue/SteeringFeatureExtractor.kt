package app.daltokki.drive.domain.fatigue

import app.daltokki.drive.domain.model.DrivingFeatures
import app.daltokki.drive.domain.model.Gear
import app.daltokki.drive.domain.model.VehicleSnapshot
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 핸들 각도·속도 흐름에서 졸음 관련 지표를 뽑습니다.
 *
 * - 핸들 반전 빈도: 깨어 있는 운전자는 작은 보정을 자주 합니다. 졸리면 줄어듭니다.
 * - 핸들 무조작 후 급꺾음: 몇 초간 핸들을 거의 안 움직이다가 갑자기 크게 꺾는 패턴. 가장 강한 졸음 신호.
 * - 속도 편차: 크루즈 없이 속도가 들쭉날쭉해지는지.
 *
 * 고속(기본 60km/h 이상) 직진 구간에서만 계산하며, 차로 유지 보조가 켜져 있으면 계산하지 않습니다.
 * 방향지시등이 켜져 있거나 막 꺼진 직후(기본 3초)는 차선 변경으로 보고 빼서 오탐을 줄입니다.
 */
class SteeringFeatureExtractor(private val cfg: FatigueConfig) {

    private class Sample(val t: Long, val dtMs: Long, val valid: Boolean, val speed: Float)

    private val samples = ArrayDeque<Sample>()
    private val reversals = ArrayDeque<Long>()
    private val lapses = ArrayDeque<Long>()
    private var validMsInWindow = 0L
    private var prevT = -1L
    private var steeringSeen = false
    private var lastTurnSignalT = -1L

    // 반전 감지 상태
    private var hasExtremum = false
    private var extremum = 0f
    private var direction = 0

    // 핸들 무조작 후 급꺾음 감지 상태
    private var holdAnchor = 0f
    private var holdStart = -1L
    private var lastAngle = 0f
    private var lastAngleT = -1L
    private var watchUntil = -1L

    /** 마지막 샘플이 유효했다면 그 샘플의 시간 간격(ms), 아니면 0. 기준선 학습에 사용. */
    var lastValidDtMs = 0L
        private set

    /** 운행 시작 이후 누적 급꺾음 횟수 */
    var totalLapses = 0
        private set

    fun add(s: VehicleSnapshot) {
        val t = s.timestampMs
        val dt = if (prevT < 0) 0L else (t - prevT).coerceIn(0L, 1_000L)
        prevT = t

        val angle = s.steeringAngleDeg
        if (angle != null) steeringSeen = true
        if (s.turnSignalOn == true) lastTurnSignalT = t
        val laneChange = lastTurnSignalT >= 0 && t - lastTurnSignalT <= cfg.turnSignalHoldMs
        val valid = angle != null &&
            s.gear == Gear.DRIVE &&
            s.speedKmh >= cfg.minValidSpeedKmh &&
            abs(angle) <= cfg.maxStraightAngleDeg &&
            !s.laneAssistActive &&
            !laneChange

        samples.addLast(Sample(t, dt, valid, s.speedKmh))
        if (valid) {
            validMsInWindow += dt
            lastValidDtMs = dt
            detectReversal(angle!!, t)
            detectLapse(angle, t)
        } else {
            lastValidDtMs = 0L
            resetDetectors()
        }
        prune(t)
    }

    fun features(): DrivingFeatures {
        val coverage = (validMsInWindow.toFloat() / cfg.sampleWindowMs).coerceIn(0f, 1f)
        val rate = if (validMsInWindow >= cfg.minValidMsForRate) {
            reversals.size / (validMsInWindow / 60_000f)
        } else null
        return DrivingFeatures(
            reversalRatePerMin = rate,
            lapseEventsRecent = lapses.size,
            speedStdKmh = speedStd(),
            validCoverage = coverage,
            steeringAvailable = steeringSeen,
            meanSpeedKmh = meanSpeed(),
        )
    }

    /** 충분히 쉰 뒤 최근 급꺾음 기록을 지웁니다. */
    fun clearRecentLapses() = lapses.clear()

    /** 최근 1분 유효 구간 평균 속도 (속도대별 습관 구분용). 표본이 적으면 null */
    private fun meanSpeed(): Float? {
        var n = 0
        var sum = 0.0
        for (s in samples) {
            if (!s.valid) continue
            n++
            sum += s.speed
        }
        return if (n < 20) null else (sum / n).toFloat()
    }

    private fun speedStd(): Float? {
        var n = 0
        var sum = 0.0
        var sumSq = 0.0
        for (s in samples) {
            if (!s.valid) continue
            n++
            sum += s.speed
            sumSq += s.speed.toDouble() * s.speed
        }
        if (n < 20) return null
        val mean = sum / n
        val variance = (sumSq / n - mean * mean).coerceAtLeast(0.0)
        return sqrt(variance).toFloat()
    }

    private fun detectReversal(a: Float, t: Long) {
        if (!hasExtremum) {
            hasExtremum = true
            extremum = a
            direction = 0
            return
        }
        val gap = cfg.reversalGapDeg
        when (direction) {
            0 -> if (a - extremum >= gap) {
                direction = 1; extremum = a
            } else if (extremum - a >= gap) {
                direction = -1; extremum = a
            }
            1 -> if (a > extremum) {
                extremum = a
            } else if (extremum - a >= gap) {
                reversals.addLast(t); direction = -1; extremum = a
            }
            else -> if (a < extremum) {
                extremum = a
            } else if (a - extremum >= gap) {
                reversals.addLast(t); direction = 1; extremum = a
            }
        }
    }

    private fun detectLapse(a: Float, t: Long) {
        // 1) 무조작이 끝난 직후라면, 급꺾음이 나오는지 지켜봅니다.
        if (watchUntil >= 0 && lastAngleT >= 0) {
            if (stepRate(a, t) >= cfg.correctionRateDegPerSec) {
                recordLapse(t)
                watchUntil = -1
            } else if (t > watchUntil) {
                watchUntil = -1
            }
        }
        // 2) 무조작 구간 추적
        if (holdStart < 0) {
            holdAnchor = a
            holdStart = t
        } else if (abs(a - holdAnchor) > cfg.stillToleranceDeg) {
            val held = lastAngleT - holdStart
            if (held >= cfg.lapseMinHoldMs) {
                if (stepRate(a, t) >= cfg.correctionRateDegPerSec) {
                    recordLapse(t)
                } else {
                    watchUntil = t + cfg.correctionWatchMs
                }
            }
            holdAnchor = a
            holdStart = t
        }
        lastAngle = a
        lastAngleT = t
    }

    private fun stepRate(a: Float, t: Long): Float {
        if (lastAngleT < 0) return 0f
        val dtS = (t - lastAngleT) / 1000f
        return if (dtS > 0f) abs(a - lastAngle) / dtS else 0f
    }

    private fun recordLapse(t: Long) {
        lapses.addLast(t)
        totalLapses++
    }

    private fun resetDetectors() {
        hasExtremum = false
        direction = 0
        holdStart = -1L
        lastAngleT = -1L
        watchUntil = -1L
    }

    private fun prune(now: Long) {
        while (samples.isNotEmpty() && now - samples.first().t > cfg.sampleWindowMs) {
            val old = samples.removeFirst()
            if (old.valid) validMsInWindow -= old.dtMs
        }
        if (validMsInWindow < 0) validMsInWindow = 0
        while (reversals.isNotEmpty() && now - reversals.first() > cfg.sampleWindowMs) reversals.removeFirst()
        while (lapses.isNotEmpty() && now - lapses.first() > cfg.lapseWindowMs) lapses.removeFirst()
    }
}
