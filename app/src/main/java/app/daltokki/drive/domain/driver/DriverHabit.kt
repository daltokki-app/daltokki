package app.daltokki.drive.domain.driver

import app.daltokki.drive.domain.fatigue.BaselineCalibrator.Baseline

/** 운전 습관을 나눠 저장하는 속도대 (고속도로와 국도의 핸들 습관이 다름) */
enum class SpeedBand(val label: String) {
    MID("시속 60~90km"),
    HIGH("시속 90km 이상");

    companion object {
        /** 핸들 지표를 쓰는 속도(60km 이상)만. 모르면 null */
        fun of(speedKmh: Float?): SpeedBand? = when {
            speedKmh == null -> null
            speedKmh >= 90f -> HIGH
            speedKmh >= 60f -> MID
            else -> null
        }
    }
}

/** 한 번의 운행에서 배운 습관 (속도대 하나) */
data class HabitSample(
    val band: SpeedBand,
    val reversalRatePerMin: Float,
    val speedStdKmh: Float,
    val timeMs: Long,
)

/**
 * 운전자 한 명의 누적 운전 습관.
 * - 운행마다 배운 습관을 속도대별로 최근 [KEEP]회까지 보관
 * - 실제로 쓰는 기준선 = 보관한 값들의 중앙값 (어느 하루가 이상해도 흔들리지 않음)
 * - [MIN_TRIPS]회 이상 쌓이면 출발하자마자 이 기준선으로 판단 (15분 학습 공백 없음)
 *
 * 안드로이드에 의존하지 않는 순수 Kotlin.
 */
data class HabitHistory(
    val samples: List<HabitSample> = emptyList(),
    /** 학습에 반영된 운행 수 */
    val trips: Int = 0,
) {
    /** 출발하자마자 쓸 수 있을 만큼 쌓였는지 */
    val ready: Boolean get() = trips >= MIN_TRIPS && samples.size >= MIN_TRIPS

    /**
     * 이 속도대의 기준선. 그 속도대 기록이 적으면 전체 기록으로. 아직 부족하면 null.
     * @param band null이면 전체
     */
    fun baseline(band: SpeedBand?): Baseline? {
        if (!ready) return null
        val inBand = if (band == null) emptyList() else samples.filter { it.band == band }.takeLast(KEEP)
        val use = if (inBand.size >= MIN_TRIPS) inBand else samples.takeLast(KEEP)
        return Baseline(
            reversalRatePerMin = median(use.map { it.reversalRatePerMin }).coerceAtLeast(5f),
            speedStdKmh = median(use.map { it.speedStdKmh }).coerceAtLeast(1f),
        )
    }

    /** 이번 운행에서 배운 습관을 더함 (속도대별 최근 [KEEP]회만 남김) */
    fun add(bands: Map<SpeedBand, Baseline>, timeMs: Long): HabitHistory {
        if (bands.isEmpty()) return this
        val added = samples + bands.map { (b, v) -> HabitSample(b, v.reversalRatePerMin, v.speedStdKmh, timeMs) }
        val kept = SpeedBand.entries.flatMap { b -> added.filter { it.band == b }.takeLast(KEEP) }.sortedBy { it.timeMs }
        return HabitHistory(kept, trips + 1)
    }

    companion object {
        const val KEEP = 20
        const val MIN_TRIPS = 3

        fun median(values: List<Float>): Float {
            if (values.isEmpty()) return 0f
            val sorted = values.sorted()
            val mid = sorted.size / 2
            return if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2f else sorted[mid]
        }
    }
}

/** 이번 운행 처음 15분(유효 주행)에서 배운 습관과 그때의 조건 */
data class TripHabit(
    /** 속도대별 기준선 (표본이 충분한 속도대만) */
    val bands: Map<SpeedBand, Baseline>,
    /** 속도대 구분 없는 이번 운행 기준선 */
    val overall: Baseline,
    /** 배우는 동안 '좋아요' 단계였고 핸들 무조작 후 급꺾음이 없었는지 */
    val clean: Boolean,
    /** 출발 시각(시) */
    val startHour: Int,
    val sleepHours: Float?,
    val timeMs: Long,
)

/**
 * 습관 학습 보호장치: 졸린 날의 습관은 배우지 않음 (그냥 쌓으면 기준이 점점 무뎌짐).
 * 그리고 평소와 너무 다르면 다른 사람이 운전했을 수 있어 묻습니다.
 */
object HabitLearning {

    enum class Verdict { ACCEPT, SKIP_TIRED, SKIP_MISMATCH }

    data class Judgement(val verdict: Verdict, val reason: String)

    /** 이 범위를 벗어나면 다른 사람일 수 있음 (평소 미세 보정 빈도 대비) */
    const val MISMATCH_LOW = 0.5f
    const val MISMATCH_HIGH = 2.0f

    fun isNight(hour: Int): Boolean = hour in 0..5

    fun judge(trip: TripHabit, history: HabitHistory): Judgement {
        if (!trip.clean) return Judgement(Verdict.SKIP_TIRED, "배우는 동안 졸음 신호가 있었음")
        if (isNight(trip.startHour)) return Judgement(Verdict.SKIP_TIRED, "새벽 운행")
        val sleep = trip.sleepHours
        if (sleep != null && sleep < 6f) return Judgement(Verdict.SKIP_TIRED, "수면 부족")
        val stored = history.baseline(null)
        if (stored != null) {
            val ratio = trip.overall.reversalRatePerMin / stored.reversalRatePerMin
            if (ratio < MISMATCH_LOW || ratio > MISMATCH_HIGH) {
                return Judgement(Verdict.SKIP_MISMATCH, "핸들 미세 보정이 평소의 ${(ratio * 100).toInt()}%")
            }
        }
        return Judgement(Verdict.ACCEPT, "")
    }

    /** 대답 속도 기준도 같은 조건(새벽·수면 부족이 아닐 때)에서만 쌓음 */
    fun latencyOk(startHour: Int, sleepHours: Float?): Boolean =
        !isNight(startHour) && (sleepHours == null || sleepHours >= 6f)
}
