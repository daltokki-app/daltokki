package app.daltokki.drive.domain

import app.daltokki.drive.domain.dialog.TalkLevel
import app.daltokki.drive.domain.driver.DemoDriver
import app.daltokki.drive.domain.driver.DriverCodec
import app.daltokki.drive.domain.driver.DriverProfile
import app.daltokki.drive.domain.driver.DriverRoster
import app.daltokki.drive.domain.driver.HabitHistory
import app.daltokki.drive.domain.driver.HabitLearning
import app.daltokki.drive.domain.driver.SpeedBand
import app.daltokki.drive.domain.driver.TripHabit
import app.daltokki.drive.domain.fatigue.BaselineCalibrator.Baseline
import app.daltokki.drive.domain.fatigue.FatigueConfig
import app.daltokki.drive.domain.fatigue.FatigueEngine
import app.daltokki.drive.domain.fatigue.ResponseTracker
import app.daltokki.drive.domain.model.DriveEvent
import app.daltokki.drive.domain.model.DriveState
import app.daltokki.drive.domain.model.FatigueLevel
import app.daltokki.drive.domain.sim.DriveScenario
import app.daltokki.drive.domain.sim.ScenarioGenerator
import app.daltokki.drive.domain.sim.Scenarios
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/** 운전자별 누적 운전 습관·운전자 목록 (12차) */
class DriverHabitTest {

    private class Run(val byPhase: Map<String, FatigueLevel>, val events: List<DriveEvent>, val trip: TripHabit?, val firstState: DriveState?)

    private fun run(scenario: DriveScenario, seed: FatigueEngine.HabitSeed? = null, hour: Int? = null): Run {
        val start = LocalDate.of(2026, 11, 3).atTime(hour ?: scenario.startHour, scenario.startMinute).toInstant(ZoneOffset.UTC).toEpochMilli()
        val gen = ScenarioGenerator(scenario, start, seed = 42L)
        val engine = FatigueEngine(zone = ZoneOffset.UTC)
        engine.habitSeed = seed
        val max = LinkedHashMap<String, FatigueLevel>()
        val events = mutableListOf<DriveEvent>()
        var trip: TripHabit? = null
        var firstState: DriveState? = null
        while (!gen.isFinished) {
            val label = gen.currentPhase().label
            val a = engine.onSnapshot(gen.next())
            events += a.events
            if (firstState == null && a.driveState.isDriving) firstState = a.driveState
            max[label] = maxOf(max[label] ?: FatigueLevel.NORMAL, a.level)
            engine.takeTripHabit()?.let { trip = it }
        }
        return Run(max, events, trip, firstState)
    }

    private fun history(base: Baseline, trips: Int = 12, band: SpeedBand = SpeedBand.HIGH): HabitHistory {
        var h = HabitHistory()
        repeat(trips) { i -> h = h.add(mapOf(band to base), i.toLong()) }
        return h
    }

    @Test
    fun history_medianPerBand_needsThreeTrips_keepsTwenty() {
        var h = HabitHistory()
        assertNull(h.baseline(null))
        h = h.add(mapOf(SpeedBand.HIGH to Baseline(30f, 2f)), 1)
        h = h.add(mapOf(SpeedBand.HIGH to Baseline(40f, 3f), SpeedBand.MID to Baseline(60f, 4f)), 2)
        assertFalse("2회로는 부족", h.ready)
        h = h.add(mapOf(SpeedBand.HIGH to Baseline(35f, 2.5f)), 3)
        assertTrue(h.ready)
        assertEquals(35f, h.baseline(SpeedBand.HIGH)!!.reversalRatePerMin, 0.01f)
        // MID는 1회뿐이라 전체(4개)의 중앙값
        assertEquals(37.5f, h.baseline(SpeedBand.MID)!!.reversalRatePerMin, 0.01f)
        repeat(30) { i -> h = h.add(mapOf(SpeedBand.HIGH to Baseline(50f, 2f)), 10L + i) }
        assertEquals(HabitHistory.KEEP, h.samples.count { it.band == SpeedBand.HIGH })
        assertEquals(33, h.trips)
        assertEquals("오래된 기록은 밀려남", 50f, h.baseline(SpeedBand.HIGH)!!.reversalRatePerMin, 0.01f)
    }

