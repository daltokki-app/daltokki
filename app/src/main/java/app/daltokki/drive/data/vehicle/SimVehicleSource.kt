package app.daltokki.drive.data.vehicle

import android.os.SystemClock
import app.daltokki.drive.domain.model.VehicleSnapshot
import app.daltokki.drive.domain.sim.DriveScenario
import app.daltokki.drive.domain.sim.ScenarioGenerator
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import java.time.LocalDate
import java.time.ZoneId

/**
 * 시나리오를 배속으로 재생하는 시뮬레이션 데이터 공급원.
 * 달토끼가 대화 중일 때는 자동으로 1배속(실시간)으로 바뀝니다.
 */
class SimVehicleSource(
    val scenario: DriveScenario,
    zone: ZoneId = ZoneId.systemDefault(),
    sampleHz: Int = 10,
) : VehicleDataSource {

    override val name: String = "시뮬레이션"

    private val _status = MutableStateFlow("준비됨: ${scenario.title}")
    override val status: StateFlow<String> = _status

    /** 기본 배속 */
    val speed = MutableStateFlow(30f)
    val paused = MutableStateFlow(false)

    /** 대화 중이면 1배속 */
    @Volatile
    var interactive: Boolean = false

    // 데모 패널의 "차선 변경" / "급제동" 버튼: 실제 시간 기준으로 몇 초 동안 신호를 덮어씀 (그동안 1배속)
    @Volatile private var laneChangeUntil = 0L
    @Volatile private var brakeUntil = 0L

    /** 방향지시등을 켜고 차선 변경하는 장면 (5초) */
    fun injectLaneChange(durationMs: Long = 5_000L) {
        laneChangeUntil = SystemClock.elapsedRealtime() + durationMs
    }

    /** 브레이크를 밟는 장면 (3초) */
    fun injectBraking(durationMs: Long = 3_000L) {
        brakeUntil = SystemClock.elapsedRealtime() + durationMs
    }

    /** 운전자가 "응, 바꿔 줘" → 외기 전환 (이번 운행 끝까지 외기, 팬 4단) */
    @Volatile var freshAirOn: Boolean = false
        private set

    fun freshAir() {
        freshAirOn = true
    }

    private val injecting: Boolean
        get() = SystemClock.elapsedRealtime().let { it < laneChangeUntil || it < brakeUntil }

    private fun applyInjection(s: VehicleSnapshot): VehicleSnapshot {
        val now = SystemClock.elapsedRealtime()
        var out = s
        if (now < laneChangeUntil) out = out.copy(turnSignalOn = true)
        if (now < brakeUntil) out = out.copy(brakePressed = true)
        if (freshAirOn) out = out.copy(recirculationOn = false, fanSpeed = 4)
        return out
    }

    private val startEpochMs: Long = LocalDate.now(zone)
        .atTime(scenario.startHour, scenario.startMinute)
        .atZone(zone).toInstant().toEpochMilli()

    private val generator = ScenarioGenerator(scenario, startEpochMs, sampleHz)

    val phaseLabel: String get() = generator.currentPhase().label
    val progress: Float get() = generator.elapsedMs.toFloat() / generator.totalMs
    val effectiveSpeed: Float get() = if (paused.value) 0f else if (interactive || injecting) 1f else speed.value

    override fun snapshots(): Flow<VehicleSnapshot> = flow {
        _status.value = "재생 중: ${scenario.title}"
        var lastReal = SystemClock.elapsedRealtime()
        var budgetMs = 0.0
        while (!generator.isFinished) {
            val now = SystemClock.elapsedRealtime()
            budgetMs += (now - lastReal) * effectiveSpeed.toDouble()
            lastReal = now
            var emitted = 0
            while (budgetMs >= generator.stepMs && !generator.isFinished && emitted < 3_000) {
                emit(applyInjection(generator.next()))
                budgetMs -= generator.stepMs
                emitted++
            }
            delay(20)
        }
        _status.value = "재생 완료: ${scenario.title}"
    }
}
