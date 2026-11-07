package app.daltokki.drive.data.vehicle

import app.daltokki.drive.engine.FreshAirResult

/**
 * Pleos Vehicle SDK에서 받는 차량 신호.
 * (속도·기어는 AAOS 표준 Car API로 받고, 나머지는 이 인터페이스로 받습니다)
 *
 * 실제 구현: `app/src/pleos/java/.../platform/PleosBridge.kt` 의 PleosVehicleSignals
 *  - 핸들 각도:        SteeringWheel.registerSteeringWheelAngle
 *  - 방향지시등:       TurnSignal.registerTurnSignalState
 *  - 브레이크 페달:    Brake.registerBrakePedalStatus
 *  - 차로 유지 보조·차선 개입·운전자 주의 경고: Safety.registerTelltales (경고등 LKA/LFA/ELK/DRIVER_ATTENTION)
 *  - 내기순환:         HVAC.registerHvacRecirculationState
 *  - (14차) 풍량:      HVAC.registerHvacFanSpeed · 탑승 인원: Seat 착좌 · 외기 전환: HVAC.setHvacRecirculation(false)
 *
 * gradle.properties에서 pleosSdk=false로 빌드하면 [NoVehicleSignals]를 씁니다(SDK 없이도 빌드·실행 가능).
 * 값을 모르면 null을 돌려주고, 엔진은 있는 값만 씁니다.
 */
interface VehicleSignalsProvider {
    /** 사람이 읽을 연결 상태 (예: "핸들 OK · 방향지시등 OK") */
    val status: String
    fun start()
    fun stop()
    /** 최신 핸들 각도(도). 없으면 null. */
    fun steeringAngleDeg(): Float?
    /** 차로 유지 보조(LKA/LFA)가 켜져 있는지. 켜져 있으면 차가 핸들을 보정하므로 핸들 신호를 쓰지 않습니다. */
    fun laneAssistActive(): Boolean
    /** 차선 이탈 경고·차로 유지 개입 중인지. 모르면 null. */
    fun laneInterventionActive(): Boolean?
    /** 방향지시등 켜짐. 모르면 null. */
    fun turnSignalOn(): Boolean?
    /** 차량 운전자 주의 경고(DAW) 켜짐. 모르면 null. */
    fun driverAttentionWarning(): Boolean?
    /** 공조 내기순환 켜짐. 모르면 null. */
    fun recirculationOn(): Boolean?
    /** 브레이크 페달 밟음. 모르면 null. (말 걸기 보류용) */
    fun brakePressed(): Boolean?

    // ── 14차 (기본값이 있어 구현하지 않아도 됨) ──
    /** 탄 사람 수 (운전석 + 착좌 감지된 좌석). 모르면 null → 실내 CO₂ 추정은 1명으로 */
    fun occupants(): Int? = null
    /** 공조 풍량 단계. 모르면 null */
    fun fanSpeed(): Int? = null
    /** 외기로 전환 (내기순환 끔). 최소 차량 제어: 운전자가 "응"이라고 한 뒤에만 부름 */
    suspend fun setFreshAir(): FreshAirResult = FreshAirResult.UNSUPPORTED
    /** 운전석 공조 설정 온도를 [deltaC]도 낮춤 (운전자가 "응" 한 뒤에만) */
    suspend fun lowerTemperature(deltaC: Float = 2f): FreshAirResult = FreshAirResult.UNSUPPORTED
    /** 신호별 연결 상태 (데모 패널 'Pleos 차량 신호' 카드). 이름 → "OK"/"실패"/"미지원" */
    fun signalStates(): Map<String, String> = emptyMap()
}

/** Pleos SDK가 없는 빌드(pleosSdk=false) 또는 연결 실패 시 */
object NoVehicleSignals : VehicleSignalsProvider {
    override val status: String = "Pleos Vehicle SDK 없음 (속도·기어만 사용)"
    override fun start() = Unit
    override fun stop() = Unit
    override fun steeringAngleDeg(): Float? = null
    override fun laneAssistActive(): Boolean = false
    override fun laneInterventionActive(): Boolean? = null
    override fun turnSignalOn(): Boolean? = null
    override fun driverAttentionWarning(): Boolean? = null
    override fun recirculationOn(): Boolean? = null
    override fun brakePressed(): Boolean? = null
}
