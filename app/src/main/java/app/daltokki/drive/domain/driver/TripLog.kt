package app.daltokki.drive.domain.driver

import app.daltokki.drive.domain.model.FatigueLevel
import app.daltokki.drive.domain.model.TripStats

/**
 * 운행 기록 한 줄 (운전자별 최근 [TripLog.KEEP]개, 기기 안에만).
 * @param safe 안전 운행: 졸음 신호 없이('주의'까지) 끝났거나, 졸음 신호가 오면 쉬었음
 */
data class TripSummary(
    val endMs: Long,
    val drivingMin: Int,
    val rests: Int,
    val maxLevel: FatigueLevel,
) {
    val safe: Boolean get() = maxLevel <= FatigueLevel.CAUTION || rests > 0
    val warned: Boolean get() = maxLevel >= FatigueLevel.WARNING
}

/** 이번 주(최근 7일)·지난주(7~14일 전) 요약 */
data class WeeklyReport(
    val trips: Int,
    val drivingMin: Int,
    val rests: Int,
    val warnedTrips: Int,
    val lastWeekWarnedTrips: Int,
) {
    /** "이번 주 3번 운행 · 휴식 2번 · 졸음 경고 1번 (지난주 3번)" */
    fun text(): String {
        if (trips == 0) return "이번 주 운행 기록이 아직 없어요"
        val cmp = when {
            lastWeekWarnedTrips == 0 && warnedTrips == 0 -> ""
            warnedTrips < lastWeekWarnedTrips -> " (지난주 ${lastWeekWarnedTrips}번보다 줄었어요)"
            else -> " (지난주 ${lastWeekWarnedTrips}번)"
        }
        return "이번 주 ${trips}번 운행 · 휴식 ${rests}번 · 졸음 경고 ${warnedTrips}번$cmp"
    }
}

/**
 * 리텐션: 안전 운행 연속 기록과 주간 리포트.
 * 짧은 이동(5분 미만)은 기록하지 않습니다.
 */
object TripLog {
    const val KEEP = 30
    const val MIN_DRIVING_MIN = 5
    private const val DAY_MS = 24 * 60 * 60_000L

    fun summarize(stats: TripStats, endMs: Long): TripSummary? {
        val min = (stats.drivingMs / 60_000L).toInt()
        if (min < MIN_DRIVING_MIN) return null
        return TripSummary(endMs, min, stats.rests, stats.maxLevel)
    }

    fun add(trips: List<TripSummary>, t: TripSummary): List<TripSummary> = (trips + t).sortedBy { it.endMs }.takeLast(KEEP)

    /** 최근부터 거꾸로 이어진 안전 운행 수 */
    fun streak(trips: List<TripSummary>): Int = trips.sortedByDescending { it.endMs }.takeWhile { it.safe }.size

    fun weekly(trips: List<TripSummary>, now: Long): WeeklyReport {
        val thisWeek = trips.filter { now - it.endMs in 0 until 7 * DAY_MS }
        val lastWeek = trips.filter { now - it.endMs in 7 * DAY_MS until 14 * DAY_MS }
        return WeeklyReport(
            trips = thisWeek.size,
            drivingMin = thisWeek.sumOf { it.drivingMin },
            rests = thisWeek.sumOf { it.rests },
            warnedTrips = thisWeek.count { it.warned },
            lastWeekWarnedTrips = lastWeek.count { it.warned },
        )
    }

    /** 저장 글: "endMs,min,rests,LEVEL;…" */
    fun encode(trips: List<TripSummary>): String =
        trips.joinToString(";") { "${it.endMs},${it.drivingMin},${it.rests},${it.maxLevel.name}" }

    fun decode(text: String?): List<TripSummary> =
        text.orEmpty().split(';').mapNotNull { part ->
            val c = part.split(',')
            if (c.size < 4) return@mapNotNull null
            val level = FatigueLevel.entries.firstOrNull { it.name == c[3] } ?: return@mapNotNull null
            TripSummary(c[0].toLongOrNull() ?: return@mapNotNull null, c[1].toIntOrNull() ?: 0, c[2].toIntOrNull() ?: 0, level)
        }
}
