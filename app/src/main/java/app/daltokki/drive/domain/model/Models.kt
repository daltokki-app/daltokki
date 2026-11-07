package app.daltokki.drive.domain.model

/** 기어 상태. 수동 단수(1~9단)는 DRIVE로 취급합니다. */
enum class Gear { PARK, REVERSE, NEUTRAL, DRIVE, UNKNOWN }

/**
 * 한 시점의 차량 데이터 묶음.
 * 시뮬레이터와 실차(Car API + Pleos Vehicle SDK)가 모두 이 형태로 데이터를 넘깁니다.
 * 없는 값은 null(모름)로 두고, 엔진은 있는 값만 씁니다.
 */
data class VehicleSnapshot(
    /** 데이터 시각(epoch ms). 시뮬레이션에서는 가상 시각입니다. */
    val timestampMs: Long,
    val speedKmh: Float,
    val gear: Gear,
    /** 핸들 각도(도). 받을 수 없으면 null. (Vehicle SDK: SteeringWheel) */
    val steeringAngleDeg: Float?,
    /** 차로 유지 보조(LKA/LFA)가 켜져 있으면 true. 이때 핸들 신호는 쓰지 않습니다. (Vehicle SDK: Safety 경고등) */
    val laneAssistActive: Boolean = false,
    /** 차선 이탈 경고·차로 유지 개입 중이면 true. 모르면 null. (Vehicle SDK: Safety 경고등 ELK/LKA 깜빡임) */
    val laneDepartureWarning: Boolean? = null,
    /** 방향지시등이 켜져 있으면 true. 차선 변경은 졸음 신호에서 뺍니다. 모르면 null. (Vehicle SDK: TurnSignal) */
    val turnSignalOn: Boolean? = null,
    /** 차량의 운전자 주의 경고(DAW)가 켜져 있으면 true. 모르면 null. (Vehicle SDK: Safety 경고등) */
    val driverAttentionWarning: Boolean? = null,
    /** 공조 내기순환이 켜져 있으면 true. 모르면 null. (Vehicle SDK: HVAC) */
    val recirculationOn: Boolean? = null,
    /** 브레이크 페달을 밟고 있으면 true. 모르면 null. (Vehicle SDK: Brake) — 말 걸기 보류에만 씀 */
    val brakePressed: Boolean? = null,
    /** 탑승 인원(좌석 착좌 수, 운전자 포함). 모르면 null. (Vehicle SDK: Seat) — 실내 CO₂ 추정용 */
    val occupants: Int? = null,
    /** 공조 풍량 단계. 모르면 null. (Vehicle SDK: HVAC) — 실내 CO₂ 추정용 */
    val fanSpeed: Int? = null,
)

/** 운전에 집중해야 하는 순간. 이때는 달토끼가 새로 말을 걸지 않습니다. */
enum class BusyReason(val label: String) {
    LANE_CHANGE("차선 변경"),
    BRAKING("제동"),
    TURNING("큰 핸들 조작"),
}

enum class FatigueLevel(val label: String) {
    NORMAL("좋아요"),
    CAUTION("주의"),
    WARNING("경고"),
    DANGER("위험"),
}

enum class DriveState(val label: String) {
    PARKED("주차"),
    CALIBRATING("기준 학습 중"),
    MONITORING("주행 중"),
    RESTING("휴식 중");

    val isDriving: Boolean get() = this == CALIBRATING || this == MONITORING
}

/** 상태 변화 이벤트. 대화 코디네이터가 인사, 휴식 안내, 운행 리포트 등에 사용합니다. */
sealed interface DriveEvent {
    data object TripStarted : DriveEvent
    data object CalibrationDone : DriveEvent
    data object RestStarted : DriveEvent
    /** 충분히(기본 10분) 쉬어서 피로 누적이 초기화됨. */
    data object RestCompleted : DriveEvent
    data class DrivingResumed(val restedEnough: Boolean) : DriveEvent
    data object TripEnded : DriveEvent
    /** 차량이 운전자 주의 경고(DAW)를 띄움 */
    data object VehicleAttentionWarning : DriveEvent
}

/** 핸들·속도에서 뽑은 운전 행동 지표. */
data class DrivingFeatures(
    /** 1분당 핸들 반전 횟수. 유효 구간이 짧으면 null. */
    val reversalRatePerMin: Float?,
    /** 최근 5분 동안의 "핸들 무조작 후 급꺾음" 횟수. */
    val lapseEventsRecent: Int,
    /** 속도 표준편차(km/h). */
    val speedStdKmh: Float?,
    /** 최근 1분 중 판정에 쓸 수 있었던 구간 비율(0~1). */
    val validCoverage: Float,
    /** 핸들 각도 데이터가 들어온 적이 있는지. */
    val steeringAvailable: Boolean,
    /** 최근 1분 유효 구간 평균 속도(km/h). 운전 습관을 속도대별로 나눌 때 씀. 모르면 null. */
    val meanSpeedKmh: Float? = null,
) {
    companion object {
        val EMPTY = DrivingFeatures(null, 0, null, 0f, false)
    }
}

