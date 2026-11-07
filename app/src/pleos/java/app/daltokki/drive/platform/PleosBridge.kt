package app.daltokki.drive.platform

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.WaypointChangedInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.telltale.Telltales
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import ai.pleos.playground.vehicle.constant.telltale.TelltaleState
import ai.pleos.playground.vehicle.constant.telltale.TelltaleType
import ai.pleos.playground.vehicle.listener.BrakePedalStatusListener
import ai.pleos.playground.vehicle.listener.HvacRecirculationStateListener
import ai.pleos.playground.vehicle.listener.SteeringWheelAngleListener
import ai.pleos.playground.vehicle.listener.TelltaleInfoListener
import ai.pleos.playground.vehicle.listener.TurnSignalStateListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import app.daltokki.drive.data.nav.WaypointNavigator
import app.daltokki.drive.data.vehicle.VehicleSignalsProvider
import app.daltokki.drive.engine.FreshAirResult
import app.daltokki.drive.voice.SpeechInput
import app.daltokki.drive.voice.SpeechOutput
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap

/**
 * Pleos Connect SDK 연결부 (gradle.properties의 pleosSdk=true일 때 빌드에 포함).
 * pleosSdk=false면 app/src/nopleos/의 같은 이름 파일이 대신 쓰여 SDK 없이도 빌드됩니다.
 *
 * 사용 SDK (별도 신청 불필요)
 * - Vehicle SDK 2.3.2 (Vehicle State): 핸들 각도, 방향지시등, 브레이크, 경고등(LKA/LFA/ELK/운전자 주의), 내기순환
 *   (14차) 풍량·좌석 착좌(실내 CO₂ 추정), 외기 전환(최소 차량 제어: 운전자가 "응" 한 뒤에만)
 * - NaviHelper SDK 3.0.5: 경유지 추가
 * - TextToSpeech / SpeechToText SDK 3.3.1: 온디바이스 모드(앱 등록 불필요) → PleosVoice.kt
 */
object PleosBridge {
    const val INCLUDED = true

    fun vehicleSignals(context: Context): VehicleSignalsProvider = PleosVehicleSignals(context)

    fun navigator(context: Context): WaypointNavigator = PleosWaypointNavigator(context)

    /** Pleos TTS(온디바이스). 안 되면 [fallback]으로 말함 */
    fun speechOutput(context: Context, fallback: () -> SpeechOutput): SpeechOutput = PleosTtsOutput(context, fallback)

    /** Pleos STT(온디바이스). 안 되면 [fallback]으로 들음 */
    fun speechInput(context: Context, fallback: () -> SpeechInput): SpeechInput = PleosSttInput(context, fallback)
}

/**
 * Vehicle SDK 신호 수신.
 * 휴대폰처럼 Pleos 차량 서비스가 없는 기기에서는 초기화나 등록이 실패하며, 그때는 값이 모두 null(모름)로 남습니다.
 */
private class PleosVehicleSignals(context: Context) : VehicleSignalsProvider {

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var vehicle: Vehicle? = null
    private var retries = 0
    /** start()·stop()은 엔진 스레드에서 불릴 수 있어, 실제 연결·해제는 메인 스레드에서 순서대로 처리합니다. */
    @Volatile private var wanted = false
    private val registerTask = Runnable { register() }

    @Volatile private var angle: Float? = null
    @Volatile private var turn: Boolean? = null
    @Volatile private var laneAssist = false
    @Volatile private var laneIntervention: Boolean? = null
    @Volatile private var attention: Boolean? = null
    @Volatile private var recirculation: Boolean? = null
    @Volatile private var brake: Boolean? = null
    @Volatile private var fan: Int? = null
    /** 운전석 말고 착좌가 감지된 좌석 */
    private val seated = ConcurrentHashMap<String, Boolean>()

    /** 신호별 상태 (OK / 실패 이유) */
    private val state = ConcurrentHashMap<String, String>()

    override val status: String
        get() {
            if (state.isEmpty()) return "Pleos Vehicle SDK 연결 중"
            return SIGNALS.joinToString(" · ") { name -> "$name ${state[name] ?: "대기"}" }
        }

    private val steeringListener = object : SteeringWheelAngleListener {
        override fun onSteeringWheelAngleUpdated(value: Float?) {
            angle = value
            if (value != null) state[STEER] = "OK"
        }

        override fun onFailed(exception: Exception) = failed(STEER, exception)
    }

