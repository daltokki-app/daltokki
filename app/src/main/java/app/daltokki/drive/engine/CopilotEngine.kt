package app.daltokki.drive.engine

import kotlin.math.roundToInt
import app.daltokki.drive.domain.fatigue.DropCause
import app.daltokki.drive.domain.fatigue.RiskDrop
import app.daltokki.drive.domain.fatigue.RiskDropTracker
import android.content.Context
import android.os.SystemClock
import app.daltokki.drive.AppContainer
import app.daltokki.drive.data.nav.RestArea
import app.daltokki.drive.data.nav.RestAreaProvider
import app.daltokki.drive.data.nav.RestStop
import app.daltokki.drive.data.nav.SimRestAreaProvider
import app.daltokki.drive.data.nav.VehicleRestAreaProvider
import app.daltokki.drive.data.settings.SettingsStore
import app.daltokki.drive.data.vehicle.CarConnection
import app.daltokki.drive.data.vehicle.CarVehicleSource
import app.daltokki.drive.data.vehicle.SimVehicleSource
import app.daltokki.drive.data.vehicle.VehicleDataSource
import app.daltokki.drive.domain.dialog.DriverMemory
import app.daltokki.drive.domain.dialog.Intervention
import app.daltokki.drive.domain.dialog.TalkLevel
import app.daltokki.drive.domain.driver.DemoDriver
import app.daltokki.drive.domain.driver.DriverCodec
import app.daltokki.drive.domain.driver.DriverProfile
import app.daltokki.drive.domain.driver.DriverRoster
import app.daltokki.drive.domain.driver.HabitLearning
import app.daltokki.drive.domain.driver.SpeedBand
import app.daltokki.drive.domain.driver.TripLog
import app.daltokki.drive.domain.driver.TripHabit
import app.daltokki.drive.domain.game.AwakeScore
import app.daltokki.drive.domain.game.Badges
import app.daltokki.drive.domain.game.GameKind
import app.daltokki.drive.domain.game.TripResult
import app.daltokki.drive.domain.game.GiftBox
import app.daltokki.drive.domain.game.Gift
import app.daltokki.drive.voice.SoundEffect
import app.daltokki.drive.domain.game.PointCategory
import app.daltokki.drive.domain.game.SafetyAction
import app.daltokki.drive.domain.game.StampBook
import app.daltokki.drive.domain.fatigue.FatigueEngine
import app.daltokki.drive.domain.fatigue.ResponseKind
import app.daltokki.drive.domain.fatigue.RestPlanner
import app.daltokki.drive.domain.model.DriveEvent
import app.daltokki.drive.domain.model.DriveState
import app.daltokki.drive.domain.model.FatigueAssessment
import app.daltokki.drive.domain.model.FatigueLevel
import app.daltokki.drive.domain.model.TripStats
import app.daltokki.drive.domain.model.VehicleSnapshot
import app.daltokki.drive.domain.sim.DriveScenario
import app.daltokki.drive.domain.sim.Scenarios
import app.daltokki.drive.gleo.GleoActionReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors

enum class SourceMode(val label: String) { SIMULATION("시뮬레이션"), VEHICLE("실차") }

/** 피로 그래프의 점 하나 */
data class GraphPoint(
    val minute: Float,
    val score: Float,
    /** 보너스 적용 전 센서 위험도 */
    val sensor: Float,
    val context: Float,
    val steering: Float?,
    val dialog: Float?,
    val level: FatigueLevel,
)

data class BadgeUi(val name: String, val description: String, val earned: Boolean)

/** 도장 하나 (정차 화면 표시용) */
data class StampUi(val name: String, val isShelter: Boolean, val visits: Int, val route: String?)

/** 노선별 도장 진행 */
data class RouteUi(val route: String, val have: Int, val total: Int)

/** 점수를 얻은 까닭 (화면 연출 종류) */
enum class GainReason { CORRECT, SAFETY, REST, NAP }

/**
 * 방금 얻은 점수 한 번 (화면이 seq가 바뀔 때마다 연출).
 * @param combo 게임 정답이면 연속 정답 수, @param multiplier 콤보 배율, @param fast 번개 보너스
 * @param label 연출에 붙일 한 줄 ("휴식 완료 · 새 도장" 등)
 */
data class ScoreGain(
    val seq: Int,
    val points: Int,
    val reason: GainReason,
    val combo: Int = 0,
    val multiplier: Float = 1f,
    val fast: Boolean = false,
    val label: String? = null,
    /** 휴식 완료 때 새로 받은 도장 이름 ("쾅" 연출) */
    val stampName: String? = null,
    /** 휴식 잭팟 내역 ("휴식 완료" 500, "제때 쉬기" 200, "새 도장" 200, "달빛 조각 ×12" 120) */
    val parts: List<Pair<String, Int>> = emptyList(),
    /** 터뜨린 달빛 조각 수 */
    val shards: Int = 0,
)

/** 달빛 선물 상자를 열었을 때 (화면 연출용) */
data class GiftReveal(val seq: Int, val title: String, val detail: String, val itemId: String? = null, val points: Int = 0)

/** 게임 화면 상태: 각성 점수·콤보·배지 */
data class GameUi(
    /** 방금 얻은 점수 (연출용). 없으면 null */
    val lastGain: ScoreGain? = null,
    /** 이번 운행 점수 내역 (게임 · 안전 행동 · 휴식 · 도장) */
    val breakdown: Map<PointCategory, Int> = emptyMap(),
    /** 운전 중 모은 달빛 조각 (쉬면 터짐) */
    val shards: Int = 0,
    val tripPoints: Int = 0,
    val combo: Int = 0,
    val bestCombo: Int = 0,
    val lifetimePoints: Int = 0,
    val nextBadgeName: String? = null,
    val pointsToNextBadge: Int = 0,
    val nextBadgeProgress: Float = 0f,
    val badges: List<BadgeUi> = emptyList(),
    /** 방금 받은 배지 (잠깐 표시) */
    val recentBadge: String? = null,
    /** 휴게소 도장: 서로 다른 곳 수 / 전국 휴게소·졸음쉼터 수 */
    val stampPlaces: Int = 0,
    val stampTotal: Int = 0,
    val shelterStamps: Int = 0,
    /** 최근 도장 (최근 방문 순) */
    val recentStamps: List<StampUi> = emptyList(),
    /** 도장이 있는 노선 (많이 받은 순) */
    val routes: List<RouteUi> = emptyList(),
)

/** 운전자 (정차 화면 선택 칩·목록) */
data class DriverUi(
    val id: String,
    val name: String,
    /** 색 번호 (게스트는 -1) */
    val colorIndex: Int,
    /** 운전 습관이 쌓인 운행 수 */
    val habitTrips: Int,
    /** 출발하자마자 판단할 만큼 쌓였는지 (3회 이상) */
    val habitReady: Boolean,
    val guest: Boolean = false,
    val demo: Boolean = false,
    /** 기억하는 대화: "자주 가는 곳 · 부산" 같은 줄 */
    val memory: List<String> = emptyList(),
    /** 안전 운행 연속 기록 (졸음 신호 없이 끝났거나 졸리면 쉰 운행) */
    val streak: Int = 0,
    /** "이번 주 3번 운행 · 휴식 2번 · 졸음 경고 1번 (지난주 3번보다 줄었어요)" */
    val weekly: String = "",
    /** 평소 습관(누적) vs 지난 운행: 핸들 보정 횟수(/분)·대답 속도(ms). 모르면 null */
    val habitRate: Float? = null,
    val habitLatencyMs: Long? = null,
    val lastRate: Float? = null,
    val lastLatencyMs: Long? = null,
    /** 지난 운행 습관을 배웠는지 한 줄 ("졸린 날이라 배우지 않았어요" 등) */
    val lastHabitNote: String? = null,
    /** 달토끼 꾸미기: 착용 중인 아이템 id, 모은 아이템 수, 안 연 선물 상자 수 */
    val wearing: String? = null,
    val itemCount: Int = 0,
    val gifts: Int = 0,
    /** 모은 꾸미기 아이템 id (꾸미기 고르기 창용) */
    val items: List<String> = emptyList(),
)

/** 이번 운행 습관이 평소와 많이 달라 묻는 중 (다른 사람이 운전했을 수 있음) */
data class HabitMismatchUi(val driverName: String, val reason: String)

/** 이번 운행 습관을 누적했는지 알림 (자동 시연 장면 자막용) */
data class HabitNotice(val seq: Int, val verdict: HabitLearning.Verdict, val text: String)

