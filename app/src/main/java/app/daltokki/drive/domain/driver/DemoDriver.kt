package app.daltokki.drive.domain.driver

import app.daltokki.drive.data.nav.RestStop
import app.daltokki.drive.domain.dialog.DriverMemory
import app.daltokki.drive.domain.dialog.TalkLevel
import app.daltokki.drive.domain.fatigue.BaselineCalibrator.Baseline
import app.daltokki.drive.domain.game.Badges
import app.daltokki.drive.domain.game.StampBook
import app.daltokki.drive.domain.model.FatigueLevel

/**
 * 자동 시연용 임시 운전자 "운전자 1" (저장하지 않음, 실제 운전자 기록은 건드리지 않음).
 * 기본 이름이라 달토끼가 말할 때는 이름을 부르지 않습니다.
 *
 * - 운전 습관: 지난 12번 운행(고속도로)으로 익힌 상태 → 출발하자마자 판단
 *   (값은 새벽 고속도로 시나리오의 또렷한 구간에서 잰 습관과 같게, 운행마다 조금씩 다르게)
 * - 대답 속도: 평소 1.0~1.3초
 * - 도장 4곳(경부선) · 누적 1,300점(반달) · 배지 3개
 *   → 시연에서 망향휴게소 도장을 받으면 경부선 5곳째 '노선 탐방', 1,500점 넘어 '새벽 지킴이' 배지와 볼록달
 */
object DemoDriver {

    const val ID = "demo"
    const val NAME = "운전자 1"
    const val TRIPS = 12

    /** 새벽 고속도로 시나리오 또렷한 구간(시속 100km)의 습관 (DriverHabitTest에서 잰 값) */
    val SCENARIO_HABIT = Baseline(reversalRatePerMin = 42f, speedStdKmh = 1.0f)

    /** 운행마다 조금씩 다르게 (중앙값은 [SCENARIO_HABIT]) */
    private val JITTER = listOf(0f, 0.04f, -0.03f, 0.02f, -0.05f, 0.03f, -0.02f, 0.05f, -0.04f, 0.01f, -0.01f, 0f)

    fun habit(): HabitHistory {
        var h = HabitHistory()
        for (i in 0 until TRIPS) {
            val j = JITTER[i % JITTER.size]
            val b = Baseline(SCENARIO_HABIT.reversalRatePerMin * (1 + j), SCENARIO_HABIT.speedStdKmh * (1 - j))
            h = h.add(mapOf(SpeedBand.HIGH to b), DAY_MS * (i + 1))
        }
        return h
    }

    fun profile(stamps: String, now: Long) = DriverProfile(
        id = ID,
        name = NAME,
        colorIndex = 1,
        habit = habit(),
        latencySamples = listOf(1_050L, 1_200L, 980L, 1_150L, 1_100L, 1_250L),
        talkLevel = TalkLevel.CHATTY,
        stamps = stamps,
        lifetimePoints = 1_300,
        badges = setOf(Badges.FIRST_WORD.id, Badges.REST_KEEPER.id, Badges.FIRST_STAMP.id),
        createdMs = now,
        demo = true,
        // 기억하는 대화: 지난번에 "부산 가는 길이야", "국밥 좋아해"라고 했던 운전자 (망향휴게소 대표 메뉴 = 명품한우국밥)
        memory = mapOf(DriverMemory.Slot.DESTINATION to "부산", DriverMemory.Slot.FOOD to "국밥"),
        trips = trips(now),
    )

    /**
     * 지난 12번 운행 기록: 최근 4번은 안전 운행(→ 시연 운행을 쉬고 마치면 '안전 운행 5번 연속'),
     * 5번째는 경고까지 갔는데 쉬지 않은 운행. 이번 주 졸음 경고 1번, 지난주 3번.
     */
    fun trips(now: Long): List<TripSummary> {
        val levels = listOf(
            FatigueLevel.CAUTION to 0, FatigueLevel.NORMAL to 0, FatigueLevel.WARNING to 1, FatigueLevel.NORMAL to 0,
            FatigueLevel.WARNING to 0, FatigueLevel.CAUTION to 0, FatigueLevel.WARNING to 1, FatigueLevel.NORMAL to 0,
            FatigueLevel.WARNING to 1, FatigueLevel.CAUTION to 0, FatigueLevel.NORMAL to 0, FatigueLevel.CAUTION to 1,
        )
        return levels.mapIndexed { i, (level, rests) ->
            TripSummary(now - DAY_MS * (1 + i * 3 / 2) - 3 * 60 * 60_000L, 40 + (i * 17) % 90, rests, level)
        }.sortedBy { it.endMs }
    }

    /**
     * 시연 운전자의 지난 도장: 시나리오 휴게소와 같은 노선의 다른 곳 4곳.
     * @param stops 공공데이터 휴게소·쉼터 전체
     * @param excludeSpoken 시연에서 받을 휴게소의 부르는 이름(망향휴게소, 양방향 모두 제외)
     */
    fun stamps(stops: List<RestStop>, route: String?, excludeSpoken: String?, now: Long): String {
        if (route == null) return ""
        val picks = stops.filter { it.route == route && it.spokenName != excludeSpoken && !it.isShelter }.distinctBy { it.spokenName }.take(4)
        val book = StampBook()
        picks.forEachIndexed { i, s -> book.stamp(s, now - DAY_MS * (20 - i * 4)) }
        return book.serialize()
    }

    private const val DAY_MS = 24 * 60 * 60_000L
}