    private val turnListener = object : TurnSignalStateListener {
        override fun onTurnSignalStateUpdated(value: TurnSignalValue?) {
            turn = value?.let { it != TurnSignalValue.OFF }
            if (value != null) state[TURN] = "OK"
        }

        override fun onFailed(exception: Exception) = failed(TURN, exception)
    }

    private val telltaleListener = object : TelltaleInfoListener {
        override fun onTelltaleInfoUpdated(value: Telltales?) {
            val list = value?.telltales.orEmpty()
            fun lit(s: TelltaleState?) = s == TelltaleState.ON || s == TelltaleState.BLINK
            // 차로 유지 보조가 켜져 있으면 차가 핸들을 보정하므로 핸들 신호는 쓰지 않음
            laneAssist = list.any { (it.type == TelltaleType.LKA || it.type == TelltaleType.LFA) && lit(it.state) }
            // 차로 유지 개입(ELK 점등, LKA 깜빡임) = 차선을 벗어나려 했다는 뜻
            laneIntervention = list.any {
                (it.type == TelltaleType.ELK && lit(it.state)) || (it.type == TelltaleType.LKA && it.state == TelltaleState.BLINK)
            }
            // 차량의 운전자 주의 경고(DAW)
            attention = list.any { it.type == TelltaleType.DRIVER_ATTENTION && lit(it.state) }
            state[TELLTALE] = "OK"
        }

        override fun onFailed(exception: Exception) = failed(TELLTALE, exception)
    }

    private val brakeListener = object : BrakePedalStatusListener {
        override fun onBrakePedalStatusUpdated(value: Boolean?) {
            brake = value
            if (value != null) state[BRAKE] = "OK"
        }

        override fun onFailed(exception: Exception) = failed(BRAKE, exception)
    }

    private val recirculationListener = object : HvacRecirculationStateListener {
        // Vehicle SDK 2.3.2에서는 내기순환 상태 콜백 이름이 onHvacAutoRecirculationStateUpdated 입니다.
        override fun onHvacAutoRecirculationStateUpdated(value: Boolean?) {
            recirculation = value
            if (value != null) state[HVAC] = "OK"
        }

        override fun onFailed(exception: Exception) = failed(HVAC, exception)
    }

    private fun failed(name: String, e: Exception) {
        Log.w(TAG, "$name 수신 실패: ${e.message}")
        state[name] = "실패"
        // 서비스 연결 직후에는 등록이 실패할 수 있어 몇 번 다시 시도
        if (retries < MAX_RETRIES) {
            retries++
            main.removeCallbacks(registerTask)
            main.postDelayed(registerTask, RETRY_DELAY_MS)
        }
    }

    override fun start() {
        wanted = true
        main.post { connect() }
    }

    private fun connect() {
        if (!wanted || vehicle != null) return
        try {
            val v = Vehicle(appContext)
            v.initialize()
            vehicle = v
            retries = 0
            // initialize()는 차량 서비스에 비동기로 연결하므로 잠시 뒤 등록합니다.
            main.postDelayed(registerTask, FIRST_REGISTER_DELAY_MS)
        } catch (t: Throwable) {
            Log.w(TAG, "Vehicle SDK 초기화 실패", t)
            SIGNALS.forEach { state[it] = "사용 불가" }
        }
    }

    private fun register() {
        val v = vehicle ?: return
        tryRegister(STEER) { v.getSteeringWheel().registerSteeringWheelAngle(steeringListener) }
        tryRegister(TURN) { v.getTurnSignal().registerTurnSignalState(turnListener) }
        tryRegister(BRAKE) { v.getBrake().registerBrakePedalStatus(brakeListener) }
        tryRegister(TELLTALE) { v.getSafety().registerTelltales(telltaleListener) }
        tryRegister(HVAC) { v.getHVAC().registerHvacRecirculationState(DRIVER_SEAT, recirculationListener) }
        registerExtras(v)
    }

    // ───────────── 14차: 풍량 · 착좌 · 외기 전환 ─────────────
    // 공식 샘플(pleos-sample-vehicle-app)의 API 이름을 따르되, 정확한 시그니처를 실차에서 확인하지 못해
    // 리플렉션으로 부릅니다. 없거나 다르면 '미지원'으로 남고 앱은 그 값 없이 동작합니다(CO₂는 1명 기준 추정).

