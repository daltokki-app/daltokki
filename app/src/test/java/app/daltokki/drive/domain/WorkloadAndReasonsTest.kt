package app.daltokki.drive.domain

import app.daltokki.drive.domain.fatigue.FatigueConfig
import app.daltokki.drive.domain.fatigue.FatigueEngine
import app.daltokki.drive.domain.fatigue.ResponseKind
import app.daltokki.drive.domain.fatigue.WorkloadGate
import app.daltokki.drive.domain.model.BusyReason
import app.daltokki.drive.domain.model.Gear
import app.daltokki.drive.domain.model.VehicleSnapshot
import app.daltokki.drive.domain.sim.ScenarioGenerator
import app.daltokki.drive.domain.sim.Scenarios
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.ZoneOffset

/** 바쁜 순간 말 걸기 보류, 판단 근거, Gleo 함수 정의 */
class WorkloadAndReasonsTest {

    private val cfg = FatigueConfig()

    private fun snap(
        t: Long,
        speed: Float = 100f,
        angle: Float = 0f,
        turn: Boolean = false,
        brake: Boolean = false,
    ) = VehicleSnapshot(
        timestampMs = t, speedKmh = speed, gear = Gear.DRIVE, steeringAngleDeg = angle,
        turnSignalOn = turn, brakePressed = brake,
    )

    @Test
    fun laneChange_isBusy_andHoldsThreeSeconds() {
        val gate = WorkloadGate(cfg)
        assertNull(gate.update(snap(0)))
        assertEquals(BusyReason.LANE_CHANGE, gate.update(snap(100, turn = true)))
        assertEquals("끝나고 3초까지는 기다림", BusyReason.LANE_CHANGE, gate.update(snap(2_000)))
        assertNull(gate.update(snap(3_200)))
    }

    @Test
    fun braking_and_hardDeceleration_areBusy() {
        val brake = WorkloadGate(cfg)
        assertEquals(BusyReason.BRAKING, brake.update(snap(0, brake = true)))
        val city = WorkloadGate(cfg)
        assertNull("저속 정체 구간의 브레이크는 제외", city.update(snap(0, speed = 15f, brake = true)))
        val decel = WorkloadGate(cfg)
        decel.update(snap(0, speed = 100f))
        assertEquals("0.1초에 1.5km/h 감속(초당 15km/h) = 급제동", BusyReason.BRAKING, decel.update(snap(100, speed = 98.5f)))
    }

    @Test
    fun bigSteering_isBusy_butDrowsyCorrectionIsNot() {
        val turn = WorkloadGate(cfg)
        assertEquals(BusyReason.TURNING, turn.update(snap(0, speed = 30f, angle = 120f)))
        // 졸음 급꺾음(0.4초에 8도 = 초당 20도)은 바쁜 순간이 아님 → 달토끼가 바로 확인할 수 있어야 함
        val lapse = WorkloadGate(cfg)
        lapse.update(snap(0, angle = 0f))
        assertNull(lapse.update(snap(100, angle = 2f)))
    }

    @Test
    fun reasons_explainTheJudgment() {
        val start = LocalDate.of(2026, 11, 3).atTime(10, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        val gen = ScenarioGenerator(Scenarios.dayNormal, start)
        val engine = FatigueEngine(zone = ZoneOffset.UTC)
        repeat(10 * 60 * 2) { engine.onSnapshot(gen.next()) }
        engine.recordResponse(1_000L, ResponseKind.SIMPLE) // 또렷할 때 1초 → 개인 기준
        engine.recordMiss(); engine.recordMiss()
        engine.recordResponse(2_600L, ResponseKind.SIMPLE)
        val a = engine.onSnapshot(gen.next())
        assertTrue(a.reasons.toString(), a.reasons.contains("무응답 2회"))
        assertTrue(a.reasons.toString(), a.reasons.contains("대답이 평소보다 2.6배 느림"))
        assertTrue(a.reasons.size <= 3)
    }

    @Test
    fun nightScenario_reasonsMentionSteeringLapses() {
        val scenario = Scenarios.nightHighway
        val start = LocalDate.of(2026, 11, 3).atTime(scenario.startHour, scenario.startMinute).toInstant(ZoneOffset.UTC).toEpochMilli()
        val gen = ScenarioGenerator(scenario, start)
        val engine = FatigueEngine(zone = ZoneOffset.UTC)
        var sawLapseReason = false
        while (!gen.isFinished) {
            val label = gen.currentPhase().label
            val a = engine.onSnapshot(gen.next())
            if (label == "졸음 운전" && a.reasons.any { it.startsWith("핸들 무조작 후 급꺾음") }) sawLapseReason = true
        }
        assertTrue("졸음 운전 구간의 근거에 핸들 급꺾음이 나옴", sawLapseReason)
    }

    @Test
    fun gleoSchema_matchesManifest() {
        fun read(path: String) = listOf(File("src/main/$path"), File("app/src/main/$path")).first { it.exists() }.readText()
        val schema = read("assets/schema.json")
        val manifest = read("AndroidManifest.xml")
        val names = Regex("\"name\"\\s*:\\s*\"([A-Z_]+)\"").findAll(schema).map { it.groupValues[1] }.toList()
        assertEquals(listOf("START_WORD_GAME", "REPORT_DROWSINESS", "ADD_REST_STOP"), names)
        assertTrue("Gleo 규칙: 함수는 최대 3개", names.size <= 3)
        assertTrue(schema.contains("\"packageName\": \"app.daltokki.drive\""))
        names.forEach { assertTrue("매니페스트에 $it 액션 등록", manifest.contains("<action android:name=\"$it\" />")) }
    }
}
