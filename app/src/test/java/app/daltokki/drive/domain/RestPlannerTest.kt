package app.daltokki.drive.domain

import app.daltokki.drive.domain.fatigue.FatigueEngine
import app.daltokki.drive.domain.fatigue.RestPlanner
import app.daltokki.drive.domain.model.FatigueAssessment
import app.daltokki.drive.domain.model.FatigueLevel
import app.daltokki.drive.domain.sim.ScenarioGenerator
import app.daltokki.drive.domain.sim.Scenarios
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/** 8차: 출발 전 컨디션(수면) → 권장 휴식 간격 (졸음 예보) */
class RestPlannerTest {

    @Test
    fun intervalTable() {
        assertEquals("낮·수면 모름 → 2시간", 120, RestPlanner.intervalMin(13, null))
        assertEquals("푹 잠", 120, RestPlanner.intervalMin(13, 7.5f))
        assertEquals("새벽은 90분", 90, RestPlanner.intervalMin(3, null))
        assertEquals(90, RestPlanner.intervalMin(13, 5.5f))
        assertEquals(75, RestPlanner.intervalMin(13, 4.5f))
        assertEquals(60, RestPlanner.intervalMin(13, 3f))
        assertEquals("새벽 + 수면 부족 → 더 짧은 쪽", 60, RestPlanner.intervalMin(3, 3f))
        assertEquals(0f, RestPlanner.sleepDebtRisk(null), 0f)
        assertTrue(RestPlanner.sleepDebtRisk(3f) > RestPlanner.sleepDebtRisk(5.5f))
        assertNull(RestPlanner.sleepReason(7f))
        assertEquals("수면 5시간 반", RestPlanner.sleepReason(5.5f))
    }

    @Test
    fun parseSleepAnswers() {
        assertEquals(5f, RestPlanner.parseSleepHours("5시간")!!, 0.01f)
        assertEquals(6.5f, RestPlanner.parseSleepHours("6시간 반 정도")!!, 0.01f)
        assertEquals(6.5f, RestPlanner.parseSleepHours("6.5")!!, 0.01f)
        assertEquals(5.5f, RestPlanner.parseSleepHours("다섯 시간 반")!!, 0.01f)
        assertEquals("'한 네 시간' → 네", 4f, RestPlanner.parseSleepHours("한 네 시간쯤")!!, 0.01f)
        assertEquals(12f, RestPlanner.parseSleepHours("열두 시간 잤지")!!, 0.01f)
        assertEquals("숫자가 있으면 숫자 우선", 4f, RestPlanner.parseSleepHours("4시간밖에 못 잤어")!!, 0.01f)
        assertEquals(3f, RestPlanner.parseSleepHours("거의 못 잤어")!!, 0.01f)
        assertEquals(0f, RestPlanner.parseSleepHours("밤샜어")!!, 0.01f)
        assertNull("'네'는 대답일 수 있어서 시간으로 보지 않음", RestPlanner.parseSleepHours("네"))
        assertNull(RestPlanner.parseSleepHours("부산 가는 길이야"))
    }

    private fun runLongTrip(sleep: Float?): List<Pair<String, FatigueAssessment>> {
        val sc = Scenarios.longTrip
        val start = LocalDate.of(2026, 11, 3).atTime(sc.startHour, sc.startMinute).toInstant(ZoneOffset.UTC).toEpochMilli()
        val gen = ScenarioGenerator(sc, start)
        val engine = FatigueEngine(zone = ZoneOffset.UTC).also { it.setSleepHours(sleep) }
        val out = ArrayList<Pair<String, FatigueAssessment>>()
        while (!gen.isFinished) {
            val label = gen.currentPhase().label
            out += label to engine.onSnapshot(gen.next())
        }
        return out
    }

    @Test
    fun shortSleep_bringsRestReminderForward() {
        // 오후 1시 출발 장거리: 수면을 모르면 첫 1시간은 '좋아요'
        val normal = runLongTrip(null)
        assertEquals(FatigueLevel.NORMAL, normal.filter { it.first == "고속도로 1" }.maxOf { it.second.level })

        // 4시간만 잤으면 권장 간격 75분 → 75분이 지나면 '주의'와 근거
        val short = runLongTrip(4f)
        val after = short.map { it.second }.filter { it.continuousDriveMin >= 76f && it.continuousDriveMin < 90f }
        assertTrue(after.isNotEmpty())
        assertTrue("권장 간격이 지나면 최소 '주의'", after.all { it.level >= FatigueLevel.CAUTION })
        assertTrue(after.last().reasons.any { it.contains("권장 휴식 간격 75분") })
        assertEquals(75, after.last().restIntervalMin)
        assertTrue("남은 시간은 음수", after.last().restDueInMin!! < 0f)
        val early = short.map { it.second }.first { it.continuousDriveMin in 30f..31f }
        assertEquals("30분 시점: 45분 남음", 45f, early.restDueInMin!!, 1.5f)
    }
}