    private fun registerExtras(v: Vehicle) {
        tryRegister(FAN) {
            val hvac = v.getHVAC()
            val m = findMethod(hvac, "registerHvacFanSpeed", 2) ?: throw NoSuchMethodException("registerHvacFanSpeed")
            m.invoke(hvac, DRIVER_SEAT, listenerProxy(m.parameterTypes[1], FAN) { value ->
                (value as? Number)?.toInt()?.let { fan = it; state[FAN] = "OK" }
            })
        }
        tryRegister(SEAT) {
            val seat = v.javaClass.methods.firstOrNull { it.name == "getSeat" && it.parameterTypes.isEmpty() }?.invoke(v)
                ?: throw NoSuchMethodException("getSeat")
            val m = seat.javaClass.methods.firstOrNull { it.name.startsWith("register") && it.name.contains("Occupan") && it.parameterTypes.size == 2 }
                ?: throw NoSuchMethodException("register*Occupancy")
            var any = false
            for (name in OTHER_SEATS) {
                val value = runCatching { SeatValue.valueOf(name) }.getOrNull() ?: continue
                m.invoke(seat, SeatArea(value), listenerProxy(m.parameterTypes[1], SEAT) { v2 ->
                    occupiedOf(v2)?.let { seated[name] = it; state[SEAT] = "OK" }
                })
                any = true
            }
            if (!any) throw NoSuchFieldException("SeatValue")
        }
    }

    /** 착좌 값 → 앉아 있음? (Boolean, 숫자, 'OCCUPIED' 같은 이름의 enum 모두 받음) */
    private fun occupiedOf(value: Any?): Boolean? = when (value) {
        null -> null
        is Boolean -> value
        is Number -> value.toInt() > 0
        else -> value.toString().uppercase().let { n ->
            when {
                n.contains("UNOCCUP") || n.contains("NOT") || n.contains("EMPTY") || n == "NONE" || n == "OFF" -> false
                n.contains("OCCUP") || n == "ON" || n == "TRUE" || n.contains("SEATED") -> true
                else -> null
            }
        }
    }

    override suspend fun setFreshAir(): FreshAirResult {
        val v = vehicle ?: return FreshAirResult.UNSUPPORTED
        val result = CompletableDeferred<FreshAirResult>()
        main.post {
            try {
                val hvac = v.getHVAC()
                val m = findMethod(hvac, "setHvacRecirculation", 4) ?: findMethod(hvac, "setHvacRecirculation", 2)
                if (m == null) {
                    result.complete(FreshAirResult.UNSUPPORTED)
                    return@post
                }
                val args = arrayOfNulls<Any>(m.parameterTypes.size)
                args[0] = DRIVER_SEAT
                args[1] = false // 내기순환 끔 = 외기
                for (i in 2 until args.size) {
                    args[i] = callbackProxy(m.parameterTypes[i]) { ok, error ->
                        result.complete(
                            when {
                                ok -> FreshAirResult.DONE
                                error is SecurityException -> FreshAirResult.NO_PERMISSION
                                else -> FreshAirResult.UNSUPPORTED
                            },
                        )
                    }
                }
                m.invoke(hvac, *args)
                if (args.size == 2) result.complete(FreshAirResult.DONE)
            } catch (t: Throwable) {
                val cause = (t as? java.lang.reflect.InvocationTargetException)?.targetException ?: t
                Log.w(TAG, "외기 전환 실패", cause)
                result.complete(if (cause is SecurityException) FreshAirResult.NO_PERMISSION else FreshAirResult.UNSUPPORTED)
            }
        }
        val r = withTimeoutOrNull(3_000) { result.await() } ?: FreshAirResult.UNSUPPORTED
        state[FRESH] = when (r) {
            FreshAirResult.DONE -> "OK"
            FreshAirResult.NO_PERMISSION -> "권한 없음"
            FreshAirResult.UNSUPPORTED -> "미지원"
        }
        if (r == FreshAirResult.DONE) recirculation = false
        return r
    }

