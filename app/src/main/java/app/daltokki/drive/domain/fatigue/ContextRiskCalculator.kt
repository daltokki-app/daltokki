package app.daltokki.drive.domain.fatigue

import app.daltokki.drive.domain.model.DrivingFeatures

/**
 * 상황 위험도: "졸릴 만한 조건인가"를 0~1로 계산합니다.
 * - 연속 운전 시간: 60분부터 오르기 시작해 150분에 최대
 * - 시간대: 새벽 2~5시 최대, 오후 2~4시 중간 (생체리듬상 졸음이 몰리는 시간)
 * - 단조로움: 고속에서 일정한 속도로 오래 달리는 상태
 * - 수면 부족(출발 전 컨디션): 어젯밤 6시간 미만이면 더함 (RestPlanner.sleepDebtRisk)
 */
class ContextRiskCalculator {

    private var monotonousSince = -1L

    fun compute(
        t: Long,
        hourOfDay: Int,
        continuousDriveMs: Long,
        f: DrivingFeatures,
        speedKmh: Float,
        sleepDebt: Float = 0f,
        /** 실내 공기(CO₂ 추정)로 더할 값 (최대 0.1) */
        airRisk: Float = 0f,
    ): Float {
        val driveMin = continuousDriveMs / 60_000f
        val drive = ((driveMin - 60f) / 90f).coerceIn(0f, 1f)

        val circadian = when (hourOfDay) {
            in 2..5 -> 1f
            0, 1, 6 -> 0.6f
            14, 15 -> 0.5f
            13, 16 -> 0.25f
            else -> 0f
        }

        val std = f.speedStdKmh
        val monotone = speedKmh >= 80f && std != null && std < 4f
        if (monotone) {
            if (monotonousSince < 0) monotonousSince = t
        } else if (std != null) {
            // 판단 가능한 상황에서 단조롭지 않을 때만 초기화합니다.
            monotonousSince = -1L
        }
        val mono = if (monotonousSince < 0) 0f else ((t - monotonousSince) / (30 * 60_000f)).coerceIn(0f, 1f)

        return (0.5f * drive + 0.35f * circadian + 0.15f * mono + sleepDebt + airRisk).coerceIn(0f, 1f)
    }

    fun reset() {
        monotonousSince = -1L
    }
}
