package app.daltokki.drive.domain.sim

import app.daltokki.drive.domain.model.Gear
import app.daltokki.drive.domain.model.VehicleSnapshot
import java.util.Random
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

/**
 * 시나리오를 실제 차량 데이터처럼 초당 sampleHz번 생성합니다.
 * 같은 seed면 항상 같은 데이터가 나오므로 테스트와 시연이 재현됩니다.
 */
class ScenarioGenerator(
    val scenario: DriveScenario,
    private val startEpochMs: Long,
    sampleHz: Int = 10,
    seed: Long = 42L,
) {
    private val rnd = Random(seed)
    val stepMs: Long = 1000L / sampleHz
    val totalMs: Long = scenario.totalSec * 1000L

    var elapsedMs = 0L
        private set

    private var phaseIdx = 0
    private var phaseStartMs = 0L
    private var speed = 0f
    private var phi = 0.0

    // 핸들 무조작 → 급꺾음 상태
    private var nextLapseAt = -1L
    private var holdUntil = -1L
    private var holdAngle = 0f
    private var corrStart = -1L
    private var corrDir = 1f
    private var corrMag = 0f
    private var laneFlagUntil = -1L

    val isFinished: Boolean get() = elapsedMs >= totalMs

    fun currentPhase(): ScenarioPhase = scenario.phases[phaseIdx]

    fun next(): VehicleSnapshot {
        while (phaseIdx < scenario.phases.lastIndex &&
            elapsedMs >= phaseStartMs + scenario.phases[phaseIdx].durationSec * 1000L
        ) {
            phaseStartMs += scenario.phases[phaseIdx].durationSec * 1000L
            phaseIdx++
            nextLapseAt = -1L
            holdUntil = -1L
            corrStart = -1L
        }
        val p = scenario.phases[phaseIdx]
        val t = elapsedMs
        val dtS = stepMs / 1000f

        // 속도: 목표 속도로 초당 6km/h씩 가감속
        val target = if (p.gear == Gear.PARK) 0f else p.speedKmh
        val maxStep = 6f * dtS
        speed += (target - speed).coerceIn(-maxStep, maxStep)
        val jitter = if (speed > 1f) {
            (sin(2 * PI * 0.02 * t / 1000.0) * p.speedJitterKmh + rnd.nextGaussian() * 0.2).toFloat()
        } else 0f
        val reportedSpeed = max(0f, speed + jitter)

        // 핸들: 커브 + 미세 보정(사인파) + 잡음
        phi += 2 * PI * p.steerFreqHz * (1 + rnd.nextGaussian() * 0.05) * dtS
        var angle = (p.curveDeg + p.steerAmpDeg * sin(phi) + rnd.nextGaussian() * 0.04).toFloat()

        if (p.gear == Gear.DRIVE && p.lapseEverySec != null) {
            angle = applyLapse(p, t, angle)
        }

        // 차선 변경: 방향지시등 4.5초 + 핸들을 3도 정도 부드럽게 꺾었다 되돌림 (난수를 쓰지 않아 다른 구간 재현성 유지)
        var turnSignal = false
        val inPhase = t - phaseStartMs
        p.laneChangeEverySec?.let { every ->
            val everyMs = every * 1000L
            val offset = (inPhase - LANE_CHANGE_FIRST_MS).let { if (it < 0) -1L else it % everyMs }
            if (p.gear == Gear.DRIVE && offset in 0 until LANE_CHANGE_SIGNAL_MS) {
                turnSignal = true
                if (offset in 500L until 3_500L) {
                    angle += (3.0 * sin(PI * (offset - 500L) / 3_000.0)).toFloat()
                }
            }
        }
        val daw = p.vehicleWarningAtSec?.let { at -> inPhase in (at * 1000L) until (at * 1000L + 8_000L) } ?: false

        val snapshot = VehicleSnapshot(
            timestampMs = startEpochMs + t,
            speedKmh = reportedSpeed,
            gear = p.gear,
            steeringAngleDeg = if (p.gear == Gear.PARK) 0f else angle,
            laneAssistActive = p.laneAssist,
            laneDepartureWarning = if (scenario.hasAdas) t < laneFlagUntil else null,
            turnSignalOn = turnSignal,
            driverAttentionWarning = if (scenario.hasAdas) daw else null,
            recirculationOn = p.recirculation,
            occupants = scenario.occupants,
            fanSpeed = if (p.gear == Gear.PARK) 1 else 2,
        )
        elapsedMs += stepMs
        return snapshot
    }

    private companion object {
        const val LANE_CHANGE_FIRST_MS = 30_000L
        const val LANE_CHANGE_SIGNAL_MS = 4_500L
    }

    private fun applyLapse(p: ScenarioPhase, t: Long, normalAngle: Float): Float {
        val every = p.lapseEverySec!! * 1000L
        if (nextLapseAt < 0 && holdUntil < 0 && corrStart < 0) {
            nextLapseAt = t + (every * (0.7 + 0.6 * rnd.nextDouble())).toLong()
        }
        // 무조작 시작
        if (nextLapseAt in 0..t && holdUntil < 0 && corrStart < 0) {
            holdAngle = normalAngle
            holdUntil = t + 3_500L + rnd.nextInt(2_000)
            nextLapseAt = -1L
        }
        // 무조작 중: 핸들이 거의 움직이지 않음
        if (holdUntil >= 0) {
            if (t < holdUntil) return holdAngle + (rnd.nextGaussian() * 0.02).toFloat()
            holdUntil = -1L
            corrStart = t
            corrDir = if (rnd.nextBoolean()) 1f else -1f
            corrMag = 6f + rnd.nextFloat() * 4f
            if (p.laneWarnOnLapse) laneFlagUntil = t + 1_500L
        }
        // 급꺾음: 0.4초 동안 크게 꺾고 1.5초에 걸쳐 되돌림
        if (corrStart >= 0) {
            val el = t - corrStart
            val offset = when {
                el < 400 -> corrMag * el / 400f
                el < 1_900 -> corrMag * (1f - (el - 400) / 1_500f)
                else -> {
                    corrStart = -1L
                    return normalAngle
                }
            }
            return holdAngle + corrDir * offset
        }
        return normalAngle
    }
}