    @Test
    fun learning_skipsTiredDays_andAsksOnMismatch() {
        val h = history(Baseline(40f, 2f))
        fun trip(rate: Float, clean: Boolean = true, hour: Int = 14, sleep: Float? = 7f) =
            TripHabit(mapOf(SpeedBand.HIGH to Baseline(rate, 2f)), Baseline(rate, 2f), clean, hour, sleep, 0L)
        assertEquals(HabitLearning.Verdict.ACCEPT, HabitLearning.judge(trip(36f), h).verdict)
        assertEquals(HabitLearning.Verdict.SKIP_TIRED, HabitLearning.judge(trip(36f, clean = false), h).verdict)
        assertEquals(HabitLearning.Verdict.SKIP_TIRED, HabitLearning.judge(trip(36f, hour = 3), h).verdict)
        assertEquals(HabitLearning.Verdict.SKIP_TIRED, HabitLearning.judge(trip(36f, sleep = 5f), h).verdict)
        assertEquals(HabitLearning.Verdict.SKIP_MISMATCH, HabitLearning.judge(trip(15f), h).verdict)
        assertEquals(HabitLearning.Verdict.SKIP_MISMATCH, HabitLearning.judge(trip(95f), h).verdict)
        assertEquals("누적이 없으면 비교 없이 배움", HabitLearning.Verdict.ACCEPT, HabitLearning.judge(trip(95f), HabitHistory()).verdict)
    }

    @Test
    fun nightScenario_learnsHabit_butNightTripIsNotStored() {
        val r = run(Scenarios.nightHighway)
        val trip = r.trip
        assertNotNull("또렷한 고속도로 구간에서 이번 운행 습관 완성", trip)
        println("night trip habit: overall=${trip!!.overall} bands=${trip.bands}")
        assertTrue(trip.bands.containsKey(SpeedBand.HIGH))
        assertEquals("새벽 운행은 누적하지 않음", HabitLearning.Verdict.SKIP_TIRED, HabitLearning.judge(trip, HabitHistory()).verdict)
        assertEquals("누적이 없으면 처음에는 익히는 중", DriveState.CALIBRATING, r.firstState)
    }

    @Test
    fun storedHabit_judgesFromDeparture_andKeepsScenarioResults() {
        // 시연 운전자의 누적 습관(새벽 시나리오의 또렷한 구간과 같은 습관)으로 출발
        val demo = DemoDriver.profile(stamps = "", now = 0L)
        assertTrue(demo.habit.ready)
        val seed = FatigueEngine.HabitSeed(demo.habit, demo.latencySamples)
        val night = run(Scenarios.nightHighway, seed)
        assertEquals("누적 습관이 있으면 출발하자마자 판단", DriveState.MONITORING, night.firstState)
        assertFalse("학습 완료 이벤트 없음(이미 아는 습관)", night.events.contains(DriveEvent.CalibrationDone))
        assertTrue(night.byPhase.getValue("고속도로 · 또렷함") <= FatigueLevel.CAUTION)
        assertTrue(night.byPhase.getValue("피로 누적") >= FatigueLevel.CAUTION)
        assertTrue(night.byPhase.getValue("졸음 운전") >= FatigueLevel.WARNING)
        // 오탐 없음: 같은 누적 습관으로 오전 정상 주행
        val day = run(Scenarios.dayNormal, seed)
        day.byPhase.forEach { (label, level) -> assertEquals(label, FatigueLevel.NORMAL, level) }
        // 낮 정상 주행에서 배운 습관은 누적에 들어감
        val dayTrip = day.trip
        if (dayTrip != null) {
            assertEquals(HabitLearning.Verdict.ACCEPT, HabitLearning.judge(dayTrip, demo.habit).verdict)
        }
    }

    @Test
    fun responseTracker_usesStoredLatency() {
        val t = ResponseTracker(FatigueConfig())
        assertFalse(t.hasPersonalBaseline)
        t.seed(listOf(1_000L, 1_100L, 1_200L))
        assertTrue(t.hasPersonalBaseline)
        assertEquals(1_100L, t.baselineLatencyMs)
        t.recordBaseline(2_000L)
        assertEquals(listOf(2_000L), t.tripBaselineSamples)
    }

