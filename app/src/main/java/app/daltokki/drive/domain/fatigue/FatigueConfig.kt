package app.daltokki.drive.domain.fatigue

/**
 * 피로 추정에 쓰는 모든 기준값.
 * 실차 데이터가 쌓이면 이 값들을 조정합니다. (현재 값은 시뮬레이션 기준 시작값)
 */
data class FatigueConfig(
    // ── 핸들 지표 ──
    /** 핸들 반전 빈도·속도 편차를 계산하는 창 길이 */
    val sampleWindowMs: Long = 60_000L,
    /** 핸들 무조작 후 급꺾음 횟수를 세는 창 길이 */
    val lapseWindowMs: Long = 5 * 60_000L,
    /** 이 속도 이상에서만 핸들 지표를 계산(시내 주행 제외) */
    val minValidSpeedKmh: Float = 60f,
    /** 핸들 각도가 이 값 이하일 때만 계산(커브 제외) */
    val maxStraightAngleDeg: Float = 20f,
    /** 이만큼 이상 되돌려야 "반전" 1회로 셈 */
    val reversalGapDeg: Float = 0.5f,
    /** 이 범위 안에 머무르면 "무조작"으로 봄 */
    val stillToleranceDeg: Float = 0.3f,
    /** 무조작이 이 시간 이상 이어진 뒤 */
    val lapseMinHoldMs: Long = 3_000L,
    /** 이 각속도 이상으로 꺾으면 "급꺾음" */
    val correctionRateDegPerSec: Float = 12f,
    /** 무조작이 끝난 뒤 급꺾음을 기다리는 시간 */
    val correctionWatchMs: Long = 1_000L,
    /** 반전 빈도를 계산하려면 창 안에 필요한 최소 유효 시간 */
    val minValidMsForRate: Long = 20_000L,
    /** 방향지시등이 꺼진 뒤에도 이 시간 동안은 차선 변경으로 보고 핸들 지표에서 뺍니다 */
    val turnSignalHoldMs: Long = 3_000L,

    // ── 개인 기준선 ──
    /** 유효 주행이 이만큼 쌓이면 기준선 완성 */
    val calibrationValidMs: Long = 15 * 60_000L,
    val calibrationRecordEveryMs: Long = 10_000L,

    // ── 상태 전환 ──
    /** 이 시간 이상 쉬면 피로 누적 초기화 */
    val restResetMs: Long = 10 * 60_000L,
    /** 이 시간 이상 주차하면 운행 종료 */
    val tripEndParkMs: Long = 30 * 60_000L,

    // ── 대화 반응 ──
    /** 짧은 질문("응/아니")에 대한 기본 응답 시간. 실제 대답으로 개인 기준을 다시 잡습니다. */
    val defaultBaselineLatencyMs: Long = 1_500L,
    /**
     * 게임(끝말잇기·퀴즈) 대답의 기준 응답 시간. 단어를 떠올리는 시간은 졸음이 아니라 정상적인 생각 시간이라
     * 짧은 질문과 따로 봅니다.
     */
    val gameBaselineLatencyMs: Long = 4_000L,
    /** 게임에서 시간 초과는 생각이 안 난 것일 수도 있어 무응답(1.0)보다 약하게 봅니다. */
    val gameMissImpairment: Float = 0.6f,
    /**
     * 반응 게임("달!" → "빛!")의 기준 반응 시간. 생각할 것이 없는 단순 반응이라 짧은 질문보다 빠릅니다.
     * (졸음 연구의 반응 시간 검사(PVT)를 음성으로 바꾼 것. 음성 인식 시작 지연을 감안해 1.2초)
     */
    val reactionBaselineLatencyMs: Long = 1_200L,
    /** 반응 게임에서 놓침은 졸음의 강한 신호지만, 음성 인식 실패일 수도 있어 1.0보다 조금 약하게 봅니다. */
    val reactionMissImpairment: Float = 0.8f,
    val dialogWindowMs: Long = 20 * 60_000L,
    val dialogHalfLifeMs: Long = 10 * 60_000L,

    // ── 점수 가중치 ──
    val weightContext: Float = 0.25f,
    val weightSteering: Float = 0.45f,
    val weightLane: Float = 0.10f,
    val weightDialog: Float = 0.20f,
    /**
     * 핸들 신호가 없을 때(저속·커브·핸들 데이터 없음) 대화·차선 신호만으로 올릴 수 있는 최대치.
     * 드문드문 생기는 신호 한두 개로 '위험'까지 튀지 않게 막습니다.
     * (없는 핸들 가중치를 이 신호들에 모두 몰아주지 않고, 각 신호의 가중치 × [sparseBoost]까지만 씁니다)
     */
    val sparseBehaviorMax: Float = 0.35f,
    val sparseBoost: Float = 1.5f,
    /** 점수 평활화 시간 상수 */
    val smoothingTauMs: Long = 15_000L,

    // ── 단계 기준 ──
    val cautionThreshold: Float = 0.30f,
    val warningThreshold: Float = 0.50f,
    val dangerThreshold: Float = 0.75f,
    val hysteresis: Float = 0.05f,

    // ── 각성 보너스 (대화·게임으로 위험도를 낮추는 양) ──
    /** 보너스 최대치 (위험도 15점) */
    val maxAlertnessBonus: Float = 0.15f,
    /** 1분마다 사라지는 양 (10분이면 최대치가 모두 사라짐) */
    val bonusDecayPerMin: Float = 0.015f,
    /** 최근 5분 급꺾음이 이 횟수 이상이면 보너스로 '경고' 아래로 못 내림 */
    val lockLapseCount: Int = 2,

    // ── 규칙 ──
    /** 이 시간 이상 연속 운전하면 최소 "주의" (2시간마다 휴식 권고) */
    val longDriveFloorMin: Float = 120f,
    val longDriveFloorScore: Float = 0.35f,
    /** 운전자가 "졸려"라고 하면 이 점수 이상으로 유지 */
    val selfReportFloorScore: Float = 0.55f,
    val selfReportDurationMs: Long = 10 * 60_000L,
    /** 차량이 운전자 주의 경고(DAW)를 띄우면 이 점수 이상('위험')으로 유지 */
    val vehicleWarningFloorScore: Float = 0.76f,
    val vehicleWarningDurationMs: Long = 5 * 60_000L,

    // ── 바쁜 순간(말 걸기 보류) ──
    /** 바쁜 순간이 끝난 뒤에도 이 시간 동안은 말을 시작하지 않음 */
    val busyHoldMs: Long = 3_000L,
    /** 이 속도 이상에서 브레이크를 밟으면 바쁜 순간 */
    val busyBrakeMinSpeedKmh: Float = 30f,
    /** 초당 이만큼 이상 감속하면 급제동 */
    val hardDecelKmhPerSec: Float = 8f,
    /** 핸들을 이 각도 이상 돌리고 있으면(교차로 회전 등) 바쁜 순간 */
    val busyAngleDeg: Float = 90f,
    /** 핸들을 이 각속도 이상으로 돌리면 바쁜 순간 (급꺾음 판정 12°/s보다 커서 졸음 신호와 겹치지 않음) */
    val busySteerRateDegPerSec: Float = 30f,

    // ── 환기 제안 ──
    /** 주행 중 내기순환이 이 시간 이상 이어지고 '주의' 이상이면 외기 전환을 제안 */
    val recirculationSuggestMin: Float = 20f,
)
