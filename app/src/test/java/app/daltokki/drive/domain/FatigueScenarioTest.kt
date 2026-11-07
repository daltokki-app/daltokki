package app.daltokki.drive.domain

import app.daltokki.drive.domain.fatigue.FatigueEngine
import app.daltokki.drive.domain.model.DriveEvent
import app.daltokki.drive.domain.model.FatigueAssessment
import app.daltokki.drive.domain.model.FatigueLevel
import app.daltokki.drive.domain.sim.DriveScenario
import app.daltokki.drive.domain.sim.ScenarioGenerator
import app.daltokki.drive.domain.sim.Scenarios
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * 시뮬레이션 시나리오 전체를 피로 추정 엔진에 흘려 보내,
 * 구간별로 기대한 단계가 나오는지 확인합니다. (대화 반응 없이 차량 데이터만으로)
 */
class FatigueScenarioTest {

    private data class PhaseResult(val label: String, val maxLevel: FatigueLevel, val endLevel: FatigueLevel, val maxScore: Float)

    private fun run(scenario: DriveScenario, seed: Long = 42L): Pair<List<PhaseResult>, List<DriveEvent>> {
        val start = LocalDate.of(2026, 11, 3).atTime(scenario.startHour, scenario.startMinute).toInstant(ZoneOffset.UTC).toEpochMilli()
        val gen = ScenarioGenerator(scenario, start, seed = seed)
        val engine = FatigueEngine(zone = ZoneOffset.UTC)
        val byPhase = LinkedHashMap<String, MutableList<FatigueAssessment>>()
        val events = mutableListOf<DriveEvent>()
        while (!gen.isFinished) {
            val label = gen.currentPhase().label
            val snap = gen.next()
            val a = engine.onSnapshot(snap)
            events += a.events
            byPhase.getOrPut(label) { mutableListOf() } += a
        }
        val results = byPhase.map { (label, list) ->
            PhaseResult(label, list.maxOf { it.level }, list.last().level, list.maxOf { it.score })
        }
        results.forEach { println("[${scenario.id}] ${it.label}: max=${it.maxLevel} end=${it.endLevel} maxScore=${"%.2f".format(it.maxScore)}") }
        return results to events
    }

    @Test
    fun nightHighway_escalatesWithDrowsiness_andResetsAfterRest() {
        val (r, events) = run(Scenarios.nightHighway)
        val byLabel = r.associateBy { it.label }

        assertTrue("또렷한 구간은 주의 미만", byLabel.getValue("고속도로 · 또렷함").maxLevel <= FatigueLevel.CAUTION)
        assertTrue("피로 누적 구간에서 주의 이상", byLabel.getValue("피로 누적").maxLevel >= FatigueLevel.CAUTION)
        assertTrue("졸음 운전 구간에서 경고 이상", byLabel.getValue("졸음 운전").maxLevel >= FatigueLevel.WARNING)
        assertTrue("휴식 후 주행은 정상으로 돌아옴", byLabel.getValue("휴식 후 주행").endLevel == FatigueLevel.NORMAL)

        assertTrue(events.contains(DriveEvent.TripStarted))
        assertTrue(events.contains(DriveEvent.CalibrationDone))
        assertTrue(events.contains(DriveEvent.RestStarted))
        assertTrue(events.contains(DriveEvent.RestCompleted))
        assertTrue(events.contains(DriveEvent.DrivingResumed(restedEnough = true)))
    }

    @Test
    fun dayNormal_noFalseAlarm() {
        val (r, _) = run(Scenarios.dayNormal)
        r.forEach { assertEquals("${it.label}: 정상 주행에서는 경고가 없어야 함", FatigueLevel.NORMAL, it.maxLevel) }
    }

    @Test
    fun longTrip_suggestsRestAfterTwoHours() {
        val (r, _) = run(Scenarios.longTrip)
        val byLabel = r.associateBy { it.label }
        assertEquals(FatigueLevel.NORMAL, byLabel.getValue("고속도로 1").maxLevel)
        assertTrue("2시간 넘으면 최소 주의", byLabel.getValue("고속도로 3 · 지루함").maxLevel >= FatigueLevel.CAUTION)
    }

    @Test
    fun nightHighway_robustAcrossSeeds() {
        for (seed in listOf(1L, 7L, 123L, 2026L)) {
            val (r, _) = run(Scenarios.nightHighway, seed)
            val byLabel = r.associateBy { it.label }
            assertTrue("seed=$seed 졸음 구간 경고 이상", byLabel.getValue("졸음 운전").maxLevel >= FatigueLevel.WARNING)
            assertTrue("seed=$seed 또렷 구간 주의 이하", byLabel.getValue("고속도로 · 또렷함").maxLevel <= FatigueLevel.CAUTION)
        }
        for (seed in listOf(1L, 7L, 123L, 2026L)) {
            val (r, _) = run(Scenarios.dayNormal, seed)
            r.forEach { assertEquals("seed=$seed ${it.label}", FatigueLevel.NORMAL, it.maxLevel) }
        }
    }

    @Test
    fun dialogSignals_raiseScore() {
        val scenario = Scenarios.dayNormal
        val start = LocalDate.of(2026, 11, 3).atTime(10, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        val gen = ScenarioGenerator(scenario, start)
        val engine = FatigueEngine(zone = ZoneOffset.UTC)
        var last: FatigueAssessment? = null
        var n = 0
        while (!gen.isFinished && n < 10 * 60 * 20) { // 20분
            last = engine.onSnapshot(gen.next()); n++
            if (n == 10 * 60 * 10) { // 10분 시점: 연속 무응답 3회
                engine.recordMiss(); engine.recordMiss(); engine.recordMiss()
            }
        }
        assertTrue("무응답이 쌓이면 대화 반응 저하가 잡힘", (last!!.dialogImpairment ?: 0f) > 0.5f)
        assertTrue("대화 신호로 점수가 오름", last.score > 0.15f)
    }

    @Test
    fun selfReport_setsWarningFloor() {
        val scenario = Scenarios.dayNormal
        val start = LocalDate.of(2026, 11, 3).atTime(10, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        val gen = ScenarioGenerator(scenario, start)
        val engine = FatigueEngine(zone = ZoneOffset.UTC)
        repeat(10 * 60 * 3) { engine.onSnapshot(gen.next()) }
        engine.reportSleepy()
        val a = engine.onSnapshot(gen.next())
        assertTrue(a.level >= FatigueLevel.WARNING)
    }
}