    @Test
    fun roster_addSelectRenameRemove_andCodecRoundTrip() {
        val first = DriverProfile("d1", "운전자 1", 0, stamps = "a\tb\nc\\d", lifetimePoints = 700, badges = setOf("first_word", "rest_keeper"))
        var r = DriverRoster.initial(first)
        r = r.add("엄마", "d2", 5L)
        assertEquals("d2", r.currentId)
        assertEquals(1, r.current.colorIndex)
        r = r.update("d2") { it.copy(habit = history(Baseline(33f, 1.5f), trips = 4), latencySamples = listOf(900L, 1_000L), talkLevel = TalkLevel.CHATTY, sleepHours = 6.5f) }
        r = r.rename("d1", "아빠").select(DriverProfile.GUEST_ID)
        assertTrue(r.current.isGuest)
        assertFalse(r.current.persistent)
        val back = DriverCodec.decode(DriverCodec.encode(r))!!
        assertEquals(r.currentId, back.currentId)
        assertEquals("아빠", back.drivers[0].name)
        assertEquals("탭·줄바꿈·역슬래시가 든 도장 글도 그대로", first.stamps, back.drivers[0].stamps)
        assertEquals(setOf("first_word", "rest_keeper"), back.drivers[0].badges)
        val mom = back.drivers[1]
        assertEquals(4, mom.habit.trips)
        assertEquals(33f, mom.habit.baseline(SpeedBand.HIGH)!!.reversalRatePerMin, 0.01f)
        assertEquals(listOf(900L, 1_000L), mom.latencySamples)
        assertEquals(TalkLevel.CHATTY, mom.talkLevel)
        assertEquals(6.5f, mom.sleepHours!!, 0.01f)
        // 삭제: 마지막 한 명은 지울 수 없음
        val one = back.remove("d1")
        assertEquals(1, one.drivers.size)
        assertEquals(one, one.remove("d2"))
        assertNull(DriverCodec.decode("이상한 글"))
        // 꽉 차면 추가 안 됨
        var full = DriverRoster.initial(first)
        repeat(10) { full = full.add("운전자$it", "x$it", 0L) }
        assertEquals(DriverRoster.MAX_DRIVERS, full.drivers.size)
        assertEquals("다시 배우기", 0, back.drivers[1].resetHabit().habit.trips)
    }

    @Test
    fun demoDriver_stampsOnScenarioRoute_thenMangHyangCompletesRouteBadge() {
        val db = app.daltokki.drive.data.nav.RestAreaDatabase.merge(
            load("rest_areas/service_areas.csv", "휴게소"),
            load("rest_areas/drowsy_shelters.csv", "졸음쉼터"),
        )
        val sim = db.findByName(Scenarios.nightHighway.restStopId!!)!!
        val text = DemoDriver.stamps(db.stops, sim.route, sim.spokenName, 1_000_000_000L)
        val book = app.daltokki.drive.domain.game.StampBook(
            app.daltokki.drive.domain.game.StampBook.parse(text),
        ) { db.routeTotals }
        assertEquals("같은 노선 다른 휴게소 4곳", 4, book.places)
        assertTrue(book.records.none { it.name == sim.spokenName })
        val r = book.stamp(sim, 2_000_000_000L)
        assertEquals(5, r.places)
        assertEquals("시연에서 노선 5곳째 → '노선 탐방' 배지 조건", 5, r.bestRoute)
        val score = app.daltokki.drive.domain.game.AwakeScore(DemoDriver.profile(text, 0L).lifetimePoints, DemoDriver.profile(text, 0L).badges)
        val award = score.onRestCompleted()
        val stampAward = score.onStamp(r.isNew, r.places, r.shelters, r.bestRoute)
        val names = (award.newBadges + stampAward.newBadges).map { it.id }
        assertTrue("1,500점 넘어 새벽 지킴이: $names", names.contains("dawn_guard"))
        assertTrue("노선 탐방: $names", names.contains("route5"))
    }

    private fun load(path: String, type: String): app.daltokki.drive.data.nav.RestAreaDatabase {
        val f = java.io.File("src/main/assets/$path")
        return app.daltokki.drive.data.nav.RestAreaDatabase.parse(
            app.daltokki.drive.data.nav.RestAreaDatabase.decode(f.readBytes()), type,
        )
    }
}
