package app.daltokki.drive.ui

import app.daltokki.drive.ui.components.LocalLightEffects
import app.daltokki.drive.ui.components.GiftRevealOverlay
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.daltokki.drive.appContainer
import app.daltokki.drive.data.vehicle.CarConnection
import app.daltokki.drive.domain.fatigue.RestPlanner
import app.daltokki.drive.domain.model.DriveState
import app.daltokki.drive.domain.sim.DriveScenario
import app.daltokki.drive.domain.sim.Scenarios
import app.daltokki.drive.engine.CopilotEngine
import app.daltokki.drive.BuildConfig
import app.daltokki.drive.engine.SourceMode
import app.daltokki.drive.service.CopilotService
import app.daltokki.drive.ui.screens.AutoDemoOverlay
import app.daltokki.drive.ui.screens.AutoDemoTopBar
import app.daltokki.drive.ui.screens.DemoPanel
import app.daltokki.drive.ui.screens.DrivingScreen
import app.daltokki.drive.ui.screens.ParkedScreen
import app.daltokki.drive.ui.screens.TripReportDialog
import app.daltokki.drive.ui.theme.LunaColors
import app.daltokki.drive.ui.theme.LunaTheme
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

class MainActivity : ComponentActivity() {

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        appContainer.speechInput.refreshStatus()
        // 차량 속도·핸들·위치 권한이 새로 허용됐으면 실차 연결을 다시 맺음
        appContainer.engine.onPermissionsChanged()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val automotive = CarConnection.isAutomotive(this)
        val missing = missingPermissions(automotive)
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
        CopilotService.start(this)

        val engine = appContainer.engine
        // adb로 시연 시작(--es demo night|day|long)이면 그 시나리오로 바로 출발.
        // 아니면 차량에서는 앱을 열면 바로 감시 시작(실차 모드). 휴대폰은 화면의 ▶ 시연 시작 버튼으로.
        // (화면 회전 등으로 다시 만들어질 때는 같은 시연을 또 시작하지 않음)
        val demoStarted = savedInstanceState == null && startDemoFromIntent(intent)
        if (!demoStarted) engine.autoStartIfVehicle()
        val restrictedFlow = CarConnection.distractionRestrictedFlow(this)

        setContent {
            LunaTheme {
                val restricted by restrictedFlow.collectAsStateWithLifecycle(initialValue = false)
                ScaledToWindow {
                    LunaRoot(engine, restricted, automotive)
                }
            }
        }
    }

    /** 앱이 이미 떠 있을 때 adb로 다시 시연 시작 명령을 보낸 경우 (launchMode=singleTask) */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        startDemoFromIntent(intent)
    }

    /**
     * 명령줄 시연 시작:
     * adb -e shell am start -n app.daltokki.drive/.ui.MainActivity --es demo night
     * (night = 새벽 고속도로 졸음, day = 오전 정상 주행, long = 오후 2시간 연속)
     * 자동 시연(운전자 대답 자동): 뒤에 --ez auto true
     */
    private fun startDemoFromIntent(intent: Intent?): Boolean {
        val scenario = demoScenario(intent?.getStringExtra(EXTRA_DEMO)) ?: return false
        // 보안: 이 화면은 다른 앱도 열 수 있어서, 출시 빌드에서 실차 감시 중이면 시연 명령을 무시합니다
        // (다른 앱이 시연 신호로 실제 차의 졸음 감시를 시뮬레이션으로 바꾸지 못하게)
        val engine = appContainer.engine
        if (!BuildConfig.DEBUG && engine.ui.value.sourceMode == SourceMode.VEHICLE) {
            intent?.removeExtra(EXTRA_DEMO)
            intent?.removeExtra(EXTRA_AUTO)
            return false
        }
        val auto = intent?.getBooleanExtra(EXTRA_AUTO, false) == true
        if (auto) appContainer.engine.startAutoDemo(scenario) else appContainer.engine.startDemo(scenario)
        // 한 번 처리한 명령은 지움 (최근 앱에서 다시 열 때 또 시작하지 않게)
        intent?.removeExtra(EXTRA_DEMO)
        intent?.removeExtra(EXTRA_AUTO)
        return true
    }

    private fun missingPermissions(automotive: Boolean): List<String> {
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (automotive) {
            // 위치는 실차 모드에서 가까운 휴게소를 찾을 때만 씁니다(휴대폰 시뮬레이션에서는 요청하지 않음).
            perms += Manifest.permission.ACCESS_FINE_LOCATION
            perms += Manifest.permission.ACCESS_COARSE_LOCATION
            perms += "android.car.permission.CAR_SPEED"
            perms += PLEOS_RUNTIME_PERMISSIONS
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) perms += Manifest.permission.POST_NOTIFICATIONS
        return perms.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
    }

    private companion object {
        const val EXTRA_DEMO = "demo"
        const val EXTRA_AUTO = "auto"

        fun demoScenario(key: String?): DriveScenario? = when (key?.trim()?.lowercase()) {
            null, "" -> null
            "night", "1", "night_highway" -> Scenarios.nightHighway
            "day", "2", "day_normal" -> Scenarios.dayNormal
            "long", "3", "long_trip" -> Scenarios.longTrip
            else -> Scenarios.nightHighway
        }

        /**
         * Pleos 차량 권한. 런타임 허용이 필요 없는 권한이면 요청해도 바로 허용 상태로 돌아오고,
         * 이 기기에 없는 권한이면 무시됩니다.
         */
        val PLEOS_RUNTIME_PERMISSIONS = listOf(
            "pleos.car.permission.READ_CAR_STEERING",
            "pleos.car.permission.CAR_LIGHTS",
            "pleos.car.permission.CAR_SAFETY",
            "pleos.car.permission.READ_ADAS_STATES",
            "pleos.car.permission.CAR_CLIMATE",
            "pleos.car.permission.CAR_DRIVE",
            "pleos.car.permission.NAVI_ROUTE",
            "pleos.car.permission.TTS_SERVICE",
            "pleos.car.permission.STT_SERVICE",
        )
    }
}