    /**
     * 운전석 설정 온도 낮추기: 지금 설정 온도를 읽고(getHvacTemperature…) [deltaC]도 낮춰 setHvacTemperature.
     * 시그니처를 실차에서 확인하지 못해 리플렉션으로 여러 형태를 시도합니다. 안 되면 UNSUPPORTED → 음성 안내.
     */
    override suspend fun lowerTemperature(deltaC: Float): FreshAirResult {
        val v = vehicle ?: return FreshAirResult.UNSUPPORTED
        val result = CompletableDeferred<FreshAirResult>()
        main.post {
            try {
                val hvac = v.getHVAC()
                val current = readTemperature(hvac)
                val setter = hvac.javaClass.methods.firstOrNull { it.name.startsWith("setHvacTemperature") && (it.parameterTypes.size == 4 || it.parameterTypes.size == 2) }
                if (current == null || setter == null) {
                    result.complete(FreshAirResult.UNSUPPORTED)
                    return@post
                }
                val target = current - deltaC
                val args = arrayOfNulls<Any>(setter.parameterTypes.size)
                args[0] = DRIVER_SEAT
                args[1] = when (setter.parameterTypes[1]) {
                    Int::class.javaPrimitiveType, Int::class.javaObjectType -> target.toInt()
                    Double::class.javaPrimitiveType, Double::class.javaObjectType -> target.toDouble()
                    else -> target
                }
                for (i in 2 until args.size) {
                    args[i] = callbackProxy(setter.parameterTypes[i]) { ok, error ->
                        result.complete(
                            when {
                                ok -> FreshAirResult.DONE
                                error is SecurityException -> FreshAirResult.NO_PERMISSION
                                else -> FreshAirResult.UNSUPPORTED
                            },
                        )
                    }
                }
                setter.invoke(hvac, *args)
                if (args.size == 2) result.complete(FreshAirResult.DONE)
            } catch (t: Throwable) {
                val cause = (t as? java.lang.reflect.InvocationTargetException)?.targetException ?: t
                Log.w(TAG, "온도 낮추기 실패", cause)
                result.complete(if (cause is SecurityException) FreshAirResult.NO_PERMISSION else FreshAirResult.UNSUPPORTED)
            }
        }
        val r = withTimeoutOrNull(3_000) { result.await() } ?: FreshAirResult.UNSUPPORTED
        state[TEMP] = when (r) {
            FreshAirResult.DONE -> "OK"
            FreshAirResult.NO_PERMISSION -> "권한 없음"
            FreshAirResult.UNSUPPORTED -> "미지원"
        }
        return r
    }

    /** 지금 설정 온도: 값을 바로 돌려주는 getter(인자 1개)를 먼저, 없으면 모름 */
    private fun readTemperature(hvac: Any): Float? {
        val getter = hvac.javaClass.methods.firstOrNull { it.name.startsWith("getHvacTemperature") && it.parameterTypes.size == 1 }
            ?: return null
        return (getter.invoke(hvac, DRIVER_SEAT) as? Number)?.toFloat()
    }

    private fun findMethod(target: Any, name: String, params: Int): Method? =
        target.javaClass.methods.firstOrNull { it.name == name && it.parameterTypes.size == params }

    /** 리스너 인터페이스 대리 구현: on…Updated(값) → [onValue], onFailed → 실패 표시 */
    private fun listenerProxy(type: Class<*>, name: String, onValue: (Any?) -> Unit): Any =
        Proxy.newProxyInstance(type.classLoader, arrayOf(type), InvocationHandler { proxy, method, args ->
            when (method.name) {
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.getOrNull(0)
                "toString" -> "PleosListener($name)"
                "onFailed" -> {
                    (args?.getOrNull(0) as? Exception)?.let { failed(name, it) }
                    null
                }
                else -> {
                    if (method.name.startsWith("on") && !args.isNullOrEmpty()) onValue(args.last())
                    null
                }
            }
        })

    /**
     * 콜백(코틀린 함수형 Function0~3 또는 리스너 인터페이스) 대리 구현.
     * 인자에 예외가 있으면 실패, false(수락 안 됨)가 있으면 실패, 그 밖에는 성공으로 봅니다.
     */
    private fun callbackProxy(type: Class<*>, done: (Boolean, Throwable?) -> Unit): Any =
        Proxy.newProxyInstance(type.classLoader, arrayOf(type), InvocationHandler { proxy, method, args ->
            when (method.name) {
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.getOrNull(0)
                "toString" -> "PleosCallback"
                else -> {
                    val list = args?.toList().orEmpty()
                    val error = list.firstOrNull { it is Throwable } as? Throwable
                    val rejected = list.any { it == false }
                    done(error == null && !rejected && !method.name.contains("Fail"), error)
                    if (method.returnType == Void.TYPE) null else Unit
                }
            }
        })

