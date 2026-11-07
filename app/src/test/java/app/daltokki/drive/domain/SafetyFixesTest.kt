package app.daltokki.drive.domain

import app.daltokki.drive.data.nav.RestAreaDatabase
import app.daltokki.drive.domain.dialog.DialogPolicy
import app.daltokki.drive.domain.dialog.Intervention
import app.daltokki.drive.domain.dialog.TalkLevel
import app.daltokki.drive.domain.dialog.UtteranceIntent
import app.daltokki.drive.domain.fatigue.FatigueConfig
import app.daltokki.drive.domain.fatigue.FatigueEngine
import app.daltokki.drive.domain.fatigue.FatigueEstimator
import app.daltokki.drive.domain.fatigue.ResponseKind
import app.daltokki.drive.domain.fatigue.ResponseTracker
import app.daltokki.drive.domain.fatigue.SteeringFeatureExtractor
import app.daltokki.drive.domain.game.WordChainEngine
import app.daltokki.drive.domain.game.WordDictionary
import app.daltokki.drive.domain.model.FatigueLevel
import app.daltokki.drive.domain.model.Gear
import app.daltokki.drive.domain.model.VehicleSnapshot
import app.daltokki.drive.domain.sim.ScenarioGenerator
import app.daltokki.drive.domain.sim.Scenarios
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.random.Random

/**
 * 11-04 중간 점검에서 나온 문제의 수정 검증
 * ① 핸들 신호 없을 때 무응답 한 번으로 '위험'이 되던 문제
 * ② 게임 대답의 생각 시간을 졸음으로 보던 문제
 * ③ 개인 기준 응답 시간이 '수다'에서만 잡히던 문제
 * ④ 음성 끝말잇기 군말 처리
 * ⑤ 의도 오인식
 * 그리고 차량 신호(방향지시등, 운전자 주의 경고, 내기순환), 휴게소 찾기, 화면 위험도와 말 걸기 판단 분리
 */
class SafetyFixesTest {

    private val cfg = FatigueConfig()

    // ── ① ──
    @Test
    fun noSteering_singleMiss_doesNotJumpToDanger() {
        val est = FatigueEstimator(cfg)
        // 새벽(상황 0.45) + 핸들 데이터 없음 + 방금 무응답 1번(반응 저하 1.0, 신뢰도 1.0)
        val r = est.update(
            t = 0L, context = 0.45f, steering = null, lane = null, dialog = 1f to 1f,
            continuousDriveMin = 30f, selfReportActive = false,
        )
        assertTrue("무응답 한 번으로 경고·위험까지 가지 않음 (sensor=${r.sensor})", r.level < FatigueLevel.WARNING)
        assertTrue("그래도 신호는 반영됨", r.sensor > 0.25f)
    }

    @Test
    fun noSteering_sparseSignalsAreCapped() {
        val est = FatigueEstimator(cfg)
        val behavior = est.behavior(steering = null, lane = 1f, dialog = 1f to 1f)
        assertEquals(cfg.sparseBehaviorMax, behavior, 0.0001f)
        // 핸들 신호가 있으면 예전처럼 가중치를 나눠 받음
        val withSteering = est.behavior(steering = 1f, lane = null, dialog = null)
        assertEquals(0.75f, withSteering, 0.0001f)
    }

    // ── ② ──
    @Test
    fun gameThinkingTime_isNotTreatedAsDrowsiness() {
        val tracker = ResponseTracker(cfg)
        tracker.recordResponse(0L, 4_000L, ResponseKind.GAME)
        assertEquals("게임에서 4초는 정상", 0f, tracker.impairment(0L)!!.first, 0.0001f)

        val simple = ResponseTracker(cfg)
        simple.recordResponse(0L, 4_000L, ResponseKind.SIMPLE)
        assertTrue("짧은 질문에 4초는 느림", simple.impairment(0L)!!.first > 0.5f)

        val timeout = ResponseTracker(cfg)
        timeout.recordMiss(0L, ResponseKind.GAME)
        assertEquals("게임 시간 초과는 무응답보다 약하게", cfg.gameMissImpairment, timeout.impairment(0L)!!.first, 0.0001f)

        assertTrue(tracker.isFast(3_500L, ResponseKind.GAME))
        assertFalse(tracker.isFast(3_500L, ResponseKind.SIMPLE))
    }