@Composable
private fun LunaRoot(engine: CopilotEngine, uxRestricted: Boolean, automotive: Boolean) {
    val state by engine.ui.collectAsStateWithLifecycle()
    val transcript by engine.dialog.transcript.collectAsStateWithLifecycle()
    val lastLine by engine.dialog.lastLunaLine.collectAsStateWithLifecycle()
    val speaking by engine.dialog.speaking.collectAsStateWithLifecycle()
    val listening by engine.dialog.listening.collectAsStateWithLifecycle()
    val talkLevel by engine.dialog.talkLevel.collectAsStateWithLifecycle()
    val busy by engine.dialog.busy.collectAsStateWithLifecycle()
    val askingRest by engine.dialog.askingRest.collectAsStateWithLifecycle()
    val suggestions by engine.dialog.suggestions.collectAsStateWithLifecycle()
    val driverSaid by engine.dialog.driverSaid.collectAsStateWithLifecycle()
    val inCall by engine.dialog.inCallState.collectAsStateWithLifecycle()
    val inGame by engine.dialog.inGame.collectAsStateWithLifecycle()
    val autoUi by engine.autoDemo.ui.collectAsStateWithLifecycle()
    val container = LocalContext.current.appContainer
    val ttsStatus by container.speechOutput.status.collectAsStateWithLifecycle()
    val sttStatus by container.speechInput.status.collectAsStateWithLifecycle()
    // 데모 패널: 처음에는 숨김 → 차에서 매일 보는 일상 화면이 먼저. 시연은 왼쪽 위 '데모' → 패널에서 시작
    var showDemo by rememberSaveable { mutableStateOf(false) }
    // 운행 리포트: 운행이 끝나면 저절로 (자동 시연은 종료 카드가 대신), 정차 화면 '지난 운행'을 눌러도
    var reportOpen by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.reportSeq) {
        if (state.reportSeq > 0 && !autoUi.active) reportOpen = true
    }

    val driveState = state.assessment?.driveState ?: DriveState.PARKED
    // 실제 주행 중 화면 제한이 걸리면 데모 패널과 정차 화면은 숨기고 주행 화면만 보여줍니다.
    val showDriving = uxRestricted || driveState.isDriving
    // 자동 시연 중에는 데모 패널을 숨겨 화면을 깔끔하게 (녹화용)
    val demoVisible = showDemo && !uxRestricted && !autoUi.active
    // 왼쪽 위 '데모' 버튼 (자동 시연 중에는 그 자리가 장면 자막 줄)
    val demoPill = !uxRestricted && !autoUi.active

    val mainScreen: @Composable (Modifier) -> Unit = { m ->
        Box(m) {
            if (showDriving) {
                DrivingScreen(
                    assessment = state.assessment,
                    game = state.game,
                    restArea = state.restArea,
                    busy = busy,
                    askingRest = askingRest,
                    lastLunaLine = lastLine,
                    listening = listening,
                    speaking = speaking,
                    driverLine = driverSaid,
                    inCall = inCall,
                    driverName = state.driver?.takeUnless { it.guest }?.name,
                    habitTrips = state.habitInUseTrips,
                    // 왼쪽 위에 겹쳐 놓인 '데모' 버튼을 피해 머리글을 오른쪽으로
                    headerStartInset = if (demoPill) DEMO_PILL_INSET else 0.dp,
                    onTalk = engine.dialog::pushToTalk,
                    onPickGame = { engine.dialog.startGame(it) },
                    inGame = inGame,
                    onStopGame = engine.dialog::stopGame,
                    restricted = uxRestricted,
                    accessory = state.driver?.wearing,
                    riskDrop = state.riskDrop,
                    onRest = engine.dialog::requestRest,
                    onAcceptRest = engine.dialog::acceptRest,
                    onDeclineRest = engine.dialog::declineRest,
                )
            } else {
                ParkedScreen(
                    assessment = state.assessment,
                    stats = state.stats,
                    lastTrip = state.lastTripStats,
                    game = state.game,
                    talkLevel = talkLevel,
                    restElapsedMin = state.restElapsedMin,
                    lastLunaLine = lastLine,
                    speaking = speaking,
                    listening = listening,
                    // [말놀이 게임] → 게임 고르기 창 (알아서 골라 줘 / 7가지). 게임 중이면 [게임 그만]
                    onPickGame = { engine.dialog.startGame(it) },
                    inGame = inGame,
                    onStopGame = engine.dialog::stopGame,
                    onOpenReport = { reportOpen = true },
                    onTalk = engine.dialog::pushToTalk,
                    onEndTrip = engine::endTrip,
                    onTalkLevel = { engine.dialog.setTalkLevel(it) },
                    sleepHours = state.sleepHours,
                    // 지금(시뮬레이션이면 시나리오 시각) 출발한다면의 권장 휴식 간격
                    restIntervalMin = RestPlanner.intervalMin(
                        state.assessment?.timestampMs?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).hour }
                            ?: LocalTime.now().hour,
                        state.sleepHours,
                    ),
                    onSleep = engine::reportSleep,
                    driver = state.driver,
                    drivers = state.drivers,
                    habitMismatch = state.habitMismatch,
                    // 운행 중(휴식 포함)·자동 시연 중에는 운전자를 바꾸지 않음
                    driverEditable = !autoUi.active && state.driver?.demo != true &&
                        (state.assessment?.driveState ?: DriveState.PARKED) == DriveState.PARKED,
                    onSelectDriver = engine::selectDriver,
                    onAddDriver = engine::addDriver,
                    onRenameDriver = engine::renameDriver,
                    onRemoveDriver = engine::removeDriver,
                    onResetHabit = engine::resetDriverHabit,
                    onResolveMismatch = engine::resolveHabitMismatch,
                    onClearMemory = engine::clearDriverMemory,
                    napRemainingMin = state.napRemainingMin,
                    onNap = engine.dialog::startNap,
                    askDriverOnStart = state.askDriverOnStart && !demoVisible,
                    onDriverPrompted = engine::onDriverPrompted,
                    onOpenGift = engine::openGift,
                    onWear = engine::wear,
                    onSound = { e, step -> engine.dialog.playNow(e, step) },
                )
            }
        }
    }
    val demoPanel: @Composable (Modifier) -> Unit = { m ->
        DemoPanel(
            state = state,
            transcript = transcript,
            suggestions = suggestions,
            talkLevel = talkLevel,
            onTalkLevel = { engine.dialog.setTalkLevel(it) },
            ttsStatus = ttsStatus,
            sttStatus = sttStatus,
            llmName = container.llm.name,
            onSource = engine::selectSource,
            onScenario = engine::selectScenario,
            onSpeed = engine::setSpeed,
            onStart = engine::start,
            onPause = engine::togglePause,
            onRestart = engine::restart,
            onEndTrip = engine::endTrip,
            onSend = engine.dialog::submitText,
            onLaneChange = engine::injectLaneChange,
            onBraking = engine::injectBraking,
            onGleo = engine::simulateGleo,
            modifier = m,
            onDemo = { engine.startDemo(it) },
            onCallTest = { engine.simulateCall() },
            // 자동 시연을 누르면 패널을 닫고 시작 (녹화 화면이 깔끔하게, 끝나도 닫힌 채로)
            onAutoDemo = {
                showDemo = false
                engine.startAutoDemo(it)
            },
            onRealNavi = engine::setRealNaviInSim,
            onLightEffects = engine::setLightEffects,
        )
    }

    // 가벼운 연출 (입자 절반·빛 번짐 없음): 아래 모든 화면의 연출에 전달
    CompositionLocalProvider(LocalLightEffects provides state.lightEffects) {
    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().background(LunaColors.Background)) {
        // 자동 시연 중: 맨 위 줄은 장면 자막 + "자동 시연" 표시 + 중지
        if (autoUi.active && !uxRestricted) {
            AutoDemoTopBar(autoUi, onStop = engine::stopAutoDemo)
        }
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            // Row/Column 안에서는 BoxWithConstraints의 maxWidth를 바로 못 쓰므로(DSL 범위 제한) 먼저 꺼내 둠
            val boxWidth = maxWidth
            val wide = boxWidth >= 900.dp
            val portrait = maxHeight > boxWidth
            val demoWidth = minOf(560.dp, boxWidth * 0.45f)
            when {
                !demoVisible -> mainScreen(Modifier.fillMaxSize())
                // 차량 디스플레이·태블릿: 오른쪽에 데모 패널
                wide -> Row(Modifier.fillMaxSize()) {
                    mainScreen(Modifier.weight(1f).fillMaxHeight())
                    demoPanel(Modifier.width(demoWidth).fillMaxHeight())
                }
                // 휴대폰 세로: 위 앱 화면, 아래 데모 패널
                portrait -> Column(Modifier.fillMaxSize()) {
                    mainScreen(Modifier.fillMaxWidth().weight(0.58f))
                    demoPanel(Modifier.fillMaxWidth().weight(0.42f))
                }
                // 휴대폰 가로: 데모 패널을 화면 전체로 (닫으면 앱 화면)
                else -> demoPanel(Modifier.fillMaxSize())
            }
        }
    }
        // 데모 패널 버튼: 왼쪽 위에 작고 반투명하게 겹쳐 둠 (화면 높이를 쓰지 않음, 일상 화면을 가리지 않게)
        if (demoPill) {
            Button(
                onClick = { showDemo = !showDemo },
                modifier = Modifier.padding(start = 10.dp, top = 10.dp).height(40.dp),
                shape = RoundedCornerShape(20.dp),
                contentPadding = PaddingValues(horizontal = 14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = LunaColors.SurfaceHigh.copy(alpha = 0.85f),
                    contentColor = LunaColors.TextSecondary,
                ),
            ) {
                Text(if (demoVisible) "데모 ✕" else "데모 ☰", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }
        // 운행 리포트 (정차 중에만)
        val lastTrip = state.lastTripStats
        if (reportOpen && lastTrip != null && !showDriving && !autoUi.active) {
            TripReportDialog(
                lastTrip, state.history, state.driver, onDismiss = { reportOpen = false }, breakdown = state.game.breakdown,
                result = state.lastResult, onSound = { e, step -> engine.dialog.playNow(e, step) },
            )
        }
        // 달빛 선물 상자 열기 (정차 중, 화면 전체 위에)
        if (!showDriving) {
            GiftRevealOverlay(state.giftReveal, onSound = { e, step -> engine.dialog.playNow(e, step) }, onDismiss = engine::dismissGift)
        }
        // 자동 시연 카운트다운·종료 카드 (화면 전체 위에)
        AutoDemoOverlay(
            autoUi, onDismiss = engine.autoDemo::dismiss, report = state.lastTripStats,
            result = state.lastResult, onSound = { e, step -> engine.dialog.playNow(e, step) },
        )
    }
    }
}

