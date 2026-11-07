package app.daltokki.drive.domain.fatigue

import kotlin.math.exp

/**
 * 실내 CO₂ 추정 (센서 없이 차량 신호로). 보조 지표입니다.
 *
 * 공기 수지 식: dC/dt = N·G/V − λ·(C − C_out)
 * - N: 탑승 인원 (좌석 착좌, 모르면 1명), G: 1인 CO₂ 배출량(앉아서 운전 약 18 L/h), V: 실내 부피(약 3 m³)
 * - λ: 환기율(시간당 공기 교체 횟수). 내기순환이면 틈새로 조금(0.6), 외기면 풍량에 따라 크게(15 + 10×풍량, 차량 실측 연구의 시간당 30~100회 범위)
 * - C_out: 바깥 CO₂ 약 420ppm
 * 시간 간격마다 정확한 지수 해로 갱신합니다(배속 재생에서도 안정적).
 *
 * CO₂가 졸음·판단력에 주는 영향은 연구마다 결과가 엇갈려서, 졸음 위험도에는 작게(최대 +0.1)만 더합니다.
 * 내기순환 여부를 모르면 추정하지 않습니다(null).
 */
class CabinAirModel(
    private val volumeM3: Double = 3.0,
    private val outdoorPpm: Double = 420.0,
    private val perPersonM3h: Double = 0.018,
) {
    /** 지금 추정값(ppm). 모르면 null */
    var ppm: Float? = null
        private set

    private var c = outdoorPpm + 100.0
    private var lastT = -1L
    private var known = false

    fun update(t: Long, recirculation: Boolean?, fanSpeed: Int?, occupants: Int?, driving: Boolean) {
        val dtH = if (lastT < 0) 0.0 else ((t - lastT).coerceIn(0L, 60_000L)) / 3_600_000.0
        lastT = t
        if (recirculation == null) {
            // 모르면 추정하지 않음 (이전 값도 버림)
            known = false
            ppm = null
            return
        }
        if (!known) {
            known = true
            c = outdoorPpm + 100.0
        }
        val people = (occupants ?: 1).coerceIn(1, 9)
        val lambda = airChangesPerHour(recirculation, fanSpeed, driving)
        val steady = outdoorPpm + people * perPersonM3h / (volumeM3 * lambda) * 1_000_000.0
        c = steady + (c - steady) * exp(-lambda * dtH)
        ppm = c.toFloat()
    }

    /** 휴식·하차 후: 문을 열었다 닫았으니 바깥 공기에 가깝게 */
    fun reset() {
        c = outdoorPpm + 100.0
        if (known) ppm = c.toFloat()
    }

    companion object {
        /** 이 이상이면 환기 제안 */
        const val SUGGEST_PPM = 1_200f
        /** 근거로 보여주는 기준 */
        const val REASON_PPM = 1_500f

        fun airChangesPerHour(recirculation: Boolean, fanSpeed: Int?, driving: Boolean): Double = when {
            recirculation -> if (driving) 0.6 else 0.4
            else -> 15.0 + 10.0 * (fanSpeed ?: 2).coerceIn(0, 8)
        }

        /** 졸음 위험도(상황)에 더할 값: 1,000ppm부터 3,000ppm까지 0 → 0.1 */
        fun risk(ppm: Float?): Float = if (ppm == null) 0f else ((ppm - 1_000f) / 2_000f).coerceIn(0f, 1f) * 0.1f
    }
}
