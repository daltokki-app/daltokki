package app.daltokki.drive.domain.fatigue

import app.daltokki.drive.domain.driver.HabitHistory
import app.daltokki.drive.domain.driver.SpeedBand
import app.daltokki.drive.domain.model.DrivingFeatures

/**
 * 운전 습관(개인 기준선).
 *
 * 운행마다: 처음 일정 시간(기본: 유효 주행 15분 = 시속 60km 이상 직진, 차선 변경·차로 보조 제외)의
 * 핸들 미세 보정 빈도와 속도 흔들림을 재서 "이번 운행 습관"을 만듭니다(속도대별로도).
 *
 * 운전자별 누적 습관([HabitHistory])이 3회 이상 쌓여 있으면 출발하자마자 그것으로 판단하고,
 * 이번 운행 습관이 완성되면 둘을 섞어 씁니다(누적 70% + 이번 30%, 그날 컨디션 반영).
 * 누적 습관이 없으면 예전처럼 이번 운행 습관이 완성될 때까지 '익히는 중'입니다.
 *
 * 이후 판정은 절댓값이 아니라 이 기준선 대비 변화율로 합니다.
 */
class BaselineCalibrator(private val cfg: FatigueConfig) {

    data class Baseline(val reversalRatePerMin: Float, val speedStdKmh: Float)

    private class Sample(val band: SpeedBand?, val reversal: Float, val speedStd: Float?)

    private val samples = mutableListOf<Sample>()
    private var accumulatedValidMs = 0L
    private var lastRecordT = -1L

    /** 이번 운행에 쓰는 누적 습관 (3회 이상 쌓였을 때만) */
    private var stored: HabitHistory? = null

    /** 이번 운행에서 배운 습관 (완성 전에는 null) */
    var tripBaseline: Baseline? = null
        private set

    /** 이번 운행에서 배운 속도대별 습관 */
    var tripBands: Map<SpeedBand, Baseline> = emptyMap()
        private set

    /** 운행 시작: 누적 습관을 받고 이번 운행 학습을 처음부터 */
    fun startTrip(history: HabitHistory?) {
        stored = history?.takeIf { it.ready }
        samples.clear()
        accumulatedValidMs = 0L
        lastRecordT = -1L
        tripBaseline = null
        tripBands = emptyMap()
    }

    /** 누적 습관으로 바로 판단하는 중인지 */
    val usingStored: Boolean get() = stored != null

    /** 누적 습관에 반영된 운행 수 (누적을 안 쓰면 0) */
    val storedTrips: Int get() = stored?.trips ?: 0

    /** 이번 운행 습관을 다 배웠는지 */
    val tripLearned: Boolean get() = tripBaseline != null

    /** 판단할 기준선이 있는지 (누적 습관 또는 이번 운행 습관) */
    val ready: Boolean get() = usingStored || tripLearned

    /** 기준선 (속도대 구분 없이, 화면·호환용) */
    val baseline: Baseline? get() = baselineFor(null)

    val progress: Float
        get() = if (ready) 1f else (accumulatedValidMs.toFloat() / cfg.calibrationValidMs).coerceIn(0f, 0.99f)

    /** 지금 속도대의 기준선: 누적과 이번 운행을 섞음 (한쪽만 있으면 그것) */
    fun baselineFor(speedKmh: Float?): Baseline? {
        val band = SpeedBand.of(speedKmh)
        val s = stored?.baseline(band)
        val trip = band?.let { tripBands[it] } ?: tripBaseline
        return when {
            s != null && trip != null -> Baseline(
                reversalRatePerMin = s.reversalRatePerMin * STORED_WEIGHT + trip.reversalRatePerMin * (1 - STORED_WEIGHT),
                speedStdKmh = s.speedStdKmh * STORED_WEIGHT + trip.speedStdKmh * (1 - STORED_WEIGHT),
            )
            s != null -> s
            else -> trip
        }
    }

    /** @return 이번 호출로 이번 운행 습관이 막 완성되었으면 true */
    fun update(t: Long, validDtMs: Long, f: DrivingFeatures): Boolean {
        if (tripLearned) return false
        accumulatedValidMs += validDtMs
        val rate = f.reversalRatePerMin
        if (rate != null && f.validCoverage >= 0.5f &&
            (lastRecordT < 0 || t - lastRecordT >= cfg.calibrationRecordEveryMs)
        ) {
            lastRecordT = t
            samples += Sample(SpeedBand.of(f.meanSpeedKmh), rate, f.speedStdKmh)
        }
        if (accumulatedValidMs >= cfg.calibrationValidMs && samples.size >= MIN_SAMPLES) {
            tripBaseline = baselineOf(samples)
            tripBands = SpeedBand.entries.mapNotNull { b ->
                val inBand = samples.filter { it.band == b }
                if (inBand.size >= MIN_SAMPLES) b to baselineOf(inBand) else null
            }.toMap()
            return true
        }
        return false
    }

    private fun baselineOf(list: List<Sample>): Baseline {
        val stds = list.mapNotNull { it.speedStd }
        return Baseline(
            reversalRatePerMin = HabitHistory.median(list.map { it.reversal }).coerceAtLeast(5f),
            speedStdKmh = (if (stds.isEmpty()) 2f else HabitHistory.median(stds)).coerceAtLeast(1f),
        )
    }

    private companion object {
        const val MIN_SAMPLES = 5
        /** 누적 습관 비중 (나머지는 이번 운행) */
        const val STORED_WEIGHT = 0.7f
    }
}
