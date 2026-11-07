package app.daltokki.drive.domain.fatigue

import app.daltokki.drive.domain.model.FatigueLevel
import kotlin.math.exp

/**
 * 여러 신호를 합쳐 졸음 위험도와 단계를 정합니다.
 *
 * 1) 센서 위험도 = 상황 위험도 × 0.25 + 행동 신호(핸들 0.45, 차선 0.10, 대화 0.20)
 *    - 핸들 신호가 있을 때: 없는 행동 신호(차선·대화)의 가중치를 있는 행동 신호에 나눠 줍니다.
 *    - 핸들 신호가 없을 때(저속·커브·데이터 없음): 대화·차선은 드문드문 생기는 신호라
 *      각자 가중치 × 1.5 × 신뢰도까지만 쓰고, 합계도 0.35를 넘지 않습니다.
 *      (예전에는 무응답 한 번이 행동 가중치 전체(0.75)로 커져 바로 '위험'이 되는 문제가 있었습니다)
 *    - 상황 위험도에는 넘기지 않습니다. 새벽이라는 이유만으로 경고가 울리지 않게 하기 위함입니다.
 *    - 하한: 연속 운전 2시간 이상이면 최소 "주의", 운전자가 "졸려"라고 하면 최소 "경고",
 *      차량이 운전자 주의 경고(DAW)를 띄우면 "위험".
 *
 * 2) 화면에는 센서 위험도와 그 단계([Result.level])를 그대로 보여줍니다.
 *    게임·대화로 쌓은 각성 확인 보너스는 화면 숫자를 낮추지 않고, 말 걸기 판단용 단계([Result.promptLevel])에만 씁니다.
 *    (깨어 있음이 확인되면 달토끼가 덜 말을 건다 = 성가시지 않게. 실제 졸음 위험이 줄었다고 보여주지 않음)
 *    - 안전 한도: 센서 위험도가 '위험'(0.75) 이상이면 보너스 무효 /
 *      급꺾음 반복·2시간 연속 운전·차량 경고면 '경고'(0.50) 아래로 못 내림 / 위 하한도 못 뚫음
 *
 * 단계가 내려갈 때는 기준보다 조금 더 내려가야 바뀝니다(깜빡임 방지).
 */
class FatigueEstimator(private val cfg: FatigueConfig) {

    data class Result(
        val raw: Float,
        /** 졸음 위험도(화면 표시용) */
        val sensor: Float,
        /** 말 걸기 판단용 위험도 = 센서 − 적용된 보너스 */
        val score: Float,
        /** 졸음 위험 단계(센서 기준) */
        val level: FatigueLevel,
        val bonusApplied: Float,
        val bonusBlocked: Boolean,
        /** 말 걸기 판단용 단계(보너스 반영) */
        val promptLevel: FatigueLevel = level,
    )

    private var smoothed = 0f
    private var lastT = -1L

    /** 졸음 위험 단계(센서 기준) */
    var level: FatigueLevel = FatigueLevel.NORMAL
        private set

    /** 말 걸기 판단용 단계 */
    var promptLevel: FatigueLevel = FatigueLevel.NORMAL
        private set

    fun reset() {
        smoothed = 0f
        lastT = -1L
        level = FatigueLevel.NORMAL
        promptLevel = FatigueLevel.NORMAL
    }

    /** 행동 신호 합계(0~0.75). 핸들 유무에 따라 합치는 방식이 다릅니다. */
    internal fun behavior(steering: Float?, lane: Float?, dialog: Pair<Float, Float>?): Float {
        val behaviorTotalWeight = cfg.weightSteering + cfg.weightLane + cfg.weightDialog
        if (steering != null) {
            var wSum = cfg.weightSteering
            var vSum = cfg.weightSteering * steering
            lane?.let { wSum += cfg.weightLane; vSum += cfg.weightLane * it }
            dialog?.let { (value, confidence) ->
                val w = cfg.weightDialog * confidence
                wSum += w; vSum += w * value
            }
            return (vSum / wSum) * behaviorTotalWeight
        }
        // 핸들 신호 없음: 드문드문 생기는 신호는 자기 몫만큼만
        var sparse = 0f
        lane?.let { sparse += cfg.weightLane * cfg.sparseBoost * it }
        dialog?.let { (value, confidence) -> sparse += cfg.weightDialog * cfg.sparseBoost * value * confidence }
        return sparse.coerceIn(0f, cfg.sparseBehaviorMax)
    }

    fun update(
        t: Long,
        context: Float,
        steering: Float?,
        lane: Float?,
        dialog: Pair<Float, Float>?,
        continuousDriveMin: Float,
        selfReportActive: Boolean,
        bonus: Float = 0f,
        strongSignal: Boolean = false,
        vehicleWarning: Boolean = false,
        /** 이만큼 연속으로 달리면 '주의' 하한 (기본 120분, 새벽·수면 부족이면 더 짧게 — RestPlanner) */
        longDriveFloorMin: Float = cfg.longDriveFloorMin,
    ): Result {
        var raw = (cfg.weightContext * context + behavior(steering, lane, dialog)).coerceIn(0f, 1f)

        // 평활화
        val dt = if (lastT < 0) 0L else (t - lastT).coerceAtLeast(0L)
        lastT = t
        val alpha = if (dt == 0L && smoothed == 0f) 1f else (1f - exp(-dt.toFloat() / cfg.smoothingTauMs))
        smoothed += alpha * (raw - smoothed)

        // 규칙 하한
        val longDrive = continuousDriveMin >= longDriveFloorMin
        var floor = 0f
        if (longDrive) floor = maxOf(floor, cfg.longDriveFloorScore)
        if (selfReportActive) floor = maxOf(floor, cfg.selfReportFloorScore)
        if (vehicleWarning) floor = maxOf(floor, cfg.vehicleWarningFloorScore)
        raw = maxOf(raw, floor)
        val sensor = maxOf(smoothed, floor).coerceIn(0f, 1f)

        // 각성 확인 보너스(말 걸기 판단용) + 안전 한도
        val b = bonus.coerceIn(0f, cfg.maxAlertnessBonus)
        val locked = sensor >= cfg.dangerThreshold
        var net = when {
            locked -> sensor
            strongSignal || longDrive || vehicleWarning -> maxOf(sensor - b, minOf(sensor, cfg.warningThreshold))
            else -> sensor - b
        }
        net = maxOf(net, floor).coerceIn(0f, 1f)
        val applied = (sensor - net).coerceAtLeast(0f)
        val blocked = locked || (b - applied > 0.005f)

        level = levelFor(sensor, level)
        promptLevel = levelFor(net, promptLevel)
        return Result(raw, sensor, net, level, applied, blocked, promptLevel)
    }

    private fun threshold(l: FatigueLevel): Float = when (l) {
        FatigueLevel.NORMAL -> 0f
        FatigueLevel.CAUTION -> cfg.cautionThreshold
        FatigueLevel.WARNING -> cfg.warningThreshold
        FatigueLevel.DANGER -> cfg.dangerThreshold
    }

    private fun levelFor(score: Float, current: FatigueLevel): FatigueLevel {
        val target = when {
            score >= cfg.dangerThreshold -> FatigueLevel.DANGER
            score >= cfg.warningThreshold -> FatigueLevel.WARNING
            score >= cfg.cautionThreshold -> FatigueLevel.CAUTION
            else -> FatigueLevel.NORMAL
        }
        if (target.ordinal >= current.ordinal) return target
        return if (score < threshold(current) - cfg.hysteresis) target else current
    }
}