    // ── ③ ──
    @Test
    fun baseline_learnedFromSimpleAnswersWhileAlert() {
        val start = LocalDate.of(2026, 11, 3).atTime(10, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        val gen = ScenarioGenerator(Scenarios.dayNormal, start)
        val engine = FatigueEngine(zone = ZoneOffset.UTC)
        repeat(10 * 60) { engine.onSnapshot(gen.next()) } // 1분 주행 ('좋아요')
        assertEquals(cfg.defaultBaselineLatencyMs, engine.baselineLatencyMs)
        engine.recordResponse(1_000L, ResponseKind.SIMPLE)
        assertEquals("'좋아요' 단계의 짧은 대답으로 개인 기준이 잡힘", 1_000L, engine.baselineLatencyMs)
        engine.recordResponse(5_000L, ResponseKind.GAME)
        assertEquals("게임 대답은 기준에 쓰지 않음", 1_000L, engine.baselineLatencyMs)
    }

    // ── 화면 위험도 vs 말 걸기 판단 ──
    @Test
    fun bonus_doesNotLowerDisplayedLevel_onlyPromptLevel() {
        val r = FatigueEstimator(cfg).update(
            t = 0L, context = 0f, steering = 0.55f / 0.75f, lane = null, dialog = null,
            continuousDriveMin = 30f, selfReportActive = false, bonus = 0.15f,
        )
        assertEquals("화면 단계는 실제 졸음 위험도 기준", FatigueLevel.WARNING, r.level)
        assertEquals("말 걸기 판단은 깨어 있음 확인 반영", FatigueLevel.CAUTION, r.promptLevel)
        assertEquals(0.55f, r.sensor, 0.001f)
    }

    // ── 차량 신호 ──
    @Test
    fun vehicleAttentionWarning_forcesDanger() {
        val r = FatigueEstimator(cfg).update(
            t = 0L, context = 0f, steering = 0f, lane = null, dialog = null,
            continuousDriveMin = 10f, selfReportActive = false, bonus = 0.15f, vehicleWarning = true,
        )
        assertEquals(FatigueLevel.DANGER, r.level)
        assertEquals(FatigueLevel.DANGER, r.promptLevel)
    }

    private fun snap(t: Long, angle: Float, turn: Boolean) = VehicleSnapshot(
        timestampMs = t, speedKmh = 100f, gear = Gear.DRIVE, steeringAngleDeg = angle, turnSignalOn = turn,
    )

    /** 4초 무조작 뒤 0.1초에 3도 꺾기(30도/초) */
    private fun lapsePattern(turnAtCorrection: Boolean): Int {
        val ex = SteeringFeatureExtractor(cfg)
        var t = 0L
        repeat(40) { ex.add(snap(t, 0f, false)); t += 100 }
        ex.add(snap(t, 3f, turnAtCorrection)); t += 100
        repeat(10) { ex.add(snap(t, 3f, turnAtCorrection)); t += 100 }
        return ex.features().lapseEventsRecent
    }

    @Test
    fun turnSignal_excludesLaneChangeFromLapse() {
        assertEquals("방향지시등 없이 핸들 무조작 후 급꺾음 = 졸음 신호", 1, lapsePattern(turnAtCorrection = false))
        assertEquals("방향지시등 켜고 꺾으면 차선 변경으로 보고 제외", 0, lapsePattern(turnAtCorrection = true))
    }

    @Test
    fun scenario_vehicleSignalsArePlayed() {
        val scenario = Scenarios.nightHighway
        val start = LocalDate.of(2026, 11, 3).atTime(scenario.startHour, scenario.startMinute).toInstant(ZoneOffset.UTC).toEpochMilli()
        val gen = ScenarioGenerator(scenario, start)
        val engine = FatigueEngine(zone = ZoneOffset.UTC)
        var sawDaw = false
        var maxRecirc = 0f
        var dangerDuringDaw = false
        while (!gen.isFinished) {
            val a = engine.onSnapshot(gen.next())
            if (a.events.any { it is app.daltokki.drive.domain.model.DriveEvent.VehicleAttentionWarning }) sawDaw = true
            if (a.vehicleWarningActive && a.level == FatigueLevel.DANGER) dangerDuringDaw = true
            a.recirculationMin?.let { maxRecirc = maxOf(maxRecirc, it) }
        }
        assertTrue("졸음 운전 구간에서 차량 주의 경고 재생", sawDaw)
        assertTrue("차량 주의 경고 중에는 위험 단계", dangerDuringDaw)
        assertTrue("내기순환 20분 이상 (환기 제안 조건)", maxRecirc >= cfg.recirculationSuggestMin)
    }

    // ── 환기 제안 규칙 ──
    @Test
    fun freshAirPolicy() {
        val min = 60_000L
        val p = DialogPolicy()
        assertFalse("최소: 말하지 않음(화면만)", p.decideFreshAir(1 * min, FatigueLevel.CAUTION, TalkLevel.MIN, 25f, null))
        assertFalse("20분 미만이면 안 함", p.decideFreshAir(1 * min, FatigueLevel.CAUTION, TalkLevel.NORMAL, 10f, null))
        assertFalse("'좋아요' 단계면 안 함", p.decideFreshAir(1 * min, FatigueLevel.NORMAL, TalkLevel.NORMAL, 25f, null))
        assertTrue(p.decideFreshAir(1 * min, FatigueLevel.CAUTION, TalkLevel.NORMAL, 25f, null))
        p.markDone(Intervention.FRESH_AIR, 1 * min)
        assertFalse("30분 안에는 다시 안 함", p.decideFreshAir(20 * min, FatigueLevel.CAUTION, TalkLevel.NORMAL, 45f, null))
        assertTrue(p.decideFreshAir(32 * min, FatigueLevel.WARNING, TalkLevel.NORMAL, 55f, null))
    }

    // ── ④ ──
    @Test
    fun wordChain_skipsFillerWords_andRejectsRepeatedSyllables() {
        val dict = WordDictionary(listOf("기차", "차표", "표정", "정원", "원두", "두부", "부자", "자기", "기러기", "기타", "타조", "조기"))
        val engine = WordChainEngine(dict, strict = false, random = Random(3))
        engine.start()
        val hint = engine.hints(1).first()
        val r = engine.submit("음 $hint")
        assertTrue("'음 $hint' → '$hint'로 인정", r is WordChainEngine.Result.Accepted && r.userWord == hint)
        assertTrue((r as WordChainEngine.Result.Accepted).inDictionary)

        val c = engine.expectedStarts().first()
        val repeated = engine.submit("$c$c")
        assertTrue("같은 글자 반복은 단어로 안 봄", repeated is WordChainEngine.Result.Invalid)
    }

    // ── ⑤ ──
    @Test
    fun intents_fixedMisreadings() {
        assertEquals(UtteranceIntent.NO, UtteranceIntent.parse("어 아니"))
        assertEquals(UtteranceIntent.OTHER, UtteranceIntent.parse("어디야"))
        assertEquals(UtteranceIntent.YES, UtteranceIntent.parse("어"))
        assertEquals(UtteranceIntent.YES, UtteranceIntent.parse("네"))
        assertEquals(UtteranceIntent.YES, UtteranceIntent.parse("응 들려"))
        assertEquals(UtteranceIntent.NO, UtteranceIntent.parse("괜찮아요"))
        assertTrue(UtteranceIntent.parse("조용한 노래 틀어줘") != UtteranceIntent.QUIET)
        assertEquals(UtteranceIntent.QUIET, UtteranceIntent.parse("조용히 해"))
    }

    // ── 휴게소 찾기 ──
    @Test
    fun restAreas_prefersOnesAhead() {
        val db = RestAreaDatabase.parse(
            """
            # 시험용 좌표
            이름,종류,위도,경도
            북쪽휴게소,휴게소,36.60,127.00
            남쪽쉼터,졸음쉼터,36.45,127.00
            잘못된줄,휴게소,abc,127.0
            """.trimIndent(),
        )
        assertEquals(2, db.size)
        // 위도 36.50에서 북쪽(0도)으로 달리는 중: 남쪽이 더 가깝지만 이미 지나쳤으므로 북쪽을 고름
        val ahead = db.nearestAhead(36.50, 127.00, headingDeg = 0f)
        assertNotNull(ahead)
        assertEquals("북쪽휴게소", ahead!!.first.name)
        // 방향을 모르면 가장 가까운 곳
        assertEquals("남쪽쉼터", db.nearestAhead(36.50, 127.00, headingDeg = null)!!.first.name)
        assertTrue(RestAreaDatabase.distanceKm(36.50, 127.00, 36.60, 127.00) in 10.0..12.0)
    }
}
