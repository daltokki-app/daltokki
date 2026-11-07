package app.daltokki.drive.domain

import app.daltokki.drive.domain.dialog.DialogPolicy
import app.daltokki.drive.domain.dialog.Intervention
import app.daltokki.drive.domain.dialog.ReplyContext
import app.daltokki.drive.domain.dialog.TalkLevel
import app.daltokki.drive.domain.dialog.UtteranceIntent
import app.daltokki.drive.domain.driver.DemoDriver
import app.daltokki.drive.domain.driver.DriverCodec
import app.daltokki.drive.domain.driver.DriverProfile
import app.daltokki.drive.domain.driver.DriverRoster
import app.daltokki.drive.domain.driver.TripLog
import app.daltokki.drive.domain.driver.TripSummary
import app.daltokki.drive.domain.model.FatigueLevel
import app.daltokki.drive.domain.model.TripStats
import app.daltokki.drive.domain.sim.AutoDriverInput
import app.daltokki.drive.domain.sim.AutoDriverScript
import app.daltokki.drive.domain.game.SafetyAction as A
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 18차: 먼저 알아챈 시간 · 안전 운행 연속 기록 · 주간 리포트 · 온도 낮추기 */
class Features18Test {

    private val MIN = 60_000L
    private val DAY = 24 * 60 * MIN

    @Test
    fun leadTime_onlyWhenAppWarnsFirst() {
        assertEquals(5f, TripStats(firstWarningMs = 10 * MIN, firstVehicleWarningMs = 15 * MIN).leadMin!!, 0.01f)
        assertNull("차량이 먼저면 없음", TripStats(firstWarningMs = 15 * MIN, firstVehicleWarningMs = 10 * MIN).leadMin)
        assertNull("차량 경고가 없으면 없음", TripStats(firstWarningMs = 10 * MIN).leadMin)
    }

    @Test
    fun tripLog_streakWeeklyAndCodec() {
        val now = 100 * DAY
        assertNull("5분 미만은 기록 안 함", TripLog.summarize(TripStats(drivingMs = 3 * MIN), now))
        val danger = TripLog.summarize(TripStats(drivingMs = 50 * MIN, rests = 1, maxLevel = FatigueLevel.DANGER), now)!!
        assertTrue("졸음 신호가 와도 쉬었으면 안전 운행", danger.safe)
        assertFalse("경고까지 갔는데 안 쉬면 안전 운행 아님", TripSummary(now, 30, 0, FatigueLevel.WARNING).safe)
        val demo = DemoDriver.trips(now)
        assertEquals("시연 운전자: 최근 4번 연속", 4, TripLog.streak(demo))
        assertEquals("시연 운행을 쉬고 마치면 5번", 5, TripLog.streak(TripLog.add(demo, danger)))
        val w = TripLog.weekly(TripLog.add(demo, danger), now)
        assertEquals(5, w.trips)
        assertEquals(2, w.warnedTrips)
        assertEquals(3, w.lastWeekWarnedTrips)
        assertTrue(w.text().contains("지난주 3번보다 줄었어요"))
        assertEquals(TripLog.KEEP, (1..40).fold(emptyList<TripSummary>()) { l, i -> TripLog.add(l, TripSummary(i * DAY, 30, 0, FatigueLevel.NORMAL)) }.size)
        val p = DriverProfile("d1", "운전자 1", 0).addTrip(danger)
        assertEquals(listOf(danger), DriverCodec.decode(DriverCodec.encode(DriverRoster.initial(p)))!!.current.trips)
        assertEquals("이번 주 운행 기록이 아직 없어요", TripLog.weekly(emptyList(), now).text())
    }

    @Test
    fun policy_coolDown() {
        val p = DialogPolicy()
        assertFalse("먼저 말을 걸기 전에는 X", p.decideCoolDown(10 * MIN, FatigueLevel.CAUTION, TalkLevel.NORMAL, null))
        p.markDone(Intervention.CHECK_IN, 10 * MIN)
        assertFalse("말 걸기 직후 2분은 쉼", p.decideCoolDown(11 * MIN, FatigueLevel.CAUTION, TalkLevel.NORMAL, null))
        assertTrue(p.decideCoolDown(13 * MIN, FatigueLevel.CAUTION, TalkLevel.NORMAL, null))
        assertFalse("최소는 X", p.decideCoolDown(13 * MIN, FatigueLevel.CAUTION, TalkLevel.MIN, null))
        assertFalse("위험은 휴식이 먼저", p.decideCoolDown(13 * MIN, FatigueLevel.DANGER, TalkLevel.NORMAL, null))
        p.markDone(Intervention.COOL_DOWN, 13 * MIN)
        assertFalse("한 번 하면 60분 쉼", p.decideCoolDown(50 * MIN, FatigueLevel.WARNING, TalkLevel.CHATTY, null))
        val r = AutoDriverScript.decide(AutoDriverInput(ReplyContext.COOL_DOWN, emptyList(), "피로 누적", FatigueLevel.CAUTION, 0, 0))!!
        assertEquals(UtteranceIntent.YES, UtteranceIntent.parse(r.text))
    }

    @Test
    fun comboMultiplier_growsWithStreak() {
        val s = app.daltokki.drive.domain.game.AwakeScore()
        s.startTrip()
        val pts = (1..6).map { s.onCorrect(fast = false, isWordGame = false) }
        assertEquals(listOf(50, 75, 100, 100, 150, 150), pts.map { it.points })
        assertEquals(listOf(1f, 1.5f, 2f, 2f, 3f, 3f), pts.map { it.multiplier })
        assertEquals(6, pts.last().combo)
        s.onWrong()
        val again = s.onCorrect(fast = true, isWordGame = false)
        assertEquals("콤보가 끊기면 ×1부터 (+번개)", 80, again.points)
        assertTrue(again.fast)
        assertEquals("반응 게임 3연속 ×2", 60, run { s.onCorrect(false, false, isReaction = true); s.onCorrect(false, false, isReaction = true).points })
    }

