package app.daltokki.drive.domain.sim

import app.daltokki.drive.domain.model.Gear

/**
 * 시뮬레이션 주행의 한 구간.
 * 핸들은 "깨어 있는 운전자의 미세 보정"을 사인파로 흉내 내고(진폭·주기),
 * 졸음 구간에서는 일정 간격으로 "핸들 무조작 → 급꺾음"을 넣습니다.
 */
data class ScenarioPhase(
    val label: String,
    val durationSec: Int,
    val gear: Gear = Gear.DRIVE,
    val speedKmh: Float = 0f,
    val speedJitterKmh: Float = 1.2f,
    /** 미세 보정 진폭(도). 또렷할수록 큼. */
    val steerAmpDeg: Float = 1.5f,
    /** 미세 보정 주기(Hz). 또렷할수록 빠름. */
    val steerFreqHz: Float = 0.35f,
    /** 커브(평균 핸들 각도) */
    val curveDeg: Float = 0f,
    /** 핸들 무조작 후 급꺾음 평균 간격(초). null이면 없음. */
    val lapseEverySec: Int? = null,
    /** 급꺾음 때 차선 이탈 경고도 발생시킬지 */
    val laneWarnOnLapse: Boolean = false,
    /** 차로 유지 보조 사용 중 */
    val laneAssist: Boolean = false,
    /** 방향지시등을 켜고 차선 변경하는 간격(초). null이면 없음. 졸음 신호에서 빠져야 함 */
    val laneChangeEverySec: Int? = null,
    /** 구간 시작 후 이 시점(초)에 차량 운전자 주의 경고(DAW)가 켜짐. null이면 없음 */
    val vehicleWarningAtSec: Int? = null,
    /** 공조 내기순환 켜짐 */
    val recirculation: Boolean = false,
)

data class DriveScenario(
    val id: String,
    val title: String,
    val description: String,
    val startHour: Int,
    val startMinute: Int,
    val phases: List<ScenarioPhase>,
    val restAreaName: String,
    val restAreaKm: Int,
    val hasAdas: Boolean = true,
    /**
     * 공공데이터의 실제 휴게소·쉼터 이름(원본 표기, 예: "망향(부산)").
     * 있으면 시연에서도 실제 휴게소로 안내하고, 쉬면 그곳 도장을 받습니다.
     */
    val restStopId: String? = null,
    /** 탄 사람 수 (실내 CO₂ 추정용) */
    val occupants: Int = 1,
) {
    val totalSec: Int get() = phases.sumOf { it.durationSec }
}

object Scenarios {

    private const val MIN = 60

    /** 졸음이 점점 심해지다 휴게소에서 쉬고 다시 출발하는 시나리오(시연용 메인) */
    val nightHighway = DriveScenario(
        id = "night_highway",
        title = "새벽 고속도로 · 졸음 발생",
        description = "새벽 2시 40분 출발. 또렷함 → 피로 누적(내기순환 · 실내 CO₂ 추정 상승 → 외기 전환 제안) → 졸음 운전(차량 주의 경고) → 휴게소 휴식(커피냅 15분) → 재출발",
        startHour = 2, startMinute = 40,
        restAreaName = "망향휴게소", restAreaKm = 12, restStopId = "망향(부산)",
        phases = listOf(
            ScenarioPhase("출발 준비", 20, gear = Gear.PARK),
            ScenarioPhase("시내 구간", 60, speedKmh = 45f, steerAmpDeg = 3f, steerFreqHz = 0.3f, curveDeg = 25f),
            ScenarioPhase("고속도로 · 또렷함", 18 * MIN, speedKmh = 100f, recirculation = true),
            ScenarioPhase(
                "피로 누적", 12 * MIN, speedKmh = 100f,
                steerAmpDeg = 0.8f, steerFreqHz = 0.22f, lapseEverySec = 200, recirculation = true,
            ),
            ScenarioPhase(
                "졸음 운전", 12 * MIN, speedKmh = 100f, speedJitterKmh = 3f,
                steerAmpDeg = 0.35f, steerFreqHz = 0.13f, lapseEverySec = 70, laneWarnOnLapse = true,
                vehicleWarningAtSec = 6 * MIN, recirculation = true,
            ),
            ScenarioPhase("휴게소 진입", 60, speedKmh = 40f, steerAmpDeg = 2f, curveDeg = 22f),
            ScenarioPhase("휴게소 휴식", 18 * MIN, gear = Gear.PARK),
            ScenarioPhase("휴식 후 주행", 10 * MIN, speedKmh = 100f),
            ScenarioPhase("도착 · 주차", 20, gear = Gear.PARK),
        ),
    )

    /** 오탐(괜히 경고하는 것)이 없는지 보여주는 정상 주행 시나리오 */
    val dayNormal = DriveScenario(
        id = "day_normal",
        title = "오전 국도 · 정상 주행",
        description = "오전 10시 출발. 커브·직선·차선 변경(방향지시등)이 섞인 정상 주행 40분. 달토끼가 괜히 말을 걸지 않아야 정상",
        startHour = 10, startMinute = 0,
        restAreaName = "오창졸음쉼터", restAreaKm = 8, restStopId = "오창",
        phases = listOf(
            ScenarioPhase("출발 준비", 20, gear = Gear.PARK),
            ScenarioPhase("직선 구간", 15 * MIN, speedKmh = 80f, steerAmpDeg = 1.6f, steerFreqHz = 0.38f, laneChangeEverySec = 150),
            ScenarioPhase("굽은 길", 5 * MIN, speedKmh = 70f, steerAmpDeg = 2f, curveDeg = 28f),
            ScenarioPhase(
                "직선 구간 2", 15 * MIN, speedKmh = 85f, steerAmpDeg = 1.4f, steerFreqHz = 0.36f, laneChangeEverySec = 150,
            ),
            ScenarioPhase("시내 진입", 5 * MIN, speedKmh = 40f, steerAmpDeg = 3f, curveDeg = 30f),
            ScenarioPhase("도착 · 주차", 20, gear = Gear.PARK),
        ),
    )

    /** 2시간 넘게 쉬지 않고 달리는 장거리. "2시간마다 휴식" 규칙과 오후 졸음 시간대를 보여줍니다. */
    val longTrip = DriveScenario(
        id = "long_trip",
        title = "오후 장거리 · 2시간 연속",
        description = "오후 1시 출발. 2시간 넘게 쉬지 않고 주행. 운전 습관은 정상이어도 휴식을 권하는지 확인",
        startHour = 13, startMinute = 0,
        restAreaName = "칠곡휴게소", restAreaKm = 18, restStopId = "칠곡(부산)",
        hasAdas = false,
        phases = listOf(
            ScenarioPhase("출발 준비", 20, gear = Gear.PARK),
            ScenarioPhase("고속도로 1", 60 * MIN, speedKmh = 105f, steerAmpDeg = 1.5f, steerFreqHz = 0.34f),
            ScenarioPhase("고속도로 2", 45 * MIN, speedKmh = 100f, steerAmpDeg = 1.2f, steerFreqHz = 0.3f),
            ScenarioPhase(
                "고속도로 3 · 지루함", 30 * MIN, speedKmh = 100f,
                steerAmpDeg = 1.0f, steerFreqHz = 0.27f, lapseEverySec = 420,
            ),
            ScenarioPhase("도착 · 주차", 20, gear = Gear.PARK),
        ),
    )

    val all = listOf(nightHighway, dayNormal, longTrip)

    fun byId(id: String): DriveScenario = all.firstOrNull { it.id == id } ?: nightHighway
}