/** 피로 추정 결과. */
data class FatigueAssessment(
    val timestampMs: Long,
    val driveState: DriveState,
    /**
     * 말 걸기 판단용 위험도(0~1) = 센서 위험도 − 실제 적용된 각성 보너스.
     * 화면에는 보여주지 않습니다(화면은 [sensorScore]). 깨어 있음이 확인되면 달토끼가 덜 말을 걸게 하는 용도입니다.
     */
    val score: Float,
    /** 졸음 위험도(0~1). 핸들·시간·상황·대화 반응으로만 계산. 화면 게이지에 이 값을 씁니다. */
    val sensorScore: Float,
    /** 지금 쌓여 있는 각성 확인 보너스(0~0.15). 빠르고 정확한 대답·게임 정답으로 쌓이고 시간이 지나면 사라짐. */
    val bonus: Float,
    /** 보너스 중 실제로 말 걸기 판단에 반영된 양. */
    val bonusApplied: Float,
    /** 졸음 신호가 강해 보너스가 막혔는지(위험 단계, 급꺾음 반복, 2시간 연속 운전, 차량 경고). */
    val bonusBlocked: Boolean,
    /** 평활화 전 점수(0~1). */
    val rawScore: Float,
    /** 졸음 위험 단계(센서 위험도 기준). 화면 표시·위험 경고·운행 리포트에 씁니다. */
    val level: FatigueLevel,
    /** 상황 위험도: 연속 운전 시간, 시간대, 단조로움 (0~1). */
    val contextRisk: Float,
    /** 핸들 조작 이상도 (0~1). 판단 불가 시 null. */
    val steeringAnomaly: Float?,
    /** 차선 이탈 위험도 (0~1). 데이터 없으면 null. */
    val laneRisk: Float?,
    /** 대화 반응 저하 (0~1). 측정 전이면 null. */
    val dialogImpairment: Float?,
    val continuousDriveMin: Float,
    val baselineReady: Boolean,
    val calibrationProgress: Float,
    val features: DrivingFeatures,
    val selfReportActive: Boolean,
    val events: List<DriveEvent>,
    /** 말 걸기 판단용 단계([score] 기준). 깨어 있음이 확인되면 [level]보다 낮을 수 있습니다. */
    val promptLevel: FatigueLevel = level,
    /** 차량 운전자 주의 경고(DAW)를 반영 중인지 */
    val vehicleWarningActive: Boolean = false,
    /** 내기순환이 연속으로 켜져 있던 시간(분). 데이터가 없거나 꺼져 있으면 null. */
    val recirculationMin: Float? = null,
    /** 지금 운전에 집중해야 하는 순간이면 그 이유 (차선 변경·제동·큰 핸들 조작 + 3초) */
    val driverBusy: BusyReason? = null,
    /** 판단 근거 (중요한 순서, 최대 3개). 예: "핸들 무조작 후 급꺾음 2회(5분)", "대답이 평소의 2.1배" */
    val reasons: List<String> = emptyList(),
    /** 권장 휴식 간격(분): 기본 120, 새벽·수면 부족이면 더 짧게 (졸음 예보) */
    val restIntervalMin: Int = 120,
    /** 다음 휴식 권장까지 남은 분 (지났으면 음수). 주행 중이 아니면 null */
    val restDueInMin: Float? = null,
    /** 출발 전에 알려 준 어젯밤 수면 시간 */
    val sleepHours: Float? = null,
    /** 실내 CO₂ 추정(ppm). 내기순환 여부를 모르면 null (보조 지표) */
    val co2Ppm: Float? = null,
)

/** 한 번의 운행 기록(운행 리포트용). */
data class TripStats(
    val tripStartMs: Long = -1L,
    val drivingMs: Long = 0L,
    val maxLevel: FatigueLevel = FatigueLevel.NORMAL,
    val interventions: Int = 0,
    val alerts: Int = 0,
    val rests: Int = 0,
    val responses: Int = 0,
    val misses: Int = 0,
    val totalLatencyMs: Long = 0L,
    val lapseEvents: Int = 0,
    /** 차량 운전자 주의 경고(DAW) 횟수 */
    val vehicleWarnings: Int = 0,
    /** 이번 운행에서 처음 '경고'(앱)가 된 시각. 없으면 -1 */
    val firstWarningMs: Long = -1L,
    /** 이번 운행에서 처음 차량 주의 경고(DAW)가 켜진 시각. 없으면 -1 */
    val firstVehicleWarningMs: Long = -1L,
    /** 반응 게임 성공 반응 시간(ms), 순서대로 */
    val reactionMs: List<Long> = emptyList(),
    /** 반응 게임에서 놓친 횟수 */
    val reactionMisses: Int = 0,
    /** 바쁜 순간·통화 중이라 말 걸기를 미룬 횟수 */
    val deferrals: Int = 0,
    /** 커피냅 전·후 반응 평균(ms) */
    val napBeforeMs: Long? = null,
    val napAfterMs: Long? = null,
) {
    val avgLatencyMs: Long? get() = if (responses > 0) totalLatencyMs / responses else null

    /** 차량 경고보다 몇 분 먼저 '경고'로 알아챘는지 (둘 다 있고 앱이 먼저일 때만) */
    val leadMin: Float?
        get() = if (firstWarningMs >= 0 && firstVehicleWarningMs >= 0 && firstVehicleWarningMs > firstWarningMs) {
            (firstVehicleWarningMs - firstWarningMs) / 60_000f
        } else null
}
