package app.daltokki.drive.domain

import app.daltokki.drive.domain.fatigue.FatigueConfig
import app.daltokki.drive.domain.fatigue.FatigueEngine
import app.daltokki.drive.domain.fatigue.FatigueEstimator
import app.daltokki.drive.domain.game.AwakeScore
import app.daltokki.drive.domain.game.Badges
import app.daltokki.drive.domain.model.FatigueLevel
import app.daltokki.drive.domain.sim.ScenarioGenerator
import app.daltokki.drive.domain.sim.Scenarios
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/** 게임 규칙(각성 보너스·안전 한도)과 점수·배지 검증 */
class BonusAndScoreTest {

    private val cfg = FatigueConfig()

    /** 센서 위험도가 정확히 [sensor]가 되도록 입력 (상황 0, 핸들만) */
    private fun estimate(
        sensor: Float,
        bonus: Float,
        strong: Boolean = false,
        driveMin: Float = 30f,
        selfReport: Boolean = false,
    ): FatigueEstimator.Result =
        FatigueEstimator(cfg).update(
            t = 0L, context = 0f, steering = sensor / 0.75f, lane = null, dialog = null,
            continuousDriveMin = driveMin, selfReportActive = selfReport, bonus = bonus, strongSignal = strong,
        )

    @Test
    fun bonus_lowersRisk() {
        val r = estimate(sensor = 0.45f, bonus = 0.12f)
        assertEquals(0.45f, r.sensor, 0.001f)
        assertEquals(0.33f, r.score, 0.001f)
        assertEquals(0.12f, r.bonusApplied, 0.001f)
        assertFalse(r.bonusBlocked)
        assertEquals(FatigueLevel.CAUTION, r.level)
    }

    @Test
    fun bonus_isCappedAtMax() {
        val r = estimate(sensor = 0.40f, bonus = 0.50f)
        assertEquals(0.25f, r.score, 0.001f) // 최대 0.15만 적용
    }

    @Test
    fun bonus_lockedInDanger() {
        val r = estimate(sensor = 0.80f, bonus = 0.15f)
        assertEquals("위험 단계에서는 게임으로 못 낮춤", 0.80f, r.score, 0.001f)
        assertTrue(r.bonusBlocked)
        assertEquals(FatigueLevel.DANGER, r.level)
    }

    @Test
    fun bonus_cannotGoBelowWarningWithStrongSignal() {
        val r = estimate(sensor = 0.60f, bonus = 0.15f, strong = true)
        assertEquals("급꺾음 반복이면 '경고' 아래로 못 내림", 0.50f, r.score, 0.001f)
        assertTrue(r.bonusBlocked)
        val r2 = estimate(sensor = 0.60f, bonus = 0.15f, driveMin = 130f)
        assertEquals("2시간 연속 운전도 같음", 0.50f, r2.score, 0.001f)
    }

    @Test
    fun bonus_cannotBreakSleepyFloor() {
        val r = estimate(sensor = 0.30f, bonus = 0.15f, selfReport = true)
        assertEquals("'졸려'라고 했으면 최소 경고 유지", cfg.selfReportFloorScore, r.score, 0.001f)
    }

    @Test
    fun engineBonus_decaysInTenMinutes_andResetsAfterRest() {
        val start = LocalDate.of(2026, 11, 3).atTime(10, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        val gen = ScenarioGenerator(Scenarios.dayNormal, start)
        val engine = FatigueEngine(zone = ZoneOffset.UTC)
        repeat(10 * 60 * 2) { engine.onSnapshot(gen.next()) } // 2분 주행
        engine.addBonus(0.15f)
        var a = engine.onSnapshot(gen.next())
        assertEquals(0.15f, a.bonus, 0.002f)
        repeat(10 * 60 * 5) { a = engine.onSnapshot(gen.next()) } // 5분 뒤 절반
        assertEquals(0.075f, a.bonus, 0.005f)
        repeat(10 * 60 * 6) { a = engine.onSnapshot(gen.next()) } // 10분 넘으면 0
        assertEquals(0f, a.bonus, 0.0001f)
    }

    @Test
    fun score_pointsComboBadges() {
        val s = AwakeScore()
        s.startTrip()
        val first = s.onCorrect(fast = true, isWordGame = true)
        assertEquals("첫 정답 ×1 + 번개", AwakeScore.CORRECT_BASE + AwakeScore.FAST_BONUS, first.points)
        assertTrue(first.newBadges.contains(Badges.FIRST_WORD))
        repeat(4) { s.onCorrect(fast = false, isWordGame = true) }
        assertEquals(5, s.combo)
        assertTrue(s.isEarned(Badges.COMBO5))
        s.onWrong()
        assertEquals(0, s.combo)
        assertEquals(5, s.bestCombo)

        val rest = s.onRestCompleted()
        assertEquals(500, rest.points)
        assertTrue(rest.newBadges.contains(Badges.REST_KEEPER))

        val next = s.nextPointBadge()!!
        assertEquals(Badges.DAWN_GUARD, next.first)
        assertEquals(1_500 - s.lifetimePoints, next.second)
        assertTrue(s.nextBadgeProgress() in 0f..1f)
    }

    @Test
    fun score_pointBadgesAndPersistence() {
        val s = AwakeScore(lifetimePoints = 1_400, earnedBadgeIds = setOf("first_word"))
        val award = s.onRestCompleted() // +500 → 1,900점
        assertTrue(award.newBadges.contains(Badges.DAWN_GUARD))
        assertEquals(Badges.SAFE_DRIVER, s.nextPointBadge()!!.first)
        assertTrue(s.earnedIds.containsAll(setOf("first_word", "rest_keeper", "dawn_guard")))
    }
}
