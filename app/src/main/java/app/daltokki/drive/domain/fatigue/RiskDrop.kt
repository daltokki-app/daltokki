package app.daltokki.drive.domain.fatigue

/**
 * 어떤 행동 뒤에 졸음 위험도가 실제로 내려갔을 때 화면에 "-7 · 빠른 대답"을 보여 주기 위한 기록.
 *
 * @property seq 표시 번호 (같은 행동이라도 더 내려가면 새 번호, 화면은 숫자만 바꿔 계속 보여 줌)
 * @property actionSeq 어떤 행동 때문인지 (행동이 바뀌면 새 연출)
 * @property amount 그 행동 이후 내려간 위험도 합계 (0~100 점수)
 * @property label "빠른 대답" · "외기 전환" · "휴식"
 */
data class RiskDrop(val seq: Int, val actionSeq: Int, val amount: Int, val label: String)

/** 위험도를 낮출 수 있는 행동 종류와, 그 행동 덕분이라고 볼 수 있는 시간(시나리오 시각 기준) */
enum class DropCause(val windowMs: Long) {
    /** 또렷한 대답 → '대화 반응 저하' 신호가 내려감 */
    ANSWER(90_000L),
    /** 외기 전환 → 실내 CO₂ 추정치가 내려감 (천천히) */
    AIR(6 * 60_000L),
    /** 휴식 10분(또는 커피냅) → 피로 누적 초기화. 다시 달리기 시작할 때 보여 줌 */
    REST(3 * 60 * 60_000L),
}

/**
 * 점수로 위험도를 낮추지 않습니다. **실제 신호가 좋아져서 숫자가 내려갔을 때만** 그 행동 덕분이라고 표시합니다.
 *
 * - 빠른 대답: 대답 뒤 90초 안에, '대화 반응 저하' 값이 실제로 줄었고 위험도가 2 이상 내려갔을 때
 * - 외기 전환: 6분 안에, 실내 CO₂ 추정치가 30ppm 이상 줄었고 위험도가 2 이상 내려갔을 때
 * - 휴식: 다시 달리기 시작해 숫자가 보이면 한 번 ("마지막 주행 위험도 → 지금")
 * - 처음 2 이상 내려가면 표시하고, 더 내려가면 합계를 늘려 계속 표시. 다시 오르면 표시하지 않음
 * - 주행 중(숫자가 보일 때)에만 표시. 휴식 결과는 다른 행동이 와도 먼저 보여 줌
 */
class RiskDropTracker {

    private class Pending(
        val seq: Int,
        val cause: DropCause,
        val label: String,
        val t0: Long,
        val risk0: Int,
        val component0: Float?,
    )

    private var pending: Pending? = null
    private var actionSeq = 0
    private var dropSeq = 0
    private var shown = 0
    private var lastDrivingRisk: Int? = null
    private var visibleSince = -1L

    /** 대답·외기 전환 직후 (그 순간의 위험도와 관련 신호 값) */
    fun onAction(t: Long, cause: DropCause, label: String, risk: Int?, component: Float?) {
        if (risk == null) return
        val p = pending
        // 아직 보여 주지 못한 휴식 결과는 지키기
        if (p != null && p.cause == DropCause.REST && cause != DropCause.REST && shown == 0) return
        pending = Pending(++actionSeq, cause, label, t, risk, component)
        shown = 0
    }

    /** 휴식 완료: 마지막 주행 중 위험도와 비교해, 다시 달릴 때 한 번 보여 줌 */
    fun onRest(t: Long, label: String = "휴식") {
        val before = lastDrivingRisk ?: return
        pending = Pending(++actionSeq, DropCause.REST, label, t, before, null)
        shown = 0
    }

    /**
     * 판단이 새로 나올 때마다.
     * @param visible 주행 중이고 위험도 숫자가 화면에 보일 때 (습관 익히는 중·정차 중이면 false)
     * @param dialog '대화 반응 저하' 값 (없으면 null)
     * @param co2Ppm 실내 CO₂ 추정치 (모르면 null)
     * @return 새로 보여 줄 표시. 없으면 null
     */
    fun onRisk(t: Long, visible: Boolean, risk: Int, dialog: Float?, co2Ppm: Float?): RiskDrop? {
        if (!visible) {
            visibleSince = -1L
            return null
        }
        if (visibleSince < 0) visibleSince = t
        val p = pending
        val result = if (p == null) {
            null
        } else if (t - p.t0 > p.cause.windowMs) {
            pending = null
            null
        } else {
            val total = p.risk0 - risk
            val improved = when (p.cause) {
                DropCause.ANSWER -> dialog != null && p.component0 != null && dialog < p.component0 - 0.01f
                DropCause.AIR -> co2Ppm != null && p.component0 != null && co2Ppm < p.component0 - 30f
                // 다시 달리기 시작하고 숫자가 화면에 자리 잡은 뒤(5초)
                DropCause.REST -> t - visibleSince >= REST_SHOW_AFTER_MS
            }
            val need = if (shown == 0) MIN_FIRST else shown + 1
            if (improved && total >= need) {
                shown = total
                if (p.cause == DropCause.REST) pending = null
                RiskDrop(++dropSeq, p.seq, total, p.label)
            } else if (p.cause == DropCause.REST && improved) {
                // 휴식 뒤 숫자가 오히려 높으면 보여 줄 것 없음
                pending = null
                null
            } else {
                null
            }
        }
        lastDrivingRisk = risk
        return result
    }

    /** 새 운행 */
    fun reset() {
        pending = null
        shown = 0
        lastDrivingRisk = null
        visibleSince = -1L
    }

    companion object {
        const val MIN_FIRST = 2
        const val REST_SHOW_AFTER_MS = 5_000L
    }
}