data class CopilotUiState(
    /** 이 기기가 차량(Android Automotive)이라 실차 데이터를 쓸 수 있는지. 휴대폰이면 false. */
    val vehicleSupported: Boolean = false,
    /** Pleos SDK가 빌드에 포함됐는지 (gradle.properties의 pleosSdk) */
    val pleosIncluded: Boolean = false,
    val running: Boolean = false,
    val paused: Boolean = false,
    val sourceMode: SourceMode = SourceMode.SIMULATION,
    /** 시뮬레이션에서도 실제 Pleos 내비에 경유지 넣기 (데모 패널 스위치, 기본 끔) */
    val realNaviInSim: Boolean = false,
    /** 가벼운 연출 (데모 패널 스위치) */
    val lightEffects: Boolean = false,
    val scenario: DriveScenario = Scenarios.nightHighway,
    val speed: Float = 30f,
    val effectiveSpeed: Float = 0f,
    val sourceStatus: String = "",
    val phaseLabel: String = "",
    val progress: Float = 0f,
    val clock: String = "",
    val snapshot: VehicleSnapshot? = null,
    val assessment: FatigueAssessment? = null,
    val history: List<GraphPoint> = emptyList(),
    val stats: TripStats = TripStats(),
    val lastTripStats: TripStats? = null,
    val restElapsedMin: Float = 0f,
    val game: GameUi = GameUi(),
    val restArea: RestArea? = null,
    /** 출발 전 컨디션: 어젯밤 잔 시간 (최근 16시간 안에 답한 것만) */
    val sleepHours: Float? = null,
    /** 지금 운전자 */
    val driver: DriverUi? = null,
    /** 고를 수 있는 운전자 (게스트 제외) */
    val drivers: List<DriverUi> = emptyList(),
    /** 이번 운행: 누적 습관으로 바로 판단 중이면 그 운행 수 (아니면 0) */
    val habitInUseTrips: Int = 0,
    val habitMismatch: HabitMismatchUi? = null,
    val habitNotice: HabitNotice? = null,
    /** 커피냅 남은 시간(분). 커피냅 중이 아니면 null */
    val napRemainingMin: Float? = null,
    /** Pleos 차량 신호별 연결 상태 (데모 패널 '차량 신호' 카드). 실차 모드에서만 */
    val signalStates: Map<String, String> = emptyMap(),
    /** 처음 실행(운전자가 기본 이름 '운전자 1' 한 명뿐): 정차 화면에서 운전자 고르기 창을 한 번 띄움 */
    val askDriverOnStart: Boolean = false,
    /** 운행이 끝날 때마다 1씩 → 화면이 운행 리포트를 엶 */
    val reportSeq: Int = 0,
    /** 지난 운행 결과 (별 3개 · 등급 · 신기록) */
    val lastResult: TripResult? = null,
    /** 방금 연 달빛 선물 (연출이 끝나면 null) */
    val giftReveal: GiftReveal? = null,
    /** 행동 뒤 졸음 위험도가 실제로 내려감: "-7 · 빠른 대답" (주행 화면 위험도 숫자 옆) */
    val riskDrop: RiskDrop? = null,
)

/**
 * 앱의 중심. 데이터 공급원 → 피로 추정 엔진 → 대화 코디네이터를 연결하고 화면 상태를 만듭니다.
 * 피로 추정은 전용 단일 스레드에서, 대화는 메인 스레드에서 실행합니다.
 */