    @Test
    fun safetyActions_pointsCooldownBadgesBreakdown() {
        val s = app.daltokki.drive.domain.game.AwakeScore()
        s.startTrip()
        assertEquals(30, s.onSafetyAction(A.FRESH_AIR, 0).points)
        assertEquals("30분 안에 같은 행동은 0점", 0, s.onSafetyAction(A.FRESH_AIR, 10 * MIN).points)
        val cool = s.onSafetyAction(A.COOL_DOWN, 11 * MIN)
        assertEquals(30, cool.points)
        assertTrue("외기 + 온도 → 상쾌한 운전", cool.newBadges.contains(app.daltokki.drive.domain.game.Badges.FRESH_DRIVE))
        assertEquals(50, s.onSafetyAction(A.SELF_REPORT, 12 * MIN).points)
        assertEquals("졸림 신고는 운행마다 한 번", 0, s.onSafetyAction(A.SELF_REPORT, 90 * MIN).points)
        assertEquals(30, s.onSafetyAction(A.FRESH_AIR, 45 * MIN).points)
        val onTime = s.onSafetyAction(A.REST_ON_TIME, 50 * MIN)
        assertTrue(onTime.newBadges.contains(app.daltokki.drive.domain.game.Badges.ON_TIME))
        s.onCorrect(fast = false, isWordGame = false)
        s.onRestCompleted()
        val b = s.tripBreakdown
        assertEquals(50, b[app.daltokki.drive.domain.game.PointCategory.GAME])
        assertEquals(30 + 30 + 50 + 30 + 200, b[app.daltokki.drive.domain.game.PointCategory.SAFETY])
        assertEquals(500, b[app.daltokki.drive.domain.game.PointCategory.REST])
        assertEquals(b.values.sum(), s.tripPoints)
        s.startTrip()
        assertEquals("새 운행이면 졸림 신고 다시 가능", 50, s.onSafetyAction(A.SELF_REPORT, 200 * MIN).points)
        assertEquals(17, app.daltokki.drive.domain.game.Badges.all.size)
    }

    @Test
    fun rewards_shardsGiftsResult() {
        val s = app.daltokki.drive.domain.game.AwakeScore()
        s.startTrip()
        repeat(3) { s.onCorrect(fast = false, isWordGame = false) }
        s.onSafetyAction(A.FRESH_AIR, 0)
        assertEquals("정답 3 + 안전 행동 1 = 조각 4", 4, s.shards)
        val (n, award) = s.releaseShards()
        assertEquals(4, n)
        assertEquals(40, award.points)
        assertEquals(0, s.shards)
        assertEquals(0, s.releaseShards().first)
        // 선물 상자: 처음은 꼭 꾸미기, 가진 것은 다시 안 나옴, 점수는 50~300
        val G = app.daltokki.drive.domain.game.GiftBox
        val first = G.open(kotlin.random.Random(1), emptySet())
        assertTrue(first is app.daltokki.drive.domain.game.Gift.Item)
        val all = app.daltokki.drive.domain.game.Cosmetics.all.map { it.id }.toSet()
        repeat(50) { i ->
            val g = G.open(kotlin.random.Random(i), all)
            assertTrue("다 가졌으면 점수만", g is app.daltokki.drive.domain.game.Gift.Points)
            val pts = (g as app.daltokki.drive.domain.game.Gift.Points).points
            assertTrue(pts in 50..300)
        }
        val owned = setOf("hat")
        repeat(50) { i ->
            val g = G.open(kotlin.random.Random(i), owned)
            if (g is app.daltokki.drive.domain.game.Gift.Item) assertFalse(g.item.id in owned)
        }
        // 운행 결과
        val R = app.daltokki.drive.domain.game.TripResult
        val r = R.compute(TripStats(maxLevel = FatigueLevel.DANGER, rests = 1), 1_400, 6, 3, prevBestPoints = 1_000, prevBestCombo = 4)
        assertEquals(listOf(true, true, true), r.stars)
        assertEquals("S", r.grade)
        assertTrue(r.newBestScore && r.newBestCombo)
        val b = R.compute(TripStats(maxLevel = FatigueLevel.WARNING, rests = 0), 200, 1, 0, 1_000, 4)
        assertEquals("C", b.grade)
        assertFalse(b.newBestScore)
        // 저장 글
        val p = DriverProfile("d1", "운전자 1", 0).copy(items = setOf("hat", "scarf"), wearing = "scarf", gifts = 2, bestTripPoints = 900, bestCombo = 5)
        val back = DriverCodec.decode(DriverCodec.encode(DriverRoster.initial(p)))!!.current
        assertEquals(p.items, back.items)
        assertEquals("scarf", back.wearing)
        assertEquals(2, back.gifts)
        assertEquals(900, back.bestTripPoints)
        assertEquals(5, back.bestCombo)
        // 자동 시연: 초성 게임 뒤 "숫자 게임도 하자"
        assertEquals(UtteranceIntent.DIGIT_GAME, UtteranceIntent.parse(AutoDriverScript.ASK_MORE_GAME_TEXT))
        val done = hashSetOf(app.daltokki.drive.domain.sim.AutoAction.LANE_CHANGE, app.daltokki.drive.domain.sim.AutoAction.CALL, app.daltokki.drive.domain.sim.AutoAction.ASK_WORD_GAME)
        assertEquals(app.daltokki.drive.domain.sim.AutoAction.ASK_MORE_GAME, AutoDriverScript.actionFor("피로 누적", FatigueLevel.CAUTION, done))
    }
}