    private inline fun tryRegister(name: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            Log.w(TAG, "$name 등록 실패", t)
            state[name] = "미지원"
        }
    }

    override fun stop() {
        wanted = false
        main.post { disconnect() }
    }

    private fun disconnect() {
        if (wanted) return // 그사이 다시 시작됨
        main.removeCallbacks(registerTask)
        val v = vehicle ?: return
        vehicle = null
        runCatching { v.getSteeringWheel().unregisterSteeringWheelAngle(steeringListener) }
        runCatching { v.getTurnSignal().unregisterTurnSignalState(turnListener) }
        runCatching { v.getSafety().unregisterTelltales(telltaleListener) }
        // 공조 리스너는 release()에서 함께 정리됩니다.
        runCatching { v.release() }
        retries = 0
    }

    override fun steeringAngleDeg(): Float? = angle
    override fun laneAssistActive(): Boolean = laneAssist
    override fun laneInterventionActive(): Boolean? = laneIntervention
    override fun turnSignalOn(): Boolean? = turn
    override fun driverAttentionWarning(): Boolean? = attention
    override fun recirculationOn(): Boolean? = recirculation
    override fun brakePressed(): Boolean? = brake
    override fun fanSpeed(): Int? = fan
    override fun occupants(): Int? = if (seated.isEmpty()) null else 1 + seated.values.count { it }
    override fun signalStates(): Map<String, String> = (SIGNALS + listOf(FAN, SEAT, FRESH, TEMP)).associateWith { state[it] ?: "대기" }

    private companion object {
        const val TAG = "PleosVehicleSignals"
        const val STEER = "핸들"
        const val TURN = "방향지시등"
        const val TELLTALE = "경고등"
        const val HVAC = "공조"
        const val BRAKE = "브레이크"
        const val FAN = "풍량"
        const val SEAT = "착좌"
        const val FRESH = "외기 전환"
        const val TEMP = "온도 낮추기"
        val OTHER_SEATS = listOf("ROW_1_RIGHT", "ROW_2_LEFT", "ROW_2_CENTER", "ROW_2_RIGHT")
        val SIGNALS = listOf(STEER, TURN, BRAKE, TELLTALE, HVAC)
        const val FIRST_REGISTER_DELAY_MS = 800L
        const val RETRY_DELAY_MS = 3_000L
        const val MAX_RETRIES = 3
        val DRIVER_SEAT = SeatArea(SeatValue.ROW_1_LEFT)
    }
}

/**
 * NaviHelper로 휴게소를 경유지에 추가합니다.
 * 결과는 onWaypointChanged(성공) / onError(실패) 이벤트로 오며, 5초 안에 답이 없으면 실패로 봅니다.
 */
private class PleosWaypointNavigator(context: Context) : WaypointNavigator {

    private val navi: NaviHelper? = try {
        NaviHelper(context.applicationContext)
    } catch (t: Throwable) {
        Log.w(TAG, "NaviHelper 생성 실패", t)
        null
    }

    @Volatile private var ready = false
    @Volatile private var lastError: String? = null
    @Volatile private var pending: CompletableDeferred<Boolean>? = null

    private val listener = object : NaviHelperEventListener {
        override fun onWaypointChanged(info: WaypointChangedInfo) {
            pending?.complete(true)
        }

        override fun onError(errorCode: NaviErrorCode) {
            lastError = errorCode.toString()
            pending?.complete(false)
        }
    }

    init {
        navi?.let { n ->
            try {
                n.initialize({ ready = true }, { lastError = it.message })
                n.addListener(listener)
            } catch (t: Throwable) {
                Log.w(TAG, "NaviHelper 초기화 실패", t)
                lastError = t.message
            }
        }
    }

    override val status: String
        get() = when {
            navi == null -> "NaviHelper 사용 불가"
            ready -> "NaviHelper OK"
            lastError != null -> "NaviHelper 오류: $lastError"
            else -> "NaviHelper 연결 중"
        }

    override suspend fun addWaypoint(latitude: Double, longitude: Double, name: String): Boolean {
        val n = navi ?: return false
        val result = CompletableDeferred<Boolean>()
        pending = result
        try {
            n.addWaypoint(
                RequestWaypointInfo(
                    latitude = latitude,
                    longitude = longitude,
                    waypointIndex = WaypointIndex.FIRST,
                    poiId = "",
                    poiName = name,
                    poiSubId = "",
                    address = "",
                ),
            )
        } catch (t: Throwable) {
            Log.w(TAG, "addWaypoint 실패", t)
            pending = null
            return false
        }
        val ok = withTimeoutOrNull(5_000) { result.await() } ?: false
        pending = null
        return ok
    }

    override fun release() {
        val n = navi ?: return
        runCatching { n.removeListener(listener) }
        runCatching { n.release() }
    }

    private companion object {
        const val TAG = "PleosNavigator"
    }
}