class CopilotEngine(
    private val context: Context,
    private val container: AppContainer,
) : DialogHost {

    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val engineDispatcher = Executors.newSingleThreadExecutor { r -> Thread(r, "fatigue-engine") }
        .asCoroutineDispatcher()

    private val zone: ZoneId = ZoneId.systemDefault()
    private val clockFormat = DateTimeFormatter.ofPattern("HH:mm:ss")

    private var fatigue = FatigueEngine(zone = zone)
    private var source: VehicleDataSource? = null
    private var restAreas: RestAreaProvider = SimRestAreaProvider(RestArea("한빛 휴게소", 12))
    private var runJob: Job? = null
    private var restAreaJob: Job? = null

    private val history = ArrayList<GraphPoint>()
    private var lastGraphT = -1L
    private var lastPublishReal = 0L
    private var lastPublishedLevel: FatigueLevel? = null
    private var stats = TripStats()

    private val settings = SettingsStore(context)

    // ───────────────────────── 운전자 (차 한 대를 여러 명이) ─────────────────────────

    private val rosterLock = Any()
    /** 운전자 목록. 처음이면 예전 기록(말 수·잠·점수·배지·도장)을 '운전자 1'로 옮김 */
    private var roster: DriverRoster = DriverCodec.decode(settings.drivers) ?: migrateLegacy()
    /** 게스트 (저장하지 않음) */
    private var guestProfile = DriverProfile.guest()
    /** 자동 시연 중 임시 운전자 (저장하지 않음) */
    @Volatile private var demoDriver: DriverProfile? = null
    /** 운전자별 지난 운행 리포트 (앱이 켜져 있는 동안) */
    private val lastTripByDriver: MutableMap<String, TripStats> = java.util.Collections.synchronizedMap(HashMap())
    /** 평소와 많이 달라 운전자에게 묻는 중인 이번 운행 습관 */
    @Volatile private var pendingMismatch: TripHabit? = null
    private var habitNoticeSeq = 0

    /** 지금 운전자 (시연 중이면 시연 운전자) */
    private val driver: DriverProfile
        get() = demoDriver ?: synchronized(rosterLock) {
            if (roster.currentId == DriverProfile.GUEST_ID) guestProfile else roster.current
        }

    private var awake = AwakeScore(driver.lifetimePoints, driver.badges)

    /** 휴게소 도장 깨기 (공공데이터 휴게소·졸음쉼터가 도장판). 운전자별 */
    private var stamps = StampBook(StampBook.parse(driver.stamps)) { container.restAreaDatabase.routeTotals }
    /** 시뮬레이션 시나리오의 실제 휴게소 (공공데이터에서 찾음) */
    private var simStop: RestStop? = null
    /** 지금 쉬고 있는 휴게소·쉼터 (휴식을 시작할 때 위치로 찾음. 휴게소가 아닌 곳이면 null) */
    private var restingAt: RestStop? = null
    /** 시연 시작이면 컨디션 질문을 매번 다시 함 */
    private var forceSleepCheck = false
    /** 커피냅이 끝나는 차량 시각 (없으면 -1). 시뮬레이션 배속에서도 차량 시각으로 잼 */
    @Volatile private var napUntilMs = -1L

    init {
        fatigue.setSleepHours(validSleep())
        fatigue.habitSeed = seedOf(driver)
    }

    private fun migrateLegacy(): DriverRoster {
        val first = DriverProfile(
            id = "d1",
            name = DEFAULT_DRIVER_NAME,
            colorIndex = 0,
            talkLevel = settings.talkLevel,
            sleepHours = settings.sleepHours,
            sleepAnsweredAt = settings.sleepAnsweredAt,
            stamps = settings.stamps,
            lifetimePoints = settings.lifetimePoints,
            badges = settings.badges,
            createdMs = System.currentTimeMillis(),
        )
        return DriverRoster.initial(first).also { settings.drivers = DriverCodec.encode(it) }
    }

    /** 운전자의 누적 습관 (게스트는 없음) */
    private fun seedOf(d: DriverProfile): FatigueEngine.HabitSeed? =
        if (d.isGuest) null else FatigueEngine.HabitSeed(d.habit, d.latencySamples)

    /** 지금 운전자 기록 고치기: 시연·게스트는 메모리에서만, 그 밖에는 저장 */
    private fun updateDriver(change: (DriverProfile) -> DriverProfile) {
        val demo = demoDriver
        if (demo != null) {
            demoDriver = change(demo)
            return
        }
        synchronized(rosterLock) {
            if (roster.currentId == DriverProfile.GUEST_ID) {
                guestProfile = change(guestProfile)
                return
            }
            roster = roster.update(roster.currentId, change)
            settings.drivers = DriverCodec.encode(roster)
        }
    }

    private fun changeRoster(change: (DriverRoster) -> DriverRoster) {
        synchronized(rosterLock) {
            roster = change(roster)
            settings.drivers = DriverCodec.encode(roster)
        }
    }

    private fun DriverProfile.toUi() = DriverUi(
        id = id, name = name, colorIndex = colorIndex, habitTrips = habit.trips, habitReady = habit.ready,
        guest = isGuest, demo = demo,
        memory = memory.entries.sortedBy { it.key.ordinal }.map { "${it.key.label} · ${it.value}" },
        streak = TripLog.streak(trips),
        weekly = TripLog.weekly(trips, System.currentTimeMillis()).text(),
        habitRate = habit.baseline(SpeedBand.HIGH)?.reversalRatePerMin,
        habitLatencyMs = median(latencySamples),
        lastRate = lastHabitByDriver[id]?.first,
        lastLatencyMs = lastLatencyByDriver[id],
        lastHabitNote = lastHabitByDriver[id]?.second,
        wearing = wearing,
        itemCount = items.size,
        gifts = gifts,
        items = items.sorted(),
    )

    private fun median(xs: List<Long>): Long? = if (xs.isEmpty()) null else xs.sorted()[xs.size / 2]

    /** 운전자별 지난 운행 습관(핸들 보정 /분, 배웠는지 한 줄)·대답 속도 (앱이 켜져 있는 동안) */
    private val lastHabitByDriver: MutableMap<String, Pair<Float, String>> = java.util.Collections.synchronizedMap(HashMap())
    private val lastLatencyByDriver: MutableMap<String, Long> = java.util.Collections.synchronizedMap(HashMap())

    private fun driversUi(): List<DriverUi> = synchronized(rosterLock) { roster.drivers.map { it.toUi() } }

    /** 최근 16시간 안에 답한 수면 시간 (지났으면 다음 날로 보고 버림) */
    private fun validSleep(): Float? {
        val d = driver
        val h = d.sleepHours ?: return null
        val age = System.currentTimeMillis() - d.sleepAnsweredAt
        return h.takeIf { age in 0..RestPlanner.SLEEP_ANSWER_VALID_MS }
    }

    /** 차량이면 실차, 휴대폰이면 시뮬레이션이 기본. 마지막 선택을 기억합니다. */
    private val vehicleSupported = CarConnection.isAutomotive(context)
    private val initialSource: SourceMode = settings.sourceModeName
        ?.let { name -> SourceMode.entries.firstOrNull { it.name == name } }
        ?.takeIf { it != SourceMode.VEHICLE || vehicleSupported }
        ?: if (vehicleSupported) SourceMode.VEHICLE else SourceMode.SIMULATION

    private val _ui = MutableStateFlow(
        CopilotUiState(
            vehicleSupported = vehicleSupported,
            pleosIncluded = container.pleosIncluded,
            sourceMode = initialSource,
            realNaviInSim = settings.realNaviInSim,
            lightEffects = settings.lightEffects,
            game = gameUi(null),
            sleepHours = validSleep(),
            driver = driver.toUi(),
            drivers = driversUi(),
            askDriverOnStart = !settings.driverPrompted && synchronized(rosterLock) {
                roster.drivers.size == 1 && roster.drivers.first().name == DEFAULT_DRIVER_NAME
            },
        ),
    )
    val ui: StateFlow<CopilotUiState> = _ui

    /** 처음 실행 때 운전자 창을 띄웠음 (다시 띄우지 않음) */
    // ───────────────────────── 달빛 선물 · 꾸미기 ─────────────────────────

    /** 선물 상자 결과 무작위 (자동 시연은 고정 seed → 매번 같은 선물) */
    private var giftRandom: kotlin.random.Random = kotlin.random.Random.Default
    private var giftSeq = 0

    /**
     * 달빛 선물 상자 열기 (정차 중 [선물 열기] 버튼, 자동 시연은 휴식 잭팟 뒤 자동).
     * 꾸미기 아이템이면 바로 착용, 점수면 각성 점수에 더함. 화면은 [CopilotUiState.giftReveal]로 연출.
     */
    fun openGift() {
        val d = driver
        if (d.gifts <= 0) return
        val gift = GiftBox.open(giftRandom, d.items)
        updateDriver { it.copy(gifts = (it.gifts - 1).coerceAtLeast(0)) }
        val reveal = when (gift) {
            is Gift.Item -> {
                updateDriver { it.copy(items = it.items + gift.item.id, wearing = gift.item.id) }
                GiftReveal(++giftSeq, gift.item.name, if (gift.item.rare) "아주 드문 꾸미기! 달토끼가 바로 착용했어요" else "새 꾸미기! 달토끼가 바로 착용했어요", itemId = gift.item.id)
            }
            is Gift.Points -> {
                onEngine {
                    awake.onGiftPoints(gift.points)
                    saveGame()
                    publishGame(null)
                }
                GiftReveal(++giftSeq, "보너스 +${gift.points}점", "달빛 보너스! 각성 점수에 더했어요", points = gift.points)
            }
        }
        _ui.update { it.copy(giftReveal = reveal, driver = driver.toUi()) }
    }

    fun dismissGift() {
        _ui.update { it.copy(giftReveal = null) }
    }

    /** 꾸미기 바꾸기 (null = 벗기) */
    fun wear(itemId: String?) {
        updateDriver { if (itemId == null || itemId in it.items) it.copy(wearing = itemId) else it }
        refreshDriverUi()
    }

    /** 자동 시연 하이라이트: 시뮬레이션을 잠깐 멈춤 (보상 장면을 충분히 보여 주게) */
    fun holdSim(ms: Long) {
        val sim = source as? SimVehicleSource ?: return
        sim.paused.value = true
        mainScope.launch {
            delay(ms)
            if (source === sim) sim.paused.value = false
        }
    }

    fun onDriverPrompted() {
        settings.driverPrompted = true
        _ui.update { it.copy(askDriverOnStart = false) }
    }

    val dialog = DialogCoordinator(
        scope = mainScope,
        tts = container.speechOutput,
        stt = container.speechInput,
        llm = container.llm,
        host = this,
        dictionary = { container.wordDictionary },
        quizBank = { container.quizBank },
        initialTalkLevel = driver.talkLevel,
        sfx = container.soundEffects,
        audio = container.audioEnvironment,
    )

    /**
     * 자동 시연 (녹화용): 운전자 대답을 대본대로 자동으로. 수동 시연은 [startDemo].
     * 운전자 목소리는 안드로이드 기본 TTS 남성 음성(없으면 낮은 음높이).
     */
    val autoDemo = AutoDriver(
        engine = this,
        scope = mainScope,
        folder = { context.getExternalFilesDir("demo") },
        driverVoice = { container.driverSpeechOutput },
    )

    // ───────────────────────── 운전자 고르기·관리 (정차 화면) ─────────────────────────

    /** 운전자 고르기 (게스트: [DriverProfile.GUEST_ID]). 다음 운행부터 그 운전자의 습관·기록으로 */
    fun selectDriver(id: String) {
        if (demoDriver != null) return
        if (id == DriverProfile.GUEST_ID) guestProfile = DriverProfile.guest(driver.talkLevel)
        changeRoster { it.select(id) }
        applyDriver()
    }

    fun addDriver(name: String) {
        if (demoDriver != null) return
        changeRoster { it.add(name, "d" + System.currentTimeMillis().toString(36), System.currentTimeMillis()) }
        applyDriver()
    }

    fun renameDriver(id: String, name: String) {
        changeRoster { it.rename(id, name) }
        refreshDriverUi()
    }

    fun removeDriver(id: String) {
        if (demoDriver != null) return
        val wasCurrent = synchronized(rosterLock) { roster.currentId == id }
        changeRoster { it.remove(id) }
        if (wasCurrent) applyDriver() else refreshDriverUi()
    }

    /** 기억하는 대화: 그 운전자의 기억(목적지·음식 등)을 지움 */
    fun clearDriverMemory(id: String) {
        if (demoDriver != null) return
        changeRoster { r -> r.update(id) { it.copy(memory = emptyMap()) } }
        refreshDriverUi()
    }

    /** 운전 습관·대답 속도 기준을 지우고 처음부터 다시 배움 */
    fun resetDriverHabit(id: String) {
        changeRoster { r -> r.update(id) { it.resetHabit() } }
        if (synchronized(rosterLock) { roster.currentId == id }) applyDriver() else refreshDriverUi()
    }

    /**
     * 이번 운행 습관이 평소와 많이 달랐을 때의 답.
     * @param assignTo 이 운전자의 습관으로 넣고 그 운전자로 바꿈. null이면 학습하지 않음
     */
    fun resolveHabitMismatch(assignTo: String?) {
        val trip = pendingMismatch
        pendingMismatch = null
        if (trip != null && assignTo != null && assignTo != DriverProfile.GUEST_ID) {
            changeRoster { r -> r.update(assignTo) { it.copy(habit = it.habit.add(bandsOf(trip), trip.timeMs)) }.select(assignTo) }
            applyDriver()
        }
        _ui.update { it.copy(habitMismatch = null) }
    }

    private fun refreshDriverUi() {
        _ui.update { it.copy(driver = driver.toUi(), drivers = driversUi()) }
    }

    /** 운전자가 바뀜: 점수·도장·말 수·잠·습관을 그 운전자 것으로 */
    private fun applyDriver() {
        val d = driver
        onEngine {
            awake = AwakeScore(d.lifetimePoints, d.badges)
            stamps = StampBook(StampBook.parse(d.stamps)) { container.restAreaDatabase.routeTotals }
            fatigue.habitSeed = seedOf(d)
            fatigue.setSleepHours(validSleep())
            publishGame(null, announce = false)
        }
        dialog.setTalkLevel(d.talkLevel, persist = false)
        _ui.update {
            it.copy(
                driver = d.toUi(),
                drivers = driversUi(),
                sleepHours = validSleep(),
                lastTripStats = lastTripByDriver[d.id],
            )
        }
    }

    /** 자동 시연: 임시 운전자 "운전자 1"(지난 12회 습관·도장 4곳·1,300점)로. 실제 운전자 기록은 그대로 */
    internal fun enterDemoDriver() {
        val now = System.currentTimeMillis()
        val stampText = runCatching {
            val db = container.restAreaDatabase
            val sim = Scenarios.nightHighway.restStopId?.let { db.findByName(it) }
            DemoDriver.stamps(db.stops, sim?.route, sim?.spokenName, now)
        }.getOrDefault("")
        demoDriver = DemoDriver.profile(stampText, now)
        lastTripByDriver.remove(DemoDriver.ID)
        applyDriver()
    }

    /** 자동 시연이 끝나면(종료 카드 닫기·중지) 원래 운전자로 */
    internal fun exitDemoDriver() {
        if (demoDriver == null) return
        demoDriver = null
        applyDriver()
    }

    private fun bandsOf(trip: TripHabit) = trip.bands.ifEmpty { mapOf(SpeedBand.HIGH to trip.overall) }

    /** 이번 운행 습관이 완성됨 → 누적할지 판단 (엔진 스레드) */
    private fun onTripHabit(trip: TripHabit) {
        val d = driver
        if (d.isGuest) return
        val j = HabitLearning.judge(trip, d.habit)
        lastHabitByDriver[d.id] = trip.overall.reversalRatePerMin to when (j.verdict) {
            HabitLearning.Verdict.ACCEPT -> "지난 운행 습관을 기준에 더했어요"
            HabitLearning.Verdict.SKIP_TIRED -> "졸린 날이라 기준에 더하지 않았어요"
            HabitLearning.Verdict.SKIP_MISMATCH -> "평소와 많이 달라 기준에 더하지 않았어요"
        }
        val text = when (j.verdict) {
            HabitLearning.Verdict.ACCEPT -> {
                updateDriver { it.copy(habit = it.habit.add(bandsOf(trip), trip.timeMs)) }
                "운전 습관 학습: ${d.name} ${d.habit.trips + 1}회째"
            }
            HabitLearning.Verdict.SKIP_TIRED -> "오늘 습관은 학습에서 뺌 (${j.reason}) · 졸린 날의 습관은 배우지 않아요"
            HabitLearning.Verdict.SKIP_MISMATCH -> {
                pendingMismatch = trip
                "오늘 습관이 ${d.name}의 평소와 많이 달라요 (${j.reason}) · 정차하면 확인할게요"
            }
        }
        val seq = ++habitNoticeSeq
        _ui.update {
            it.copy(
                driver = driver.toUi(),
                drivers = driversUi(),
                habitNotice = HabitNotice(seq, j.verdict, text),
                habitMismatch = if (j.verdict == HabitLearning.Verdict.SKIP_MISMATCH) HabitMismatchUi(d.name, j.reason) else it.habitMismatch,
            )
        }
        mainScope.launch { dialog.note(text) }
    }

    // ───────────────────────── 조작 ─────────────────────────

    fun selectScenario(scenario: DriveScenario) {
        if (autoDemo.running) autoDemo.cancel()
        stop()
        _ui.update { it.copy(scenario = scenario) }
        resetTrip()
    }

    fun selectSource(mode: SourceMode) {
        if (mode == SourceMode.VEHICLE && !vehicleSupported) return
        if (autoDemo.running) autoDemo.cancel()
        stop()
        settings.sourceModeName = mode.name
        _ui.update { it.copy(sourceMode = mode) }
        resetTrip()
        // 실차는 고르면 바로 감시 시작 (시뮬레이션은 ▶ 시작으로)
        if (mode == SourceMode.VEHICLE) start()
    }

    /** 앱을 열 때: 실차 모드면 버튼 없이 바로 감시를 시작합니다. */
    fun autoStartIfVehicle() {
        if (_ui.value.sourceMode == SourceMode.VEHICLE && runJob?.isActive != true) start()
    }

    /** 권한(차량 속도·위치 등)이 새로 허용되면 실차 데이터 연결을 다시 맺습니다. 운행 기록은 유지됩니다. */
    fun onPermissionsChanged() {
        if (_ui.value.sourceMode != SourceMode.VEHICLE) return
        runJob?.cancel()
        runJob = null
        restAreas.stop()
        source = null
        start()
    }

    // ───────────────────────── Gleo 음성비서 (GleoActionReceiver) ─────────────────────────

    /** "달토끼랑 끝말잇기" → 게임 시작. 위험 단계면 게임 대신 휴식 안내가 나가므로 false */
    fun assistantStartWordGame(): Boolean {
        val danger = currentLevel() == FatigueLevel.DANGER
        dialog.fromAssistant("끝말잇기", "끝말잇기 하자")
        return !danger
    }

    /** "졸려" → 졸림 신고 흐름(최소 '경고' 유지 + 휴게소 안내) */
    fun assistantReportSleepy() {
        dialog.fromAssistant("졸림 신고", "졸려")
    }

    /** Gleo "졸음쉼터 들르자"의 결과 */
    enum class AssistantRest { ADDED, ASKED, FAILED, NOT_FOUND }

    /**
     * "졸음쉼터 들르자" → 앞쪽 가장 가까운 휴게소를 경유지로.
     * 주행 중이면 바로 넣지 않고 달토끼가 한 번 더 물어 운전자가 승낙해야 넣습니다(ASKED).
     * (다른 앱이 Gleo 신호를 흉내 내도 운전자 확인 없이 경로가 바뀌지 않게)
     */
    suspend fun assistantAddRestStop(): Pair<RestArea?, AssistantRest> {
        val ra = runCatching { restAreas.nearestRestArea() }.getOrNull()
        if (ra != null) _ui.update { it.copy(restArea = ra) }
        if (ra == null) {
            dialog.announceRestFromAssistant(null, false)
            return null to AssistantRest.NOT_FOUND
        }
        if (isDriving()) {
            dialog.confirmRestFromAssistant()
            return ra to AssistantRest.ASKED
        }
        val ok = runCatching { restAreas.addWaypoint(ra) }.getOrDefault(false)
        dialog.announceRestFromAssistant(ra, ok)
        return ra to if (ok) AssistantRest.ADDED else AssistantRest.FAILED
    }

    // ───────────────────────── 데모 패널 시험 버튼 ─────────────────────────

    /** 시뮬레이션: 방향지시등 켜고 차선 변경(5초) → 달토끼는 말 걸기를 미룸 */
    fun injectLaneChange() {
        (source as? SimVehicleSource)?.injectLaneChange()
    }

    /** 시뮬레이션: 브레이크(3초) → 달토끼는 말 걸기를 미룸 */
    fun injectBraking() {
        (source as? SimVehicleSource)?.injectBraking()
    }

    /** 통화 중 상황 시험 (기본 15초 동안 달토끼는 먼저 말을 걸지 않음) */
    fun simulateCall(durationMs: Long = 15_000L) {
        dialog.simulateCall(durationMs)
    }

    /** 정차 화면의 "출발 전 컨디션" 버튼 */
    fun reportSleep(hours: Float) {
        val interval = onSleepReported(hours)
        dialog.noteSleep(hours, interval)
    }

    /** Gleo 음성비서가 부른 것처럼 시험 (실제와 같은 브로드캐스트 경로) */
    fun simulateGleo(action: String) {
        GleoActionReceiver.simulate(context, action)
    }

    /** 데모 패널: 시뮬레이션에서도 실제 내비에 경유지 넣기 (켜면 내비 앱이 앞으로 나올 수 있음) */
    fun setRealNaviInSim(on: Boolean) {
        settings.realNaviInSim = on
        _ui.update { it.copy(realNaviInSim = on) }
    }

    /** 데모 패널: 가벼운 연출 */
    fun setLightEffects(on: Boolean) {
        settings.lightEffects = on
        _ui.update { it.copy(lightEffects = on) }
    }

    fun setSpeed(speed: Float) {
        _ui.update { it.copy(speed = speed) }
        (source as? SimVehicleSource)?.speed?.value = speed
    }

    fun togglePause() {
        val sim = source as? SimVehicleSource ?: return
        sim.paused.value = !sim.paused.value
        _ui.update { it.copy(paused = sim.paused.value) }
    }

    fun start() {
        if (runJob?.isActive == true) return
        val state = _ui.value
        val src = source ?: createSource(state).also { source = it }
        restAreas.start()
        _ui.update { it.copy(running = true, paused = false, sourceStatus = src.status.value) }
        restAreaJob?.cancel()
        restAreaJob = mainScope.launch {
            // 시뮬레이션은 한 번, 실차는 위치가 바뀌므로 1분마다 다시 찾습니다.
            do {
                val ra = runCatching { restAreas.nearestRestArea() }.getOrNull()
                _ui.update { it.copy(restArea = ra) }
                if (_ui.value.sourceMode != SourceMode.VEHICLE) break
                delay(if (ra == null) 15_000L else 60_000L)
            } while (true)
        }
        runJob = mainScope.launch(engineDispatcher) {
            src.snapshots().collect { s -> process(s) }
            // 시뮬레이션이 끝나면 운행 종료
            withContext(Dispatchers.Main) { finishSource() }
        }
    }

    fun stop() {
        runJob?.cancel()
        runJob = null
        restAreaJob?.cancel()
        restAreaJob = null
        restAreas.stop()
        _ui.update { it.copy(running = false) }
    }

    /** 처음부터 다시 */
    fun restart() {
        stop()
        resetTrip()
        start()
    }

    /**
     * 수동 시연 한 번에 시작 (정차 화면 '수동 시연'·데모 패널, adb `--es demo night|day|long`).
     * 시뮬레이션 · 30배속 · 말 수 '수다'로 바로 출발합니다. 운전자 대답은 사람이(마이크·추천 대답 버튼).
     * 데이터 모드와 말 수는 저장하지 않으므로, 앱을 다시 열면 원래 설정(예: 실차)으로 돌아갑니다.
     *
     * @param auto 자동 시연에서 부를 때 true ([AutoDriver]가 대답을 넣음). 직접 부를 일은 없음 → [startAutoDemo]
     */
    fun startDemo(scenario: DriveScenario = Scenarios.nightHighway, speed: Float = DEMO_SPEED, auto: Boolean = false) {
        if (!auto && autoDemo.running) autoDemo.cancel()
        stop()
        // 자동 시연은 임시 운전자 "운전자 1"로, 수동 시연은 지금 운전자로
        if (auto) enterDemoDriver() else exitDemoDriver()
        _ui.update { it.copy(sourceMode = SourceMode.SIMULATION, scenario = scenario, speed = speed) }
        resetTrip()
        // 자동 시연: 마이크 대신 자동 운전자, 대사 고정(매번 같은 대사 → 미리 만든 음성을 다시 쓸 수 있음)
        dialog.autoDriver = auto
        dialog.reseed(if (auto) AUTO_SEED else null)
        giftRandom = if (auto) kotlin.random.Random(AUTO_SEED) else kotlin.random.Random.Default
        container.llm.reseed(if (auto) AUTO_SEED else null)
        dialog.setTalkLevel(TalkLevel.CHATTY, persist = false)
        // 시연은 매번 "어젯밤 몇 시간 잤어?"부터 (졸음 예보 시연)
        forceSleepCheck = true
        start()
    }

    /** 자동 시연 시작 (정차 화면 ▶ 자동 시연, 데모 패널, adb `--es demo night --ez auto true`) */
    fun startAutoDemo(scenario: DriveScenario = Scenarios.nightHighway) {
        autoDemo.begin(scenario)
    }

    /** 자동 시연 ■ 중지: 시뮬레이션도 멈추고 정차 화면으로 */
    fun stopAutoDemo() {
        autoDemo.cancel()
        stop()
        resetTrip()
    }

    /** 자동 시연이 끝나거나 중지되면 원래대로 (마이크 사용, 무작위 대사) */
    internal fun endAutoMode() {
        dialog.autoDriver = false
        dialog.reseed(null)
        container.llm.reseed(null)
    }

    /** 운행 종료(사용자) */
    fun endTrip() {
        mainScope.launch(engineDispatcher) {
            val a = fatigue.endTrip() ?: return@launch
            // 운행 마무리(운행 결과·연속 안전 운행·최고 기록·대답 속도 저장)는 updateStats의 TripEnded 처리에 있음.
            // [운행 종료]·시뮬레이션 끝으로 끝날 때도 반드시 거치게 (전에는 빠져서 운행 결과 카드가 안 나오고 기록이 안 쌓였음)
            updateStats(a)
            publish(a, force = true)
        }
    }

    private fun resetTrip() {
        source = null
        napUntilMs = -1L
        // 엔진 스레드의 상태는 엔진 스레드에서 초기화 (이후 시작하는 수집보다 먼저 실행됨)
        mainScope.launch(engineDispatcher) {
            fatigue = FatigueEngine(zone = zone).also {
                it.setSleepHours(validSleep())
                it.habitSeed = seedOf(driver)
            }
            history.clear()
            lastGraphT = -1L
            lastPublishedLevel = null
            stats = TripStats()
        }
        dialog.reset()
        _ui.update {
            it.copy(
                assessment = null, snapshot = null, history = emptyList(), stats = TripStats(),
                phaseLabel = "", progress = 0f, clock = "", paused = false, restElapsedMin = 0f,
                habitInUseTrips = 0, habitNotice = null, napRemainingMin = null,
            )
        }
    }

    private fun createSource(state: CopilotUiState): VehicleDataSource = when (state.sourceMode) {
        SourceMode.SIMULATION -> {
            // 시나리오에 실제 휴게소(공공데이터)가 정해져 있으면 그곳으로 안내하고, 쉬면 그곳 도장
            val stop = state.scenario.restStopId?.let { id -> runCatching { container.restAreaDatabase.findByName(id) }.getOrNull() }
            simStop = stop
            restAreas = SimRestAreaProvider(
                RestArea(stop?.spokenName ?: state.scenario.restAreaName, state.scenario.restAreaKm, stop?.latitude, stop?.longitude, stop?.food),
            )
            SimVehicleSource(state.scenario, zone).also { it.speed.value = state.speed }
        }
        SourceMode.VEHICLE -> {
            restAreas = VehicleRestAreaProvider(
                database = { container.restAreaDatabase },
                location = container.locationTracker,
                navigator = container.navigator,
            )
            CarVehicleSource(context, container.vehicleSignals)
        }
    }

    /** 지금 쉬는 곳이 휴게소·졸음쉼터인지: 시뮬레이션은 시나리오의 휴게소, 실차는 현재 위치 700m 안의 쉼터 */
    private fun findRestStopHere(): RestStop? = when (_ui.value.sourceMode) {
        SourceMode.SIMULATION -> simStop
        SourceMode.VEHICLE -> runCatching {
            val loc = container.locationTracker.last ?: return@runCatching null
            container.restAreaDatabase.nearestWithin(loc.latitude, loc.longitude, STAMP_RADIUS_KM)
        }.getOrNull()
    }

    private fun finishSource() {
        _ui.update { it.copy(running = false, sourceStatus = source?.status?.value ?: "") }
        if (fatigue.driveState != DriveState.PARKED) endTrip()
    }

    // ───────────────────────── 처리 (엔진 스레드) ─────────────────────────

    private fun process(s: VehicleSnapshot) {
        val a = fatigue.onSnapshot(s)
        fatigue.takeTripHabit()?.let { onTripHabit(it) }
        updateStats(a)
        if (lastGraphT < 0 || s.timestampMs - lastGraphT >= 10_000L) {
            lastGraphT = s.timestampMs
            val startT = if (stats.tripStartMs > 0) stats.tripStartMs else s.timestampMs
            history += GraphPoint(
                minute = (s.timestampMs - startT) / 60_000f,
                score = a.score,
                sensor = a.sensorScore,
                context = a.contextRisk,
                steering = a.steeringAnomaly,
                dialog = a.dialogImpairment,
                level = a.level,
            )
            if (history.size > 1_200) history.subList(0, history.size - 1_200).clear()
        }
        // 커피냅: 차량 시각으로 15분이 지나면 깨움
        val napUntil = napUntilMs
        if (napUntil > 0 && s.timestampMs >= napUntil) {
            napUntilMs = -1L
            mainScope.launch { dialog.napWake() }
        }
        val now = SystemClock.elapsedRealtime()
        val levelChanged = a.level != lastPublishedLevel
        if (a.events.isNotEmpty() || levelChanged || now - lastPublishReal >= 200L) {
            lastPublishReal = now
            lastPublishedLevel = a.level
            publish(a, snapshot = s)
        }
    }

    private fun publish(a: FatigueAssessment, snapshot: VehicleSnapshot? = null, force: Boolean = false) {
        val sim = source as? SimVehicleSource
        val restMin = if (a.driveState == DriveState.RESTING && fatigue.parkStartMs > 0) {
            (a.timestampMs - fatigue.parkStartMs) / 60_000f
        } else 0f
        val historyCopy = history.toList()
        val statsCopy = stats
        val drop = synchronized(riskDrops) {
            riskDrops.onRisk(
                a.timestampMs, visible = a.driveState == DriveState.MONITORING, risk = displayRisk(a),
                dialog = a.dialogImpairment, co2Ppm = a.co2Ppm,
            )
        }
        _ui.update {
            it.copy(
                assessment = a,
                riskDrop = drop ?: it.riskDrop,
                snapshot = snapshot ?: it.snapshot,
                history = historyCopy,
                stats = statsCopy,
                lastTripStats = if (a.events.contains(DriveEvent.TripEnded)) statsCopy else it.lastTripStats,
                reportSeq = if (a.events.contains(DriveEvent.TripEnded)) it.reportSeq + 1 else it.reportSeq,
                driver = if (a.events.contains(DriveEvent.TripEnded)) driver.toUi() else it.driver,
                phaseLabel = sim?.phaseLabel ?: "",
                progress = sim?.progress ?: 0f,
                effectiveSpeed = sim?.effectiveSpeed ?: 1f,
                sourceStatus = source?.status?.value ?: "",
                clock = clockFormat.format(Instant.ofEpochMilli(a.timestampMs).atZone(zone)),
                restElapsedMin = restMin,
                habitInUseTrips = if (fatigue.usingStoredHabit) fatigue.storedHabitTrips else 0,
                napRemainingMin = napUntilMs.takeIf { n -> n > 0 }?.let { n -> ((n - a.timestampMs) / 60_000f).coerceAtLeast(0f) },
                signalStates = if (it.sourceMode == SourceMode.VEHICLE) container.vehicleSignals.signalStates() else emptyMap(),
            )
        }
        // 대화 판단은 메인 스레드에서 (이벤트가 있으면 반드시 전달)
        mainScope.launch { dialog.onAssessment(a) }
    }

    private fun updateStats(a: FatigueAssessment) {
        var s = stats
        if (a.events.contains(DriveEvent.TripStarted)) {
            s = TripStats(tripStartMs = a.timestampMs)
            synchronized(riskDrops) { riskDrops.reset() }
            awake.startTrip()
            publishGame(null)
        }
        if (a.driveState.isDriving) lastRestDueMin = a.restDueInMin
        if (a.events.contains(DriveEvent.RestStarted)) {
            s = s.copy(rests = s.rests + 1)
            restingAt = findRestStopHere()
            // 권장 휴식 시간이 되기 전에 쉬기 시작 → 휴식을 마치면 '제때 쉬기' +200
            restOnTime = (lastRestDueMin ?: 0f) > 0f
        }
        if (a.events.contains(DriveEvent.RestCompleted)) {
            // 다시 달리기 시작하면 위험도 숫자 옆에 "-48 · 휴식"
            synchronized(riskDrops) { riskDrops.onRest(a.timestampMs) }
            // 휴식 잭팟: 휴식 +500 → 제때 쉬기 +200 → 새 도장 +200 → 모아 둔 달빛 조각 ×N 터뜨리기 → 선물 상자 하나
            val parts = ArrayList<Pair<String, Int>>()
            val rest = awake.onRestCompleted()
            parts += "휴식 완료" to rest.points
            val onTime = if (restOnTime) awake.onSafetyAction(SafetyAction.REST_ON_TIME, a.timestampMs) else null
            restOnTime = false
            if ((onTime?.points ?: 0) > 0) parts += "제때 쉬기" to onTime!!.points
            val stop = restingAt
            val stampResult = stop?.let { stamps.stamp(it, a.timestampMs) }
            val stampAward = stampResult?.let { awake.onStamp(it.isNew, it.places, it.shelters, it.bestRoute) }
            if ((stampAward?.points ?: 0) > 0) parts += "새 도장" to stampAward!!.points
            val (shardCount, shardAward) = awake.releaseShards()
            if (shardCount > 0) parts += "달빛 조각 ×$shardCount" to shardAward.points
            updateDriver { it.copy(gifts = it.gifts + 1) }
            saveGame()
            val badges = (rest.newBadges + onTime?.newBadges.orEmpty() + stampAward?.newBadges.orEmpty() + shardAward.newBadges).map { it.name }
            val total = AwakeScore.Award(parts.sumOf { it.second }, emptyList())
            val gain = gainOf(total, GainReason.REST, parts.joinToString(" · ") { it.first }, stampName = stop?.spokenName)
                .copy(parts = parts, shards = shardCount)
            if (stampResult != null) {
                // 도장 안내가 휴식 완료·새 배지 안내를 함께 말함
                mainScope.launch { dialog.announceStamp(stampResult, badges) }
                publishGame(badges.lastOrNull(), announce = false, gain = gain)
            } else {
                publishGame(badges.lastOrNull(), gain = gain)
            }
            restingAt = null
            refreshDriverUi()
        }
        if (a.driveState.isDriving && a.level > s.maxLevel) s = s.copy(maxLevel = a.level)
        // 먼저 알아챘는지: 앱이 처음 '경고'가 된 시각 vs 차량 주의 경고(DAW)가 처음 켜진 시각
        if (a.driveState.isDriving && a.level >= FatigueLevel.WARNING && s.firstWarningMs < 0) s = s.copy(firstWarningMs = a.timestampMs)
        if (a.vehicleWarningActive && s.firstVehicleWarningMs < 0) s = s.copy(firstVehicleWarningMs = a.timestampMs)
        s = s.copy(
            drivingMs = fatigue.tripDrivingMs,
            lapseEvents = fatigue.totalLapses,
            vehicleWarnings = fatigue.totalVehicleWarnings,
        )
        stats = s
        if (a.events.contains(DriveEvent.TripEnded)) {
            val d = driver
            // 평소 대답 속도도 운전자별로 쌓음 (새벽·수면 부족인 날은 빼고)
            if (!d.isGuest && HabitLearning.latencyOk(fatigue.startHour, fatigue.sleepHours)) {
                updateDriver { it.addLatency(fatigue.tripLatencySamples) }
            }
            median(fatigue.tripLatencySamples)?.let { lastLatencyByDriver[d.id] = it }
            // 안전 운행 연속 기록·주간 리포트 (게스트는 기록 안 함)
            if (!d.isGuest) TripLog.summarize(s, a.timestampMs)?.let { t -> updateDriver { it.addTrip(t) } }
            // 운행 결과: 별 3개 · 등급 · 신기록 (개인 최고 점수·콤보)
            val result = TripResult.compute(s, awake.tripPoints, awake.bestCombo, awake.tripSafetyActions, d.bestTripPoints, d.bestCombo)
            if (!d.isGuest) updateDriver { it.copy(bestTripPoints = maxOf(it.bestTripPoints, awake.tripPoints), bestCombo = maxOf(it.bestCombo, awake.bestCombo)) }
            _ui.update { it.copy(lastResult = result) }
            lastTripByDriver[d.id] = s
        }
    }

    // ───────────────────────── DialogHost ─────────────────────────

    private fun onEngine(block: () -> Unit) {
        mainScope.launch(engineDispatcher) { block() }
    }

    override fun recordBaselineResponse(latencyMs: Long) = onEngine {
        fatigue.recordBaselineResponse(latencyMs)
        stats = stats.copy(responses = stats.responses + 1, totalLatencyMs = stats.totalLatencyMs + latencyMs)
    }

    override fun recordResponse(latencyMs: Long, kind: ResponseKind) = onEngine {
        // 빠른지 판단은 기록 전에 (기록하면서 개인 기준이 바뀔 수 있음)
        val fast = fatigue.isFast(latencyMs, kind)
        // 또렷한 대답 → 이후 위험도가 실제로 내려가면 "-N · 빠른 대답"
        if (fast) {
            _ui.value.assessment?.takeIf { it.driveState.isDriving }?.let { a ->
                synchronized(riskDrops) { riskDrops.onAction(a.timestampMs, DropCause.ANSWER, "빠른 대답", displayRisk(a), a.dialogImpairment) }
            }
        }
        fatigue.recordResponse(latencyMs, kind)
        // 짧은 질문에 또렷할 때만큼 빠르게 답하면 작은 각성 확인 보너스 (게임 보너스는 onGameCorrect에서)
        if (kind == ResponseKind.SIMPLE && fast) fatigue.addBonus(BONUS_FAST_ANSWER)
        // 운행 리포트의 평균 응답 시간은 짧은 질문만으로 계산
        if (kind == ResponseKind.SIMPLE) {
            stats = stats.copy(responses = stats.responses + 1, totalLatencyMs = stats.totalLatencyMs + latencyMs)
        }
    }

    override fun onGameCorrect(latencyMs: Long, isWordGame: Boolean, verified: Boolean) = onEngine {
        // 사전에 없는 단어는 확인이 안 되므로 '빠른 정답' 보너스는 주지 않음
        val fast = verified && fatigue.isFast(latencyMs, ResponseKind.GAME)
        fatigue.addBonus(if (fast) BONUS_CORRECT_FAST else BONUS_CORRECT)
        val award = awake.onCorrect(fast, isWordGame)
        saveGame()
        publishGame(award.newBadges.lastOrNull()?.name, gain = gainOf(award, GainReason.CORRECT))
    }

    private var gainSeq = 0

    /** 행동 뒤 위험도가 실제로 내려갔는지 (점수로 낮추지 않음) */
    private val riskDrops = RiskDropTracker()

    /** 화면에 보이는 졸음 위험도 숫자 (0~100) */
    private fun displayRisk(a: FatigueAssessment): Int = (a.sensorScore * 100).roundToInt()

    private fun gainOf(award: AwakeScore.Award, reason: GainReason, label: String? = null, stampName: String? = null) =
        ScoreGain(++gainSeq, award.points, reason, award.combo, award.multiplier, award.fast, label, stampName)

    /** 졸음을 줄이는 행동 점수 (30분에 한 번·운행마다 한 번 규칙은 AwakeScore가) + 청록 연출 + 바람 종소리 */
    private fun awardSafety(action: SafetyAction) = onEngine {
        val now = _ui.value.assessment?.timestampMs ?: System.currentTimeMillis()
        // 외기 전환 → 실내 CO₂가 실제로 줄며 위험도가 내려가면 "-N · 외기 전환" (점수 규칙과 상관없이)
        if (action == SafetyAction.FRESH_AIR || action == SafetyAction.FRESH_AIR_VOICE) {
            _ui.value.assessment?.takeIf { it.driveState.isDriving }?.let { a ->
                synchronized(riskDrops) { riskDrops.onAction(a.timestampMs, DropCause.AIR, "외기 전환", displayRisk(a), a.co2Ppm) }
            }
        }
        val award = awake.onSafetyAction(action, now)
        if (award.points <= 0) return@onEngine
        saveGame()
        publishGame(award.newBadges.lastOrNull()?.name, gain = gainOf(award, GainReason.SAFETY, action.label))
        mainScope.launch { dialog.chime(SoundEffect.SAFETY) }
    }

    /** 휴식을 시작할 때 권장 휴식 시간 전이었는지 (제때 쉬기 +200) */
    private var restOnTime = false
    /** 마지막 주행 중 판단의 '권장 휴식까지 남은 분' */
    private var lastRestDueMin: Float? = null

    override fun onReactionHit(latencyMs: Long) = onEngine {
        stats = stats.copy(reactionMs = stats.reactionMs + latencyMs)
        // 반응 게임: 기준(1.2초)보다 빠르면 작은 보너스. 한 판이 쉬운 만큼 끝말잇기보다 작게.
        val fast = fatigue.isFast(latencyMs, ResponseKind.REACTION)
        fatigue.addBonus(if (fast) BONUS_REACTION_FAST else BONUS_REACTION)
        val award = awake.onCorrect(fast, isWordGame = false, isReaction = true)
        saveGame()
        publishGame(award.newBadges.lastOrNull()?.name, gain = gainOf(award, GainReason.CORRECT))
    }

    override fun onReactionMiss() = onEngine { stats = stats.copy(reactionMisses = stats.reactionMisses + 1) }

    override fun onTalkDeferred() = onEngine { stats = stats.copy(deferrals = stats.deferrals + 1) }

    /**
     * 실내 온도 2도 낮추기 (최소 차량 제어). 실차: Pleos HVAC 온도. 시뮬레이션: 성공으로 보고
     * (에뮬레이터면 Pleos 공조에도 실제로 시도해 보고 결과는 기록에만).
     */
    override suspend fun lowerTemperature(): FreshAirResult = lowerTemperatureRaw().also { r ->
        awardSafety(if (r == FreshAirResult.DONE) SafetyAction.COOL_DOWN else SafetyAction.COOL_DOWN_VOICE)
    }

    private suspend fun lowerTemperatureRaw(): FreshAirResult {
        val sim = source as? SimVehicleSource
        if (sim == null) {
            val r = runCatching { container.vehicleSignals.lowerTemperature() }.getOrDefault(FreshAirResult.UNSUPPORTED)
            dialog.note("Pleos 공조 온도 낮추기: ${freshAirText(r)}")
            return r
        }
        if (vehicleSupported && container.pleosIncluded) {
            val real = withTimeoutOrNull(NAVI_TIMEOUT_MS) {
                runCatching { container.vehicleSignals.lowerTemperature() }.getOrDefault(FreshAirResult.UNSUPPORTED)
            } ?: FreshAirResult.UNSUPPORTED
            dialog.note("Pleos 공조 온도 낮추기: ${freshAirText(real)}" + if (real != FreshAirResult.DONE) " · 시뮬레이션으로 처리" else "")
        }
        return FreshAirResult.DONE
    }

    override fun onGameWrong() = onEngine {
        awake.onWrong()
        publishGame(null)
    }

    override fun onAwakeClaim(latencyMs: Long?) = onEngine {
        // 말만으로는 낮아지지 않음. 달토끼 질문에 빠르게 답한 경우만 아주 조금.
        if (latencyMs != null && fatigue.isFast(latencyMs, ResponseKind.SIMPLE)) fatigue.addBonus(BONUS_AWAKE_CLAIM)
    }

    override fun needsSleepCheck(): Boolean = forceSleepCheck || validSleep() == null

    override fun onSleepReported(hours: Float): Int {
        forceSleepCheck = false
        awardSafety(SafetyAction.SLEEP_CHECK)
        val at = System.currentTimeMillis()
        updateDriver { it.copy(sleepHours = hours, sleepAnsweredAt = at) }
        _ui.update { it.copy(sleepHours = hours) }
        onEngine { fatigue.setSleepHours(hours) }
        // 권장 간격은 지금 시각(시뮬레이션이면 시나리오 시각) 기준
        val hour = _ui.value.assessment?.timestampMs?.let { Instant.ofEpochMilli(it).atZone(zone).hour } ?: LocalTime.now(zone).hour
        return RestPlanner.intervalMin(hour, hours)
    }

    override fun onTalkLevelChanged(level: TalkLevel) {
        updateDriver { it.copy(talkLevel = level) }
    }

    /** 달토끼가 인사할 때 부를 이름 (게스트·기본 이름 "운전자 N"은 null → 이름 없이 말함) */
    override fun driverName(): String? = driver.takeUnless { it.isGuest || DriverRoster.isDefaultName(it.name) }?.name

    override fun currentLevel(): FatigueLevel = _ui.value.assessment?.level ?: FatigueLevel.NORMAL

    private fun saveGame() {
        val points = awake.lifetimePoints
        val badges = awake.earnedIds
        val stampText = stamps.serialize()
        updateDriver { it.copy(lifetimePoints = points, badges = badges, stamps = stampText) }
    }

    private fun gameUi(recentBadge: String?): GameUi {
        val next = awake.nextPointBadge()
        return GameUi(
            breakdown = awake.tripBreakdown,
            shards = awake.shards,
            tripPoints = awake.tripPoints,
            combo = awake.combo,
            bestCombo = awake.bestCombo,
            lifetimePoints = awake.lifetimePoints,
            nextBadgeName = next?.first?.name,
            pointsToNextBadge = next?.second ?: 0,
            nextBadgeProgress = awake.nextBadgeProgress(),
            badges = Badges.all.map { BadgeUi(it.name, it.description, awake.isEarned(it)) },
            recentBadge = recentBadge,
            stampPlaces = stamps.places,
            stampTotal = runCatching { container.restAreaDatabase.size }.getOrDefault(0),
            shelterStamps = stamps.shelters,
            recentStamps = stamps.recent(8).map { StampUi(it.name, it.isShelter, it.visits, it.route) },
            routes = stamps.routeProgress().take(12).map { RouteUi(it.route, it.have, it.total) },
        )
    }

    /**
     * 게임 상태를 화면에 반영. 새 배지는 8초 동안 표시하고 대화 기록에 남깁니다.
     * @param announce false면 달토끼가 따로 알리지 않음 (도장 안내가 배지까지 함께 말할 때)
     */
    private fun publishGame(newBadge: String?, announce: Boolean = true, gain: ScoreGain? = null) {
        _ui.update { it.copy(game = gameUi(newBadge ?: it.game.recentBadge).copy(lastGain = gain ?: it.game.lastGain)) }
        if (newBadge != null) {
            mainScope.launch {
                if (announce) dialog.announceBadge(newBadge)
                delay(8_000)
                _ui.update { if (it.game.recentBadge == newBadge) it.copy(game = it.game.copy(recentBadge = null)) else it }
            }
        }
    }

    override fun recordMiss(kind: ResponseKind) = onEngine {
        fatigue.recordMiss(kind)
        stats = stats.copy(misses = stats.misses + 1)
    }

    override fun reportSleepy() {
        onEngine { fatigue.reportSleepy() }
        // 졸리다고 먼저 말해 줌 → 운행마다 한 번 +50 (솔직하게 말하도록)
        if (isDriving()) awardSafety(SafetyAction.SELF_REPORT)
    }

    override fun setInteractive(active: Boolean) {
        (source as? SimVehicleSource)?.interactive = active
    }

    override fun onIntervention(kind: Intervention) = onEngine {
        val counted = kind in setOf(
            Intervention.CHECK_IN, Intervention.OFFER_GAME, Intervention.ALERT, Intervention.SLEEPY, Intervention.FRESH_AIR,
            Intervention.BOREDOM_GAME,
        )
        if (counted) {
            stats = stats.copy(
                interventions = stats.interventions + 1,
                alerts = stats.alerts + if (kind == Intervention.ALERT) 1 else 0,
            )
        }
    }

    override fun isDriving(): Boolean = _ui.value.assessment?.driveState?.isDriving == true

    override fun tripStats(): TripStats = stats

    // ───────────────────────── 14차: 외기 전환 · 커피냅 · 게임 · 기억 ─────────────────────────

    /**
     * 외기 전환 (최소 차량 제어). 운전자가 "응"이라고 한 뒤에만 불림.
     * 실차: Pleos HVAC setHvacRecirculation(false). 시뮬레이션: 시나리오의 내기순환을 끔
     * (에뮬레이터면 Pleos 공조에도 실제로 시도해 보고 결과는 기록에만).
     */
    override suspend fun setFreshAir(): FreshAirResult = setFreshAirRaw().also { r ->
        awardSafety(if (r == FreshAirResult.DONE) SafetyAction.FRESH_AIR else SafetyAction.FRESH_AIR_VOICE)
    }

    private suspend fun setFreshAirRaw(): FreshAirResult {
        val sim = source as? SimVehicleSource
        if (sim == null) {
            val r = runCatching { container.vehicleSignals.setFreshAir() }.getOrDefault(FreshAirResult.UNSUPPORTED)
            dialog.note("Pleos 공조 외기 전환: ${freshAirText(r)}")
            return r
        }
        sim.freshAir()
        if (vehicleSupported && container.pleosIncluded) {
            val real = withTimeoutOrNull(NAVI_TIMEOUT_MS) {
                runCatching { container.vehicleSignals.setFreshAir() }.getOrDefault(FreshAirResult.UNSUPPORTED)
            } ?: FreshAirResult.UNSUPPORTED
            dialog.note("Pleos 공조 외기 전환: ${freshAirText(real)}" + if (real != FreshAirResult.DONE) " · 시뮬레이션으로 처리" else "")
        }
        return FreshAirResult.DONE
    }

    private fun freshAirText(r: FreshAirResult) = when (r) {
        FreshAirResult.DONE -> "성공"
        FreshAirResult.NO_PERMISSION -> "권한 없음 (음성으로 안내)"
        FreshAirResult.UNSUPPORTED -> "미지원 (음성으로 안내)"
    }

    override fun startNap(minutes: Int) {
        val now = _ui.value.assessment?.timestampMs ?: System.currentTimeMillis()
        napUntilMs = now + minutes * 60_000L
        _ui.update { it.copy(napRemainingMin = minutes.toFloat()) }
    }

    override fun onNapEnded() {
        napUntilMs = -1L
        _ui.update { it.copy(napRemainingMin = null) }
    }

    /** 커피냅 뒤 반응 재측정: 전보다 빨라졌으면(처음 재면 0.9초 안) +100점·'개운한 커피냅' 배지 */
    override fun onNapRetest(beforeMs: Long?, afterMs: Long?) = onEngine {
        val faster = afterMs != null && if (beforeMs != null) afterMs < beforeMs else afterMs < NAP_FRESH_MS
        stats = stats.copy(napBeforeMs = beforeMs, napAfterMs = afterMs)
        val award = awake.onNapRetest(faster)
        if (award.points > 0) {
            saveGame()
            publishGame(award.newBadges.lastOrNull()?.name, gain = gainOf(award, GainReason.NAP, "커피냅 효과"))
        }
        val text = when {
            afterMs == null -> "커피냅 재측정: 반응 없음"
            beforeMs != null -> "커피냅 재측정: 반응 ${beforeMs}ms → ${afterMs}ms" + if (faster) " · +${award.points}점" else ""
            else -> "커피냅 재측정: 반응 ${afterMs}ms" + if (faster) " · +${award.points}점" else ""
        }
        mainScope.launch { dialog.note(text) }
    }

    /** 휴게소 음식 퀴즈: 지금 노선(시연 휴게소·가까운 휴게소)의 대표 음식부터 */
    override fun foodStops(): List<Pair<String, String>> = runCatching {
        val db = container.restAreaDatabase
        val anchor = simStop ?: _ui.value.restArea?.let { ra -> db.stops.firstOrNull { it.spokenName == ra.name } }
        val withFood = db.stops.filter { !it.isShelter && !it.food.isNullOrBlank() }
        val sameRoute = anchor?.route?.let { r -> withFood.filter { it.route == r } }.orEmpty()
        (listOfNotNull(anchor?.takeIf { !it.food.isNullOrBlank() }) + sameRoute + withFood.take(FOOD_POOL))
            .distinctBy { it.spokenName }
            .take(FOOD_POOL)
            .map { it.spokenName to it.food!! }
    }.getOrDefault(emptyList())

    override fun onMiniGameCorrect(game: GameKind, detail: Int) = onEngine {
        val badges = awake.onGameCorrect(game, detail)
        if (badges.isNotEmpty()) {
            saveGame()
            publishGame(badges.last().name)
        }
    }

    override fun memory(): Map<DriverMemory.Slot, String> = driver.memory

    override fun remember(updates: Map<DriverMemory.Slot, String>) {
        val d = driver
        if (d.isGuest || updates.all { (k, v) -> d.memory[k] == v }) return
        updateDriver { it.remember(updates) }
        refreshDriverUi()
    }

    override suspend fun nearestRestArea(): RestArea? = restAreas.nearestRestArea()

    override suspend fun addWaypoint(restArea: RestArea): Boolean {
        // 운전자가 휴게소 가기를 승낙함 → 안전 행동 +100
        awardSafety(SafetyAction.REST_ACCEPT)
        val ok = restAreas.addWaypoint(restArea)
        // 시뮬레이션에서는 기본으로 실제 내비를 부르지 않음: NaviHelper가 경유지를 넣으며 내비 앱을 앞으로 띄워
        // 우리 화면이 사라지기 때문 (자동 시연 녹화 중 화면이 바뀌는 문제). 데모 패널 [실제 내비 연동 시험]을 켰을 때만.
        // 켜면 내비가 안내 중이 아닐 때 실패할 수 있으나, 시연 흐름은 그대로 이어집니다(결과는 기록에만).
        // 실제 주행(VEHICLE)은 restAreas가 NaviHelper로 넣고, 내비가 앞으로 나와 경유지를 보여 주는 것이 맞는 동작.
        val realNavi = settings.realNaviInSim && !autoDemo.running
        if (_ui.value.sourceMode == SourceMode.SIMULATION && vehicleSupported && container.pleosIncluded && realNavi) {
            val lat = restArea.latitude
            val lon = restArea.longitude
            if (lat != null && lon != null) {
                val real = withTimeoutOrNull(NAVI_TIMEOUT_MS) {
                    runCatching { container.navigator.addWaypoint(lat, lon, restArea.name) }.getOrDefault(false)
                } ?: false
                dialog.note(if (real) "Pleos 내비 경유지 추가 성공: ${restArea.name}" else "Pleos 내비: 안내 중인 경로가 없어 경유지는 시뮬레이션으로 처리")
            }
        }
        return ok
    }

    private companion object {
        /** 처음 운전자 이름 (예전 기록을 옮길 때) */
        val DEFAULT_DRIVER_NAME = DriverRoster.defaultName(1)

        /** 시연 배속 (새벽 졸음 시나리오가 약 3분에 끝남) */
        const val DEMO_SPEED = 30f

        /** 자동 시연 대사 고정용 */
        const val AUTO_SEED = 2026

        /** 시뮬레이션 중 Pleos 내비 경유지 시도 제한 시간 */
        const val NAVI_TIMEOUT_MS = 3_000L

        /** 각성 확인 보너스 양 (위험도 0~1 기준. 0.04 = 4점). 화면 위험도가 아니라 말 걸기 판단에만 씁니다. */
        const val BONUS_CORRECT_FAST = 0.04f
        const val BONUS_CORRECT = 0.025f
        const val BONUS_FAST_ANSWER = 0.02f
        const val BONUS_AWAKE_CLAIM = 0.01f
        const val BONUS_REACTION_FAST = 0.02f
        const val BONUS_REACTION = 0.01f

        /** 정차한 곳이 이 거리 안의 휴게소·쉼터면 도장 (휴게소 부지 크기를 감안) */
        const val STAMP_RADIUS_KM = 0.7

        /** 커피냅 전 기록이 없을 때 '개운함' 기준 반응 시간 */
        const val NAP_FRESH_MS = 900L

        /** 음식 퀴즈 후보 수 */
        const val FOOD_POOL = 30
    }
}