/**
 * 창 크기에 맞춰 화면 전체를 키웁니다.
 * 기준 1280×720dp 창에서 1배, 그보다 큰 창은 1~2배 (휴대폰은 1배 그대로).
 * 예) Pleos 에뮬레이터 2560×1440 · 160dpi 전체 화면 → 약 1.8배라 글씨·버튼이 차량 화면에 맞게 보임.
 * 16:9보다 세로가 긴 창(예: Pleos Connect 에뮬레이터 가운데 화면)은 기준 폭을 1120dp로 잡아
 * 남는 세로 공간만큼 조금 더 키웁니다(약 1.1~1.25배).
 */
@Composable
private fun ScaledToWindow(content: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val tall = maxHeight / maxWidth > TALL_RATIO
        val baseWidth = if (tall) BASE_WIDTH_TALL else BASE_WIDTH
        val scale = minOf(maxWidth / baseWidth, maxHeight / BASE_HEIGHT).coerceIn(1f, MAX_SCALE)
        val base = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(base.density * scale, base.fontScale)) {
            content()
        }
    }
}

/** '데모' 버튼 폭만큼 주행 화면 머리글을 오른쪽으로 */
private val DEMO_PILL_INSET = 76.dp

private val BASE_WIDTH = 1280.dp
private val BASE_WIDTH_TALL = 1120.dp
/** 세로/가로 비율이 이보다 크면(16:9 = 0.5625보다 세로가 김) 기준 폭을 줄임 */
private const val TALL_RATIO = 0.6f
private val BASE_HEIGHT = 720.dp
private const val MAX_SCALE = 2f
