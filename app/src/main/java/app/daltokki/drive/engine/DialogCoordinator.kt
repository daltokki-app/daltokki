package app.daltokki.drive.engine

import android.os.SystemClock
import app.daltokki.drive.ai.ChatTurn
import app.daltokki.drive.ai.LlmClient
import app.daltokki.drive.data.nav.RestArea
import app.daltokki.drive.domain.dialog.DialogPolicy
import app.daltokki.drive.domain.dialog.DriverMemory
import app.daltokki.drive.domain.dialog.Intervention
import app.daltokki.drive.domain.dialog.LunaLines
import app.daltokki.drive.domain.dialog.ReplyContext
import app.daltokki.drive.domain.dialog.TalkLevel
import app.daltokki.drive.domain.dialog.UtteranceIntent
import app.daltokki.drive.domain.fatigue.ResponseKind
import app.daltokki.drive.domain.fatigue.RestPlanner
import app.daltokki.drive.domain.game.ChosungGame
import app.daltokki.drive.domain.game.DigitSpan
import app.daltokki.drive.domain.game.FoodQuestion
import app.daltokki.drive.domain.game.GameKind
import app.daltokki.drive.domain.game.GamePicker
import app.daltokki.drive.domain.game.QuizBank
import app.daltokki.drive.domain.game.Riddles
import app.daltokki.drive.domain.game.StampResult
import app.daltokki.drive.domain.game.WordChainEngine
import app.daltokki.drive.domain.game.WordDictionary
import app.daltokki.drive.domain.model.DriveEvent
import app.daltokki.drive.domain.model.FatigueAssessment
import app.daltokki.drive.domain.model.FatigueLevel
import app.daltokki.drive.domain.model.TripStats
import app.daltokki.drive.domain.sim.DemoCueSheet
import app.daltokki.drive.voice.AudioEnvironment
import app.daltokki.drive.voice.NoAudioEnvironment
import app.daltokki.drive.voice.NoSoundEffects
import app.daltokki.drive.voice.Reply
import app.daltokki.drive.voice.SoundEffect
import app.daltokki.drive.voice.SoundEffects
import app.daltokki.drive.voice.SpeechInput
import app.daltokki.drive.voice.SpeechOutput
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.random.Random

/** 대화 코디네이터가 엔진에 요청하는 일들 */
interface DialogHost {
    fun recordBaselineResponse(latencyMs: Long)
    /**
     * 달토끼 질문에 대답함. 짧은 질문([ResponseKind.SIMPLE])과 게임([ResponseKind.GAME])은 정상 응답 시간이 달라 따로 판정합니다.
     * 짧은 질문에 빠르게 답하면 엔진이 작은 각성 확인 보너스를 줍니다.
     */
    fun recordResponse(latencyMs: Long, kind: ResponseKind)
    fun recordMiss(kind: ResponseKind)
    fun reportSleepy()
    /**
     * 끝말잇기·퀴즈 정답 → 각성 확인 보너스 + 점수·콤보.
     * verified가 false면(사전에 없는 단어) 빠른 정답 보너스는 주지 않습니다.
     */
    fun onGameCorrect(latencyMs: Long, isWordGame: Boolean, verified: Boolean)
    /** 오답·시간 초과 → 콤보 끊김 */
    fun onGameWrong()
    /** 반응 게임에서 "달!"에 반응함 → 빠르면 작은 각성 확인 보너스 + 점수·콤보 */
    fun onReactionHit(latencyMs: Long) = Unit
    /** 출발 전 컨디션(어젯밤 잠)을 아직 묻지 않았는지 (최근 16시간 안에 답이 없으면 true) */
    fun needsSleepCheck(): Boolean = false
    /** 어젯밤 잔 시간을 들음 → 저장·반영하고 권장 휴식 간격(분)을 돌려줌 */
    fun onSleepReported(hours: Float): Int = RestPlanner.DEFAULT_INTERVAL_MIN
    /** "안 졸려". latencyMs가 null이면 운전자가 먼저 한 말(보너스 없음). */
    fun onAwakeClaim(latencyMs: Long?)
    /** 대화 중이면 시뮬레이션을 1배속으로 */
    fun setInteractive(active: Boolean)
    fun onIntervention(kind: Intervention)
    fun onTalkLevelChanged(level: TalkLevel)
    fun isDriving(): Boolean
    fun currentLevel(): FatigueLevel
    fun tripStats(): TripStats
    suspend fun nearestRestArea(): RestArea?
    suspend fun addWaypoint(restArea: RestArea): Boolean
    /** 지금 운전자 이름 (달토끼가 인사할 때 부름). 게스트·모르면 null */
    fun driverName(): String? = null

    // ── 14차 ──
    /** 외기 전환(최소 차량 제어). 권한·차량이 안 되면 NO_PERMISSION/UNSUPPORTED → 음성 안내로 대체 */
    suspend fun setFreshAir(): FreshAirResult = FreshAirResult.UNSUPPORTED
    /** 커피냅 시작: 엔진이 차량 시각으로 [minutes]분 뒤 [DialogCoordinator.napWake]를 부름 */
    fun startNap(minutes: Int) = Unit
    /** 커피냅이 끝남(깨움·출발) */
    fun onNapEnded() = Unit
    /** 커피냅 뒤 반응 재측정 결과 (평균 ms, 모르면 null) */
    fun onNapRetest(beforeMs: Long?, afterMs: Long?) = Unit
    /** 휴게소 음식 퀴즈용 (부르는 이름, 대표 음식). 가까운·같은 노선 먼저 */
    fun foodStops(): List<Pair<String, String>> = emptyList()
    /** 바쁜 순간(차선 변경·제동)·통화가 시작돼 달토끼가 조용히 기다림 (운행 리포트 "기다린 순간 N번") */
    fun onTalkDeferred() = Unit
    /** 실내 온도 2도 낮추기 (최소 차량 제어). 안 되면 NO_PERMISSION/UNSUPPORTED → 음성 안내 */
    suspend fun lowerTemperature(): FreshAirResult = FreshAirResult.UNSUPPORTED
    /** 반응 게임 놓침 (운행 리포트) */
    fun onReactionMiss() = Unit
    /** 새 게임(초성·음식·숫자·수수께끼) 정답 → 게임 배지 */
    fun onMiniGameCorrect(game: GameKind, detail: Int = 0) = Unit
    /** 기억하는 대화: 지금 운전자의 기억 */
    fun memory(): Map<DriverMemory.Slot, String> = emptyMap()
    /** 운전자 말에서 기억할 것을 찾음 → 저장 */
    fun remember(updates: Map<DriverMemory.Slot, String>) = Unit
}

/** 외기 전환 결과 */
enum class FreshAirResult { DONE, NO_PERMISSION, UNSUPPORTED }

enum class Speaker { LUNA, DRIVER, SYSTEM }

/**
 * 지금 기다리는 대답 (자동 시연의 자동 운전자가 봄). seq로 같은 질문인지 구분.
 * @param expected 정답이 정해진 질문(퀴즈·숫자·수수께끼)의 정답 (자동 시연용)
 */
data class AwaitingReply(val context: ReplyContext, val hints: List<String>, val seq: Long, val expected: String? = null)

data class TranscriptLine(val speaker: Speaker, val text: String, val id: Long = nextId()) {
    companion object {
        private var counter = 0L
        @Synchronized fun nextId() = ++counter
    }
}

/**
 * 달토끼의 대화 흐름을 실행합니다. (메인 스레드에서 동작)
 *
 * 원칙: 화면이 먼저, 소리는 마지막. 말 수 설정(최소/보통/수다)에 따라 먼저 말하는 횟수가 정해집니다.
 * - 피로 단계에 따라 DialogPolicy가 정한 개입(말 걸기, 휴식·놀이 제안, 위험 경고)을 실행
 * - 질문 후 응답 지연·무응답을 측정해 엔진에 전달 (→ 졸음 판정의 대화 신호)
 * - 게임 정답·빠른 대답은 각성 보너스로 위험도를 낮춤 (위험 단계에서는 게임 멈춤)
 * - 게임 난이도는 피로에 맞춤: 평소 끝말잇기 6턴·퀴즈 3문제 → '경고'면 짧게(3턴·2문제)·반응 게임 권함 → '위험'이면 중단하고 휴식
 * - 게임 효과음(정답·콤보·배지·도장)으로 화면을 보지 않아도 게임하는 느낌
 * - 달토끼가 말하는 동안 음악 소리를 줄이고(덕킹), 통화 중에는 먼저 말을 걸지 않음
 * - 음성 인식이 듣지 못한 상황(인식기 고장·없음)의 '대답 없음'은 졸음 판단(무응답)에 넣지 않음
 * - 운전자가 먼저 한 말(마이크 버튼/화면 입력)을 의도별로 처리
 */
class DialogCoordinator(
    private val scope: CoroutineScope,
    private val tts: SpeechOutput,
    private val stt: SpeechInput,
    private val llm: LlmClient,
    private val host: DialogHost,
    private val dictionary: () -> WordDictionary,
    private val quizBank: () -> QuizBank,
    initialTalkLevel: TalkLevel = TalkLevel.MIN,
    private val sfx: SoundEffects = NoSoundEffects,
    /** 반응 게임에서 "달!"을 외치기 전 기다리는 시간(무작위). 테스트에서는 짧게 */
    private val reactionWaitMs: LongRange = 1_500L..4_000L,
    /** 덕킹·통화 상태 */
    private val audio: AudioEnvironment = NoAudioEnvironment,
    /** 경과 시간(ms). 음성 인식 결과(Reply)와 같은 기준이어야 함. 테스트에서는 바꿔 끼움 */
    private val now: () -> Long = { SystemClock.elapsedRealtime() },
    /**
     * 자동 시연에서 한 문장을 화면에 보여주는 최소 시간(ms).
     * 소리가 안 나는 환경에서도 읽을 수 있고, 나중에 편집으로 음성을 입힐 자리가 생깁니다. 테스트에서는 0.
     */
    private val autoPaceMs: (String) -> Long = { text -> DemoCueSheet.paceMs(text) },
) {
    private val policy = DialogPolicy()
    private var random: Random = Random.Default

    /**
     * 자동 시연: 운전자 대답을 '자동 운전자'가 넣습니다(engine/AutoDriver).
     * - 음성 인식(마이크)은 쓰지 않음 (노트북 마이크 문제·자기 목소리 인식 방지)
     * - 대답이 없으면 무응답으로 셈 (자동 운전자가 일부러 대답하지 않는 장면)
     * - 문장마다 최소 표시 시간을 지킴
     */
    @Volatile var autoDriver: Boolean = false

    /** 자동 시연에서 매번 같은 대사가 나오도록 무작위 선택을 고정 (null이면 원래대로) */
    fun reseed(seed: Int?) {
        random = if (seed == null) Random.Default else Random(seed)
    }

    private var awaitSeq = 0L
    private val _awaiting = MutableStateFlow<AwaitingReply?>(null)
    /** 지금 기다리는 대답 (없으면 null) */
    val awaiting: StateFlow<AwaitingReply?> = _awaiting

    /** 운전자가 방금 한 말 (주행 화면 말풍선용, 잠깐 보였다 사라짐) */
    private val _driverSaid = MutableStateFlow<String?>(null)
    val driverSaid: StateFlow<String?> = _driverSaid
    private var driverSaidJob: Job? = null

    // ── 14차: 커피냅·게임 ──
    /** 커피냅 중 (휴게소에서 15분). 이 동안 도장·휴식 완료 안내는 깬 뒤로 미룸 */
    private val _napping = MutableStateFlow(false)
    val napping: StateFlow<Boolean> = _napping
    private val afterNap = ArrayList<suspend () -> Unit>()
    /** 마지막 반응 게임 평균(ms) — 커피냅 전후 비교용 */
    private var lastReactionAvgMs: Long? = null
    /** 최근 한 게임 (같은 게임이 연달아 나오지 않게) */
    private val recentGames = ArrayList<GameKind>()
    /** 마지막으로 게임을 시작한 차량 시각 (지루함 예방 말놀이 간격) */
    private var lastGameAt: Long? = null
    /** 게임 중 (화면의 [게임 그만] 버튼) */
    private val _inGame = MutableStateFlow(false)
    val inGame: StateFlow<Boolean> = _inGame

    /** 통화·바쁜 순간이 이어지는 중 (한 번에 한 번만 셈) */
    private var callEpisode = false
    private var busyEpisode = false

    /** 통화 중인지 (화면 표시용: 실제 통화 또는 데모 '통화 중' 시험) */
    private val _inCallState = MutableStateFlow(false)
    val inCallState: StateFlow<Boolean> = _inCallState

    private val _transcript = MutableStateFlow<List<TranscriptLine>>(emptyList())
    val transcript: StateFlow<List<TranscriptLine>> = _transcript

    private val _busy = MutableStateFlow<Intervention?>(null)
    val busy: StateFlow<Intervention?> = _busy

    private val _talkLevel = MutableStateFlow(initialTalkLevel)
    val talkLevel: StateFlow<TalkLevel> = _talkLevel

    private val _listening = MutableStateFlow(false)
    val listening: StateFlow<Boolean> = _listening

    private val _speaking = MutableStateFlow(false)
    val speaking: StateFlow<Boolean> = _speaking

    private val _lastLunaLine = MutableStateFlow("")
    val lastLunaLine: StateFlow<String> = _lastLunaLine

    /**
     * 지금 질문에 대한 추천 대답. 데모 패널에 버튼으로 보여줍니다.
     * (에뮬레이터에서는 한글 키보드 입력이 불편하므로 버튼 한 번으로 대답할 수 있게)
     */
    private val _suggestions = MutableStateFlow<List<String>>(emptyList())
    val suggestions: StateFlow<List<String>> = _suggestions

    /** 지금 휴게소 경로 추가 여부를 묻고 있는지 (화면의 "경로에 추가/괜찮아요" 버튼용) */
    private val _askingRest = MutableStateFlow(false)
    val askingRest: StateFlow<Boolean> = _askingRest

    private var currentJob: Job? = null
    private var pendingReply: CompletableDeferred<Reply>? = null
    private val chatHistory = ArrayDeque<ChatTurn>()
    private var lastAssessment: FatigueAssessment? = null
    /** 바쁜 순간에 '대기' 기록을 한 번만 남기기 위함 */
    private var busyNoted = false
    /** 바쁜 순간 때문에 위험 경고를 미루기 시작한 시각 */
    private var alertDeferSince = -1L

    private val talk: TalkLevel get() = _talkLevel.value

    /** 효과음: 대화 중에 생긴 효과음(배지 등)은 다음 말 앞에 재생. 동시에 겹치지 않게 하나씩. */
    private val sfxQueue = ArrayDeque<SoundEffect>()
    private val sfxLock = Mutex()
    /** 정답 효과음의 음 높이 (콤보가 이어질수록 올라감) */
    private var sfxCombo = 0

    /** 마지막 대답 기다리기에서 음성 인식이 제대로 듣고 있었는지 (아니면 '대답 없음'을 무응답으로 세지 않음) */
    private var lastWaitHeard = true
    /** '무응답으로 세지 않음' 기록을 한 운행에 한 번만 남기기 위함 */
    private var unheardNoted = false
    /** 통화 중 '대기' 기록을 한 번만 */
    private var callNoted = false
    /** 데모 패널 '통화 중 시험': 이 시각(경과 시간 기준)까지 통화 중으로 봄 */
    private var simulatedCallUntil = -1L

    /** 통화 중인지 (실제 통화 또는 데모의 통화 시험) */
    val inCall: Boolean
        get() = audio.inCall || (simulatedCallUntil > 0 && now() < simulatedCallUntil)

    fun reset() {
        currentJob?.cancel()
        currentJob = null
        tts.stop()
        policy.reset()
        busyNoted = false
        alertDeferSince = -1L
        _busy.value = null
        _listening.value = false
        _speaking.value = false
        _transcript.value = emptyList()
        _lastLunaLine.value = ""
        _suggestions.value = emptyList()
        _askingRest.value = false
        _awaiting.value = null
        _driverSaid.value = null
        chatHistory.clear()
        pendingReply = null
        sfxQueue.clear()
        sfxCombo = 0
        unheardNoted = false
        callNoted = false
        callEpisode = false
        busyEpisode = false
        _napping.value = false
        afterNap.clear()
        lastReactionAvgMs = null
        recentGames.clear()
        lastGameAt = null
        _inGame.value = false
        audio.unduck()
    }

    /** 정차 화면에서 컨디션(수면)을 고르면 기록에 남김 */
    fun noteSleep(hours: Float, intervalMin: Int) {
        addLine(Speaker.SYSTEM, LunaLines.sleepLog(hours, intervalMin))
    }

    /** 데모 패널: 통화 중 상황 시험 (이 동안 달토끼는 먼저 말을 걸지 않음) */
    fun simulateCall(durationMs: Long = 15_000L) {
        simulatedCallUntil = now() + durationMs
        addLine(Speaker.SYSTEM, "통화 시험 ${durationMs / 1000}초 시작")
        _inCallState.value = true
        scope.launch {
            delay(durationMs)
            _inCallState.value = audio.inCall
        }
        if (_busy.value != null) {
            currentJob?.cancel()
            tts.stop()
        }
    }

    /** 기록에 시스템 메시지 한 줄 (예: Pleos 내비 경유지 추가 결과) */
    fun note(text: String) {
        addLine(Speaker.SYSTEM, text)
    }

    /** @param persist false면 이번 실행에서만 바꿈(시연용). 저장된 설정은 그대로 둡니다. */
    fun setTalkLevel(level: TalkLevel, persist: Boolean = true) {
        if (_talkLevel.value == level) {
            if (persist) host.onTalkLevelChanged(level)
            return
        }
        _talkLevel.value = level
        if (persist) host.onTalkLevelChanged(level)
        addLine(Speaker.SYSTEM, "말 수: ${level.label}")
    }

    // ───────────────────────── 엔진에서 들어오는 신호 ─────────────────────────

    fun onAssessment(a: FatigueAssessment) {
        lastAssessment = a
        val calling = inCall
        if (_inCallState.value != calling) _inCallState.value = calling
        for (e in a.events) onEvent(e)
        if (!a.driveState.isDriving) return
        // 통화 중: 먼저 말을 걸지 않음(위험 경고 포함 — 화면의 위험 표시는 그대로). 대화 중이었으면 멈춤.
        if (calling && !callEpisode) {
            callEpisode = true
            host.onTalkDeferred()
        }
        if (!calling) callEpisode = false
        val busyNow = a.driverBusy != null
        if (busyNow && !busyEpisode) host.onTalkDeferred()
        busyEpisode = busyNow
        if (calling) {
            if (_busy.value != null) {
                currentJob?.cancel()
                tts.stop()
            }
            if (!callNoted && a.promptLevel >= FatigueLevel.CAUTION) {
                callNoted = true
                addLine(Speaker.SYSTEM, LunaLines.CALL_HOLD)
            }
            return
        }
        callNoted = false
        // 바쁜 순간(차선 변경·제동·큰 핸들 조작)에는 새로 말을 걸지 않음. 위험 경고만 최대 5초까지 미룬 뒤 말함.
        val busy = a.driverBusy
        if (busy != null) {
            val wantsAlert = a.promptLevel == FatigueLevel.DANGER && _busy.value != Intervention.ALERT
            if (alertDeferSince < 0 && wantsAlert) alertDeferSince = a.timestampMs
            val deferAlert = wantsAlert && a.timestampMs - alertDeferSince < MAX_ALERT_DEFER_MS
            if (!wantsAlert || deferAlert) {
                if (!busyNoted && a.promptLevel >= FatigueLevel.CAUTION && _busy.value == null) {
                    busyNoted = true
                    addLine(Speaker.SYSTEM, "${busy.label} 중 · 말 걸기 잠시 대기")
                }
                return
            }
        } else {
            busyNoted = false
            alertDeferSince = -1L
        }
        // 말 걸기는 '말 걸기 판단용 단계'(깨어 있음이 확인되면 한 단계 낮을 수 있음)로 정합니다.
        val decision = policy.decide(a.timestampMs, a.promptLevel, talk, _busy.value)
        if (decision == null) {
            if (policy.decideFreshAir(a.timestampMs, a.promptLevel, talk, a.recirculationMin, _busy.value, co2Ppm = a.co2Ppm)) {
                launchFlow(Intervention.FRESH_AIR, a) { freshAirFlow(a.co2Ppm) }
            } else if (policy.decideCoolDown(a.timestampMs, a.promptLevel, talk, _busy.value)) {
                launchFlow(Intervention.COOL_DOWN, a) { coolDownFlow() }
            } else if (policy.decideBoredom(
                    a.timestampMs, a.promptLevel, talk, a.continuousDriveMin,
                    highway = (a.features.meanSpeedKmh ?: 0f) >= 80f, lastGameAt = lastGameAt, busyWith = _busy.value,
                )
            ) {
                launchFlow(Intervention.BOREDOM_GAME, a) { boredomFlow() }
            }
            return
        }
        when (decision) {
            Intervention.CHECK_IN -> launchFlow(decision, a) { checkIn() }
            Intervention.OFFER_GAME -> launchFlow(decision, a) { offerRestOrGame() }
            Intervention.ALERT -> launchFlow(decision, a, preempt = true) { alert() }
            else -> Unit
        }
    }

    private fun onEvent(e: DriveEvent) {
        val a = lastAssessment
        when (e) {
            DriveEvent.TripStarted -> when (talk) {
                TalkLevel.MIN -> addLine(Speaker.SYSTEM, "운행 시작")
                TalkLevel.NORMAL -> launchFlow(Intervention.GREETING, a) { say(withDriverName(LunaLines.pick(LunaLines.greetingShort, random))) }
                TalkLevel.CHATTY -> launchFlow(Intervention.GREETING, a) { greetingWithQuestion() }
            }
            DriveEvent.CalibrationDone -> addLine(Speaker.SYSTEM, "운전 습관 기준선 학습 완료")
            DriveEvent.VehicleAttentionWarning -> addLine(Speaker.SYSTEM, "차량 운전자 주의 경고 감지 → 위험 단계")
            DriveEvent.RestStarted -> launchFlow(Intervention.REST_GUIDE, a, preempt = true) { restGuide() }
            DriveEvent.RestCompleted -> {
                addLine(Speaker.SYSTEM, "휴식 10분 완료 · 위험도 초기화 · +500점")
                // 커피냅 중이면 깨운 뒤에 알려 줌
                if (_napping.value) {
                    if (afterNap.isEmpty()) afterNap += { effect(SoundEffect.REST_DONE) }
                    return
                }
                // 휴게소 도장을 받았으면 도장 안내(announceStamp)가 이미 말하는 중이라 여기서는 기록만
                if (talk != TalkLevel.MIN && _busy.value == null) {
                    launchFlow(Intervention.ACK, a) {
                        effect(SoundEffect.REST_DONE)
                        say(LunaLines.pick(LunaLines.restCompleted, random))
                    }
                } else if (_busy.value == null) {
                    playLater(SoundEffect.REST_DONE)
                }
            }
            is DriveEvent.DrivingResumed -> {
                if (_napping.value) {
                    _napping.value = false
                    afterNap.clear()
                    host.onNapEnded()
                }
                val line = when {
                    talk == TalkLevel.MIN -> null
                    !e.restedEnough -> LunaLines.pick(LunaLines.resumedEarly, random)
                    talk == TalkLevel.CHATTY -> LunaLines.pick(LunaLines.resumedRested, random)
                    else -> null
                }
                if (line != null) launchFlow(Intervention.ACK, a, preempt = true) { say(line) } else stopRestGuide()
            }
            DriveEvent.TripEnded -> when (talk) {
                TalkLevel.MIN -> {
                    stopRestGuide()
                    addLine(Speaker.SYSTEM, "운행 종료 · 리포트는 화면에")
                }
                TalkLevel.NORMAL -> launchFlow(Intervention.TRIP_REPORT, a, preempt = true) { say(LunaLines.pick(LunaLines.tripEndShort, random)) }
                TalkLevel.CHATTY -> launchFlow(Intervention.TRIP_REPORT, a, preempt = true) { tripReport() }
            }
        }
    }

    private fun stopRestGuide() {
        if (_busy.value == Intervention.REST_GUIDE) {
            currentJob?.cancel()
            tts.stop()
        }
    }

    // ───────────────────────── 화면 버튼 ─────────────────────────

    /** "끝말잇기" 버튼 */
    fun startWordGame() = submitText("끝말잇기 하자")

    /**
     * 게임 고르기 창에서: [kind] 게임 시작. null(알아서 골라 줘)이면 피로 단계에 맞는 게임을 돌아가며.
     * '경고'에서 생각할 게 많은 게임을 고르면 반응·숫자 게임으로, '위험'이면 휴식 안내.
     */
    fun startGame(kind: GameKind? = null) {
        val level = host.currentLevel()
        val chosen = kind?.takeIf { it in GamePicker.allowed(level) } ?: pickGame() ?: return requestRest()
        launchFlow(Intervention.GAME, lastAssessment, preempt = true) { playGame(chosen) }
    }

    /** [게임 그만] 버튼: 하던 게임을 바로 멈춤 ("그만할래"라고 말한 것과 같음) */
    fun stopGame() {
        if (!_inGame.value) return
        _inGame.value = false
        launchFlow(Intervention.ACK, lastAssessment, preempt = true) { say(LunaLines.GAME_STOPPED) }
    }

    /** 정차 화면 '커피냅 15분' 버튼 */
    fun startNap() {
        launchFlow(Intervention.NAP, lastAssessment, preempt = true) { beginNap() }
    }

    /** 엔진이 부름: 커피냅 시간이 다 됨 → 깨우고 반응 재측정 → 미뤄 둔 도장 안내 */
    fun napWake() {
        if (!_napping.value) return
        launchFlow(Intervention.NAP, lastAssessment, preempt = true) {
            _napping.value = false
            effect(SoundEffect.WAKE)
            say(LunaLines.NAP_WAKE)
            val before = lastReactionAvgMs
            val after = reactionGame(roundsOverride = 3, retest = true)
            host.onNapRetest(before, after)
            say(LunaLines.napResult(before, after))
            host.onNapEnded()
            val pending = afterNap.toList()
            afterNap.clear()
            pending.forEach { it() }
        }
    }

    /** "휴게소" 버튼 */
    fun requestRest() = submitText("휴게소")

    /** 위험 카드의 "경로에 추가" 버튼 */
    fun acceptRest() {
        val p = pendingReply
        if (p != null && !p.isCompleted) {
            submitText("응")
        } else {
            launchFlow(Intervention.REST_SUGGEST, lastAssessment, preempt = true) { addRestDirect() }
        }
    }

    /** 위험 카드의 "괜찮아요" 버튼 */
    fun declineRest() {
        val p = pendingReply
        if (p != null && !p.isCompleted) submitText("아니") else addLine(Speaker.DRIVER, "괜찮아요")
    }

    // ───────────────────────── Gleo 음성비서에서 온 요청 ─────────────────────────

    /**
     * Gleo Interface로 들어온 요청을 운전자가 말한 것처럼 처리합니다(기록에는 'Gleo'로 남김).
     * 대답을 기다리던 중이어도 새 요청으로 봅니다.
     */
    fun fromAssistant(label: String, text: String) {
        addLine(Speaker.SYSTEM, "Gleo 음성비서: $label")
        handleFreeUtterance(text)
    }

    /**
     * Gleo에서 휴게소 경유지 추가를 요청했는데 주행 중일 때: 바로 넣지 않고 달토끼가 한 번 더 물음
     * ("망향휴게소까지 12킬로미터 남았어. 경로에 추가할까?" → [경로에 추가] / "응").
     * 다른 앱이 Gleo 신호를 흉내 내도 운전자 확인 없이 경로가 바뀌지 않게.
     */
    fun confirmRestFromAssistant() {
        addLine(Speaker.SYSTEM, "Gleo 음성비서: 휴게소 경유지 추가 (확인 요청)")
        launchFlow(Intervention.REST_SUGGEST, lastAssessment, preempt = true) { suggestRest() }
    }

    /** Gleo에서 휴게소 경유지 추가를 요청했을 때 결과를 달토끼가 말함 */
    fun announceRestFromAssistant(restArea: RestArea?, added: Boolean) {
        addLine(Speaker.SYSTEM, "Gleo 음성비서: 휴게소 경유지 추가")
        launchFlow(Intervention.REST_SUGGEST, lastAssessment, preempt = true) {
            when {
                restArea == null -> say(LunaLines.REST_UNKNOWN)
                added -> say(LunaLines.restAdded(restArea.name))
                else -> say(LunaLines.restAddFailed(restArea.name))
            }
        }
    }

    // ───────────────────────── 운전자가 먼저 말할 때 ─────────────────────────

    /** 화면 입력 또는 음성 인식 결과 */
    fun submitText(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        val p = pendingReply
        if (p != null && !p.isCompleted) {
            p.complete(Reply(t, now()))
            return
        }
        addLine(Speaker.DRIVER, t)
        handleFreeUtterance(t)
    }

    /**
     * 자동 운전자의 대답.
     * @param seq 대답하려는 질문([AwaitingReply.seq]). 그사이 질문이 바뀌었으면 버림
     * @param startedAt 말하기 시작한 시각 (응답 지연은 이 시각으로 계산)
     * @return 처리했으면 true
     */
    fun submitAuto(seq: Long, text: String, startedAt: Long): Boolean {
        val p = pendingReply ?: return false
        if (p.isCompleted || _awaiting.value?.seq != seq) return false
        p.complete(Reply(text.trim(), startedAt))
        return true
    }

    /** 자동 운전자가 먼저 하는 말 ("달토끼야, 끝말잇기 하자") */
    fun submitAutoFree(text: String) {
        submitText(text)
    }

    /** 운전자가 말하기 시작함 → 주행 화면 말풍선 (대답 처리는 말이 끝난 뒤) */
    fun showDriverSaying(text: String) {
        showDriverLine(text)
    }

    private fun showDriverLine(text: String) {
        _driverSaid.value = text
        driverSaidJob?.cancel()
        driverSaidJob = scope.launch {
            delay(DRIVER_LINE_SHOW_MS)
            _driverSaid.value = null
        }
    }

    /** 마이크 버튼("달토끼야") */
    fun pushToTalk() {
        if (pendingReply != null) return // 이미 듣는 중
        if (!stt.isAvailable) {
            addLine(Speaker.SYSTEM, LunaLines.MIC_UNAVAILABLE)
            return
        }
        tts.stop()
        scope.launch {
            _listening.value = true
            val r = stt.listen(8_000)
            _listening.value = false
            r?.let { submitText(it.text) }
        }
    }

    private fun handleFreeUtterance(text: String) {
        val a = lastAssessment
        when (UtteranceIntent.parse(text)) {
            UtteranceIntent.QUIET -> launchFlow(Intervention.ACK, a, preempt = true) { changeTalkLevel(talk.down()) }
            UtteranceIntent.MORE_TALK -> launchFlow(Intervention.ACK, a, preempt = true) { changeTalkLevel(talk.up()) }
            UtteranceIntent.SLEEPY -> {
                host.reportSleepy()
                launchFlow(Intervention.SLEEPY, a, preempt = true) { sleepyFlow() }
            }
            UtteranceIntent.NOT_SLEEPY -> {
                host.onAwakeClaim(null)
                launchFlow(Intervention.ACK, a, preempt = true) { awakeClaimChallenge() }
            }
            UtteranceIntent.WORD_GAME -> launchFlow(Intervention.GAME, a, preempt = true) { wordGame() }
            UtteranceIntent.QUIZ -> launchFlow(Intervention.GAME, a, preempt = true) { quiz() }
            UtteranceIntent.REACTION_GAME -> launchFlow(Intervention.GAME, a, preempt = true) { reactionGame() }
            UtteranceIntent.CHOSUNG_GAME -> launchFlow(Intervention.GAME, a, preempt = true) { playGame(GameKind.CHOSUNG) }
            UtteranceIntent.FOOD_QUIZ -> launchFlow(Intervention.GAME, a, preempt = true) { playGame(GameKind.FOOD) }
            UtteranceIntent.DIGIT_GAME -> launchFlow(Intervention.GAME, a, preempt = true) { playGame(GameKind.DIGITS) }
            UtteranceIntent.RIDDLE_GAME -> launchFlow(Intervention.GAME, a, preempt = true) { playGame(GameKind.RIDDLE) }
            UtteranceIntent.ANY_GAME -> launchFlow(Intervention.GAME, a, preempt = true) { pickGame()?.let { playGame(it) } ?: suggestRest() }
            UtteranceIntent.REST_AREA -> launchFlow(Intervention.REST_SUGGEST, a, preempt = true) { suggestRest() }
            else -> if (_busy.value == null) launchFlow(Intervention.SMALL_TALK, a) { smallTalk(text) }
        }
    }

    // ───────────────────────── 대화 흐름 ─────────────────────────

    /** 말 수 '수다'에서만: 인사 + 질문 (응답 속도를 개인 기준으로 저장) */
    /** 인사 앞에 운전자 이름 ("엄마, 좋아 출발하자!"). 기본 이름("운전자 1")이면 host가 null을 줘서 부르지 않음 */
    private fun withDriverName(line: String): String = host.driverName()?.let { "$it, $line" } ?: line

    private suspend fun greetingWithQuestion() {
        say(withDriverName(LunaLines.pick(LunaLines.greeting, random)))
        // 기억하는 대화: 지난번 목적지를 기억하면 "오늘도 부산 가?" (바로 대답할 수 있어 대답 속도 기준으로도 좋음)
        val dest = host.memory()[DriverMemory.Slot.DESTINATION]
        if (dest != null) {
            say(LunaLines.destinationAsk(dest))
            val r = awaitReply(ReplyContext.MEMORY, 8_000, listOf("응", "아니, 다른 데 가"))
            if (r != null) {
                host.recordBaselineResponse(r.latencyMs)
                say(if (UtteranceIntent.parse(r.text) == UtteranceIntent.YES) LunaLines.destinationYes(dest) else LunaLines.DESTINATION_OTHER)
            }
        }
        // 오늘 아직 컨디션을 안 물었으면 어젯밤 잠부터 (졸음 예보: 권장 휴식 간격이 정해짐)
        if (host.needsSleepCheck()) {
            sleepCheck()
            return
        }
        if (dest != null) return
        say(LunaLines.pick(LunaLines.greetingQuestion, random))
        val r = awaitReply(ReplyContext.GREETING, 10_000, listOf("부산 가는 길이야", "회사 가는 중이야", "컨디션 좋아"))
        if (r == null) {
            say(LunaLines.pick(LunaLines.greetingNoAnswer, random))
            return
        }
        host.recordBaselineResponse(r.latencyMs)
        if (!handleIntentInFlow(r)) smallTalk(r.text)
    }

    /** 출발 전 컨디션: "어젯밤 몇 시간 잤어?" → 권장 휴식 간격 안내 */
    private suspend fun sleepCheck() {
        say(LunaLines.pick(LunaLines.sleepQuestion, random))
        val r = awaitReply(ReplyContext.SLEEP, 10_000, listOf("4시간", "6시간", "8시간"))
        if (r == null) {
            say(LunaLines.pick(LunaLines.greetingNoAnswer, random))
            return
        }
        host.recordBaselineResponse(r.latencyMs)
        val hours = RestPlanner.parseSleepHours(r.text)
        if (hours == null) {
            if (!handleIntentInFlow(r)) smallTalk(r.text)
            return
        }
        val interval = host.onSleepReported(hours)
        addLine(Speaker.SYSTEM, LunaLines.sleepLog(hours, interval))
        say(LunaLines.sleepAck(hours, interval))
    }

    /** 주의 단계 말 걸기 (말 수 '보통'은 한 번만 묻고, '수다'는 대답 없으면 한 번 더) */
    private suspend fun checkIn() {
        say(LunaLines.pick(LunaLines.checkInQuestions, random))
        var r = awaitReply(ReplyContext.CHECK_IN, 8_000, listOf("김치찌개 먹었어", "안 졸려", "좀 졸려"))
        if (r == null && talk == TalkLevel.CHATTY) {
            say(LunaLines.pick(LunaLines.noResponse, random))
            r = awaitReply(ReplyContext.CHECK_IN_RETRY, 6_000, listOf("응 들려", "좀 졸려"))
        }
        if (r == null) {
            if (recordNoAnswer(ResponseKind.SIMPLE) && talk == TalkLevel.CHATTY) say(LunaLines.pick(LunaLines.noResponseFinal, random))
            return
        }
        host.recordResponse(r.latencyMs, ResponseKind.SIMPLE)
        if (!handleIntentInFlow(r)) smallTalk(r.text)
    }

    /** 경고 단계: "쉬어갈까, 반응 게임으로 잠 깨워줄까?" 한 번 */
    private suspend fun offerRestOrGame() {
        say(LunaLines.pick(LunaLines.offerRestOrGame, random))
        val r = awaitReply(ReplyContext.OFFER, 8_000, listOf("반응 게임", "숫자 게임", "휴게소", "안 졸려"))
        if (r == null) {
            if (recordNoAnswer(ResponseKind.SIMPLE) && talk == TalkLevel.CHATTY) say(LunaLines.pick(LunaLines.noResponseFinal, random))
            return
        }
        host.recordResponse(r.latencyMs, ResponseKind.SIMPLE)
        when (val intent = UtteranceIntent.parse(r.text)) {
            UtteranceIntent.REACTION_GAME -> reactionGame()
            UtteranceIntent.WORD_GAME -> wordGame()
            UtteranceIntent.QUIZ -> quiz()
            UtteranceIntent.CHOSUNG_GAME, UtteranceIntent.FOOD_QUIZ, UtteranceIntent.DIGIT_GAME, UtteranceIntent.RIDDLE_GAME, UtteranceIntent.ANY_GAME ->
                playGame(gameOf(intent) ?: pickGame() ?: GameKind.REACTION)
            UtteranceIntent.REST_AREA, UtteranceIntent.YES -> addRestDirect()
            UtteranceIntent.SLEEPY -> {
                host.reportSleepy(); sleepyFlow()
            }
            UtteranceIntent.NOT_SLEEPY -> {
                host.onAwakeClaim(r.latencyMs)
                say(LunaLines.pick(LunaLines.offerDeclined, random))
            }
            else -> say(LunaLines.pick(LunaLines.offerDeclined, random))
        }
    }

    /** 위험 단계: 경고 한 문장 + 휴게소 안내. 게임은 멈춤. */
    private suspend fun alert() {
        val vehicleWarning = lastAssessment?.vehicleWarningActive == true
        say(LunaLines.pick(if (vehicleWarning) LunaLines.alertVehicleWarning else LunaLines.alert, random))
        suggestRest()
    }

    private suspend fun sleepyFlow() {
        say(LunaLines.pick(LunaLines.sleepyAck, random))
        suggestRest()
    }

    /** "안 졸려"라고 먼저 말함: 말로는 안 내려가니 끝말잇기로 증명하자고 제안 */
    private suspend fun awakeClaimChallenge() {
        if (host.currentLevel() == FatigueLevel.DANGER) {
            say(LunaLines.pick(LunaLines.gamePausedDanger, random))
            suggestRest()
            return
        }
        // '경고' 이상이면 끝말잇기 대신 반응 게임으로 확인 (피로할수록 쉬운 게임)
        val tired = host.currentLevel() >= FatigueLevel.WARNING
        say(LunaLines.pick(if (tired) LunaLines.awakeClaimReaction else LunaLines.awakeClaimFree, random))
        val r = awaitReply(ReplyContext.AWAKE_CHALLENGE, 8_000, listOf(if (tired) "반응 게임" else "끝말잇기", "괜찮아"))
        if (r == null) return
        host.recordResponse(r.latencyMs, ResponseKind.SIMPLE)
        when (UtteranceIntent.parse(r.text)) {
            UtteranceIntent.YES -> if (tired) reactionGame() else wordGame()
            UtteranceIntent.WORD_GAME -> wordGame()
            UtteranceIntent.REACTION_GAME -> reactionGame()
            UtteranceIntent.QUIZ -> quiz()
            else -> gameOf(UtteranceIntent.parse(r.text))?.let { playGame(it) }
        }
    }

    /** 휴게소 안내 + 경로 추가 여부 질문 (화면 버튼으로도 대답 가능) */
    private suspend fun suggestRest() {
        val ra = host.nearestRestArea()
        if (ra == null) {
            say(LunaLines.REST_UNKNOWN)
            return
        }
        // 기억하는 대화: 좋아하는 음식이 그 휴게소 대표 메뉴면 곁들여 권함 (쉬고 싶게)
        val favorite = host.memory()[DriverMemory.Slot.FOOD]
        say(
            if (DriverMemory.foodMatches(favorite, ra.food)) LunaLines.restSuggestFavorite(ra.name, ra.distanceKm, favorite!!)
            else LunaLines.restSuggest(ra.name, ra.distanceKm),
        )
        _askingRest.value = true
        val r = try {
            awaitReply(ReplyContext.REST, 10_000, YES_NO)
        } finally {
            _askingRest.value = false
        }
        if (r == null) {
            if (recordNoAnswer(ResponseKind.SIMPLE) && talk != TalkLevel.MIN) say(LunaLines.restNoAnswer(ra.name))
            return
        }
        host.recordResponse(r.latencyMs, ResponseKind.SIMPLE)
        if (UtteranceIntent.parse(r.text) == UtteranceIntent.NO) {
            say(LunaLines.REST_DECLINED)
        } else {
            val ok = host.addWaypoint(ra)
            say(if (ok) LunaLines.restAdded(ra.name) else LunaLines.restAddFailed(ra.name))
        }
    }

    /** 이미 쉬기로 했을 때: 묻지 않고 바로 경로에 추가 */
    private suspend fun addRestDirect() {
        val ra = host.nearestRestArea()
        if (ra == null) {
            say(LunaLines.REST_UNKNOWN)
            return
        }
        val ok = host.addWaypoint(ra)
        say(if (ok) LunaLines.restAdded(ra.name) else LunaLines.restAddFailed(ra.name))
    }

    private suspend fun wordGame() {
        val startLevel = host.currentLevel()
        if (startLevel == FatigueLevel.DANGER) {
            say(LunaLines.pick(LunaLines.gamePausedDanger, random))
            suggestRest()
            return
        }
        // '경고'에서 시작하면 짧게 (생각하는 부담을 줄임)
        val tired = startLevel >= FatigueLevel.WARNING
        markGame(GameKind.WORD)
        val maxTurns = if (tired) WORD_TURNS_TIRED else WORD_TURNS
        val engine = WordChainEngine(dictionary(), strict = false, random = random)
        effect(SoundEffect.GAME_START)
        say(LunaLines.wordGameStart(engine.start()))
        var turns = 0
        var attempts = 0
        while (turns < maxTurns && attempts < maxTurns + 4) {
            attempts++
            val r = awaitReply(ReplyContext.WORD, 10_000, engine.hints(3) + "그만할래")
            if (r == null) {
                if (recordNoAnswer(ResponseKind.GAME)) {
                    host.onGameWrong()
                    wrongSound()
                    say(LunaLines.pick(LunaLines.gameTimeout, random))
                } else {
                    say(LunaLines.NOT_HEARD_GAME_END)
                }
                return
            }
            host.recordResponse(r.latencyMs, ResponseKind.GAME)
            if (UtteranceIntent.isStop(r.text)) {
                say(LunaLines.GAME_STOPPED)
                return
            }
            when (val res = engine.submit(r.text)) {
                is WordChainEngine.Result.Accepted -> {
                    turns++
                    host.onGameCorrect(r.latencyMs, isWordGame = true, verified = res.inDictionary)
                    correctSound()
                    val luna = res.lunaWord
                    if (luna == null) {
                        say(LunaLines.pick(LunaLines.lunaGivesUp, random))
                        return
                    }
                    say(LunaLines.wordGameReply(res.userWord, luna))
                }
                is WordChainEngine.Result.Invalid -> {
                    host.onGameWrong()
                    wrongSound()
                    say(LunaLines.wordGameInvalid(res))
                }
            }
            // 게임 도중 위험 단계가 되면 멈춤 (위험 경고가 이어서 끼어듦)
            if (host.currentLevel() == FatigueLevel.DANGER) {
                say(LunaLines.pick(LunaLines.gamePausedDanger, random))
                return
            }
            // 게임 도중 '경고'로 오르면 더 쉬운 반응 게임으로 바꿈
            if (!tired && host.currentLevel() >= FatigueLevel.WARNING) {
                say(LunaLines.GAME_EASIER)
                reactionGame()
                return
            }
        }
        say(LunaLines.pick(LunaLines.gameEnd, random))
    }

    private suspend fun quiz() {
        val level = host.currentLevel()
        if (level == FatigueLevel.DANGER) {
            say(LunaLines.pick(LunaLines.gamePausedDanger, random))
            suggestRest()
            return
        }
        val count = if (level >= FatigueLevel.WARNING) QUIZ_COUNT_TIRED else QUIZ_COUNT
        markGame(GameKind.QUIZ)
        val items = quizBank().pick(count, random)
        effect(SoundEffect.GAME_START)
        for (q in items) {
            say(q.question)
            val r = awaitReply(ReplyContext.QUIZ, 10_000, listOf(q.displayAnswer, "모르겠어").shuffled(random), expected = q.displayAnswer)
            if (r == null) {
                if (recordNoAnswer(ResponseKind.GAME)) {
                    host.onGameWrong()
                    wrongSound()
                }
                say(LunaLines.quizTimeout(q.displayAnswer))
                return
            }
            host.recordResponse(r.latencyMs, ResponseKind.GAME)
            if (UtteranceIntent.isStop(r.text)) {
                say(LunaLines.GAME_STOPPED)
                return
            }
            val explanation = q.explanation?.let { " $it" }.orEmpty()
            if (q.isCorrect(r.text)) {
                host.onGameCorrect(r.latencyMs, isWordGame = false, verified = true)
                correctSound()
                say(LunaLines.quizCorrect(q.displayAnswer) + explanation)
            } else {
                host.onGameWrong()
                wrongSound()
                say(LunaLines.quizWrong(q.displayAnswer) + explanation)
            }
        }
        say(LunaLines.pick(LunaLines.gameEnd, random))
    }

    /**
     * 반응 게임: 무작위로 기다렸다가 "띵 + 달!" → 운전자가 "빛!" (무슨 말이든 바로 반응하면 성공).
     * 졸음 연구의 반응 시간 검사(PVT)를 음성으로 바꾼 것으로, 생각할 게 거의 없어 피곤할 때도 안전하게 할 수 있고
     * 반응 시간 자체가 졸음 판정의 근거가 됩니다(ResponseKind.REACTION, 기준 1.2초).
     * '경고'면 3번, 평소엔 5번. 두 번 놓치거나 결과가 느리면 휴게소를 권합니다.
     */
    private suspend fun reactionGame(roundsOverride: Int? = null, retest: Boolean = false): Long? {
        val level = host.currentLevel()
        if (level == FatigueLevel.DANGER && !retest) {
            say(LunaLines.pick(LunaLines.gamePausedDanger, random))
            suggestRest()
            return null
        }
        markGame(GameKind.REACTION)
        val rounds = roundsOverride ?: if (level >= FatigueLevel.WARNING) REACTION_ROUNDS_TIRED else REACTION_ROUNDS
        effect(SoundEffect.GAME_START)
        if (!retest) say(LunaLines.pick(LunaLines.reactionIntro, random))
        var attempts = 0
        var hits = 0
        var misses = 0
        var unheard = 0
        var totalMs = 0L
        while (attempts < rounds) {
            attempts++
            delay(random.nextLong(reactionWaitMs.first, reactionWaitMs.last + 1))
            effect(SoundEffect.CUE)
            say(LunaLines.REACTION_CUE)
            val r = awaitReply(ReplyContext.REACTION, REACTION_TIMEOUT_MS, listOf("빛"))
            if (r == null) {
                if (recordNoAnswer(ResponseKind.REACTION)) {
                    host.onGameWrong()
                    host.onReactionMiss()
                    wrongSound()
                    misses++
                    say(LunaLines.pick(LunaLines.reactionMiss, random))
                    if (misses >= 2) break
                } else {
                    // 음성 인식이 듣지 못함 → 이번 판은 측정에서 뺌
                    unheard++
                    if (unheard >= 2) {
                        say(LunaLines.REACTION_VOICE_OFF)
                        return null
                    }
                    say(LunaLines.REACTION_NOT_HEARD)
                }
                continue
            }
            if (UtteranceIntent.isStop(r.text)) {
                say(LunaLines.GAME_STOPPED)
                return null
            }
            host.recordResponse(r.latencyMs, ResponseKind.REACTION)
            host.onReactionHit(r.latencyMs)
            hits++
            totalMs += r.latencyMs
            correctSound()
            say(LunaLines.reactionHit(r.latencyMs))
            if (!retest && host.currentLevel() == FatigueLevel.DANGER) {
                say(LunaLines.pick(LunaLines.gamePausedDanger, random))
                return null
            }
        }
        val measured = attempts - unheard
        if (measured <= 0) return null
        val avg = if (hits > 0) totalMs / hits else null
        if (!retest) {
            lastReactionAvgMs = avg ?: lastReactionAvgMs
            say(LunaLines.reactionResult(avg, hits, measured))
            if (avg == null || hits < measured || avg > REACTION_SLOW_AVG_MS) suggestRest()
        }
        return avg
    }

    // ───────────────────────── 14차: 환기 · 커피냅 · 게임 다양화 ─────────────────────────

    /** 환기 제안: "외기로 바꿔 줄까?" → 응 → 외기 전환(최소 차량 제어). 안 되면 음성으로 안내 */
    private suspend fun freshAirFlow(ppm: Float?) {
        say(LunaLines.freshAirAsk(ppm))
        val r = awaitReply(ReplyContext.FRESH_AIR, 8_000, YES_NO)
        if (r == null) {
            recordNoAnswer(ResponseKind.SIMPLE)
            return
        }
        host.recordResponse(r.latencyMs, ResponseKind.SIMPLE)
        if (UtteranceIntent.parse(r.text) == UtteranceIntent.NO) {
            say(LunaLines.FRESH_AIR_DECLINED)
            return
        }
        when (host.setFreshAir()) {
            FreshAirResult.DONE -> say(LunaLines.FRESH_AIR_DONE)
            FreshAirResult.NO_PERMISSION, FreshAirResult.UNSUPPORTED -> say(LunaLines.FRESH_AIR_NO_PERMISSION)
        }
    }

    /** 온도 낮추기 제안: "실내 온도를 2도 낮춰 줄까?" → 응 → 공조 온도 -2도. 안 되면 음성으로 안내 */
    private suspend fun coolDownFlow() {
        say(LunaLines.COOL_ASK)
        val r = awaitReply(ReplyContext.COOL_DOWN, 8_000, YES_NO)
        if (r == null) {
            recordNoAnswer(ResponseKind.SIMPLE)
            return
        }
        host.recordResponse(r.latencyMs, ResponseKind.SIMPLE)
        if (UtteranceIntent.parse(r.text) == UtteranceIntent.NO) {
            say(LunaLines.COOL_DECLINED)
            return
        }
        say(if (host.lowerTemperature() == FreshAirResult.DONE) LunaLines.COOL_DONE else LunaLines.COOL_UNSUPPORTED)
    }

    /** 지루함 예방 말놀이: 졸리기 전에 짧은 게임 한 판 제안 */
    private suspend fun boredomFlow() {
        val kind = pickGame() ?: return
        say(LunaLines.boredomAsk(kind.label))
        val r = awaitReply(ReplyContext.BOREDOM, 8_000, YES_NO)
        if (r == null || UtteranceIntent.parse(r.text) == UtteranceIntent.NO) {
            if (r != null) say(LunaLines.BOREDOM_DECLINED)
            return
        }
        host.recordResponse(r.latencyMs, ResponseKind.SIMPLE)
        playGame(kind)
    }

    private suspend fun beginNap() {
        _napping.value = true
        afterNap.clear()
        host.startNap(NAP_MIN)
        say(LunaLines.NAP_START)
    }

    private fun gameOf(intent: UtteranceIntent): GameKind? = when (intent) {
        UtteranceIntent.WORD_GAME -> GameKind.WORD
        UtteranceIntent.QUIZ -> GameKind.QUIZ
        UtteranceIntent.REACTION_GAME -> GameKind.REACTION
        UtteranceIntent.CHOSUNG_GAME -> GameKind.CHOSUNG
        UtteranceIntent.FOOD_QUIZ -> GameKind.FOOD
        UtteranceIntent.DIGIT_GAME -> GameKind.DIGITS
        UtteranceIntent.RIDDLE_GAME -> GameKind.RIDDLE
        else -> null
    }

    /** 피로 단계에 맞는 게임 (최근 한 게임은 피함). 위험이면 null */
    private fun pickGame(): GameKind? =
        GamePicker.pick(host.currentLevel(), recentGames, foodAvailable = host.foodStops().size >= 3, random = random)

    private fun markGame(kind: GameKind) {
        _inGame.value = true
        recentGames += kind
        while (recentGames.size > 4) recentGames.removeAt(0)
        lastGameAt = lastAssessment?.timestampMs
    }

    private suspend fun playGame(kind: GameKind) {
        when (kind) {
            GameKind.WORD -> wordGame()
            GameKind.QUIZ -> quiz()
            GameKind.REACTION -> reactionGame()
            GameKind.CHOSUNG -> chosungGame()
            GameKind.FOOD -> foodQuiz()
            GameKind.DIGITS -> digitGame()
            GameKind.RIDDLE -> riddleGame()
        }
    }

    /** 위험 단계면 게임 대신 휴식 (true면 게임하지 않음) */
    private suspend fun dangerStop(): Boolean {
        if (host.currentLevel() != FatigueLevel.DANGER) return false
        say(LunaLines.pick(LunaLines.gamePausedDanger, random))
        suggestRest()
        return true
    }

    /**
     * 짧은 문답 게임 공통 진행 (초성·음식·숫자·수수께끼).
     * @param ask 문제를 내고 (기다릴 맥락, 추천 대답, 정답) 을 돌려줌
     * @param judge 맞았으면 정답 말, 틀렸으면 null
     * @param wrongLine 틀렸을 때·시간 초과 때 할 말
     */
    private suspend fun quickRounds(
        kind: GameKind,
        rounds: Int,
        ask: suspend (Int) -> Triple<ReplyContext, List<String>, String?>?,
        judge: (Int, String) -> String?,
        wrongLine: (Int) -> String,
        detail: (Int) -> Int = { 0 },
    ) {
        for (i in 0 until rounds) {
            val (ctx, hints, expected) = ask(i) ?: break
            val r = awaitReply(ctx, 10_000, hints + "그만할래", expected)
            if (r == null) {
                if (recordNoAnswer(ResponseKind.GAME)) {
                    host.onGameWrong()
                    wrongSound()
                }
                say(wrongLine(i))
                return
            }
            if (UtteranceIntent.isStop(r.text)) {
                say(LunaLines.GAME_STOPPED)
                return
            }
            host.recordResponse(r.latencyMs, ResponseKind.GAME)
            val ok = judge(i, r.text)
            if (ok != null) {
                host.onGameCorrect(r.latencyMs, isWordGame = false, verified = true)
                host.onMiniGameCorrect(kind, detail(i))
                correctSound()
                say(ok)
            } else {
                host.onGameWrong()
                wrongSound()
                say(wrongLine(i))
            }
            if (host.currentLevel() == FatigueLevel.DANGER) {
                say(LunaLines.pick(LunaLines.gamePausedDanger, random))
                return
            }
        }
        say(LunaLines.pick(LunaLines.gameEnd, random))
    }

    /** 초성 게임: "기역 치읓!" → "기차" */
    private suspend fun chosungGame() {
        if (dangerStop()) return
        val tired = host.currentLevel() >= FatigueLevel.WARNING
        markGame(GameKind.CHOSUNG)
        val game = ChosungGame(dictionary(), random)
        val used = HashSet<String>()
        val questions = ArrayList<ChosungGame.Question>()
        effect(SoundEffect.GAME_START)
        say(LunaLines.CHOSUNG_START)
        quickRounds(
            kind = GameKind.CHOSUNG,
            rounds = if (tired) 2 else 3,
            ask = { _ ->
                val q = game.next(used) ?: return@quickRounds null
                used += q.pattern
                questions += q
                say("${q.spoken}!")
                Triple(ReplyContext.CHOSUNG, q.answers.take(2), q.answers.first())
            },
            judge = { i, text -> game.check(questions[i], text)?.let { LunaLines.chosungCorrect(it) } },
            wrongLine = { i -> LunaLines.chosungWrong(questions.getOrNull(i)?.answers?.first() ?: "") },
        )
    }

    /** 휴게소 음식 퀴즈 (공공데이터 대표 음식) */
    private suspend fun foodQuiz() {
        if (dangerStop()) return
        val tired = host.currentLevel() >= FatigueLevel.WARNING
        val items = FoodQuestion.make(host.foodStops(), if (tired) 1 else 2, random)
        if (items.isEmpty()) {
            quiz()
            return
        }
        markGame(GameKind.FOOD)
        effect(SoundEffect.GAME_START)
        say(LunaLines.FOOD_START)
        quickRounds(
            kind = GameKind.FOOD,
            rounds = items.size,
            ask = { i ->
                val q = items[i]
                say(q.question)
                Triple(ReplyContext.FOOD, q.choices, q.answer)
            },
            judge = { i, text -> if (items[i].isCorrect(text)) LunaLines.foodCorrect(items[i].stopName, items[i].answer) else null },
            wrongLine = { i -> LunaLines.foodWrong(items[i].stopName, items[i].answer) },
        )
    }

    /** 숫자 따라 말하기: 평소 4자리 3번, 피곤하면 3자리 2번 */
    private suspend fun digitGame() {
        if (dangerStop()) return
        val tired = host.currentLevel() >= FatigueLevel.WARNING
        markGame(GameKind.DIGITS)
        val length = if (tired) 3 else 4
        val sets = List(if (tired) 2 else 3) { DigitSpan.make(length, random) }
        effect(SoundEffect.GAME_START)
        say(LunaLines.DIGITS_START)
        quickRounds(
            kind = GameKind.DIGITS,
            rounds = sets.size,
            ask = { i ->
                say(DigitSpan.spoken(sets[i]))
                val answer = sets[i].joinToString(" ")
                Triple(ReplyContext.DIGITS, listOf(answer), answer)
            },
            judge = { i, text -> if (DigitSpan.isCorrect(sets[i], text)) LunaLines.DIGITS_CORRECT else null },
            wrongLine = { i -> LunaLines.digitsWrong(DigitSpan.spoken(sets[i])) },
            detail = { length },
        )
    }

    /** 수수께끼 2문제 */
    private suspend fun riddleGame() {
        if (dangerStop()) return
        markGame(GameKind.RIDDLE)
        val items = Riddles.pick(2, random)
        effect(SoundEffect.GAME_START)
        say(LunaLines.RIDDLE_START)
        quickRounds(
            kind = GameKind.RIDDLE,
            rounds = items.size,
            ask = { i ->
                say(items[i].question)
                Triple(ReplyContext.RIDDLE, listOf(items[i].answers.first(), "모르겠어"), items[i].answers.first())
            },
            judge = { i, text -> if (items[i].isCorrect(text)) LunaLines.riddleCorrect(items[i].reveal) else null },
            wrongLine = { i -> LunaLines.riddleWrong(items[i].reveal) },
        )
    }

    /** 휴식 안내: 최소·보통은 한 문장(스트레칭은 화면), 수다는 단계별 음성 안내 */
    private suspend fun restGuide() {
        if (talk != TalkLevel.CHATTY) {
            say(LunaLines.pick(LunaLines.restShort, random))
            return
        }
        say(LunaLines.pick(LunaLines.restArrived, random))
        // 커피냅 제안: 커피 마시고 15분 눈 붙이기 → 깨면 반응 게임으로 다시 재기
        say(LunaLines.NAP_ASK)
        val r = awaitReply(ReplyContext.NAP, 10_000, YES_NO)
        if (r != null && UtteranceIntent.parse(r.text) != UtteranceIntent.NO) {
            beginNap()
            return
        }
        say(LunaLines.NAP_DECLINED)
        for (step in LunaLines.stretchSteps) {
            delay(1_500)
            say(step)
        }
    }

    private suspend fun tripReport() {
        val s = host.tripStats()
        say(
            LunaLines.tripReport(
                drivingMin = (s.drivingMs / 60_000L).toInt(),
                rests = s.rests,
                interventions = s.interventions,
                maxLevelLabel = s.maxLevel.label,
            ),
        )
    }

    private suspend fun changeTalkLevel(level: TalkLevel) {
        if (level == talk) {
            say(LunaLines.talkLevelUnchanged(level))
            return
        }
        setTalkLevel(level)
        say(LunaLines.talkLevelChanged(level))
    }

    private suspend fun smallTalk(text: String) {
        chatHistory.addLast(ChatTurn(true, text))
        val reply = llm.reply(chatHistory.toList(), text) ?: LunaLines.pick(LunaLines.smallTalkFallback, random)
        chatHistory.addLast(ChatTurn(false, reply))
        while (chatHistory.size > 12) chatHistory.removeFirst()
        say(reply)
    }

    /** 흐름 도중 받은 답이 특별한 의도(졸려, 안 졸려, 조용히 등)면 처리하고 true */
    private suspend fun handleIntentInFlow(r: Reply): Boolean {
        when (UtteranceIntent.parse(r.text)) {
            UtteranceIntent.SLEEPY -> {
                host.reportSleepy(); sleepyFlow()
            }
            UtteranceIntent.NOT_SLEEPY -> {
                host.onAwakeClaim(r.latencyMs)
                say(LunaLines.pick(LunaLines.awakeClaimFast, random))
            }
            UtteranceIntent.QUIET -> changeTalkLevel(talk.down())
            UtteranceIntent.MORE_TALK -> changeTalkLevel(talk.up())
            UtteranceIntent.WORD_GAME -> wordGame()
            UtteranceIntent.QUIZ -> quiz()
            UtteranceIntent.REACTION_GAME -> reactionGame()
            UtteranceIntent.CHOSUNG_GAME, UtteranceIntent.FOOD_QUIZ, UtteranceIntent.DIGIT_GAME, UtteranceIntent.RIDDLE_GAME ->
                gameOf(UtteranceIntent.parse(r.text))?.let { playGame(it) }
            UtteranceIntent.ANY_GAME -> pickGame()?.let { playGame(it) } ?: suggestRest()
            UtteranceIntent.REST_AREA -> suggestRest()
            else -> return false
        }
        return true
    }

    /** 새 배지를 받았을 때 (엔진이 호출). 말 수 '수다'에서만 소리로 알림. */
    fun announceBadge(name: String) {
        addLine(Speaker.SYSTEM, LunaLines.newBadge(name))
        if (talk == TalkLevel.CHATTY && _busy.value == null) {
            launchFlow(Intervention.ACK, lastAssessment) {
                effect(SoundEffect.BADGE)
                say(LunaLines.newBadge(name))
            }
        } else {
            playLater(SoundEffect.BADGE)
        }
    }

    /**
     * 휴게소 도장을 받았을 때 (엔진이 10분 휴식 완료 직전에 호출).
     * 최소: 효과음과 기록만 / 보통: "망향휴게소 도장 쾅! 3곳째" / 수다: + 대표 음식
     */
    fun announceStamp(r: StampResult, newBadges: List<String> = emptyList()) {
        val routeText = r.record.route?.takeIf { r.routeTotal > 0 }?.let { LunaLines.routeProgress(it, r.routeHave, r.routeTotal) }
        addLine(Speaker.SYSTEM, LunaLines.stampLog(r.record.name, r.isNew, r.places, routeText))
        newBadges.forEach { addLine(Speaker.SYSTEM, LunaLines.newBadge(it)) }
        // 기억하는 대화: 좋아하는 음식이 이 휴게소 대표 메뉴면 그 얘기로
        val favorite = host.memory()[DriverMemory.Slot.FOOD]
        val favoriteHere = favorite != null && DriverMemory.foodMatches(favorite, r.food)
        val speech: suspend () -> Unit = {
            effect(SoundEffect.STAMP)
            val stampLine = if (favoriteHere) {
                LunaLines.stamp(r.record.name, r.isNew, r.places, null, withFood = false) + LunaLines.stampFavorite(favorite!!, r.food!!)
            } else {
                LunaLines.stamp(r.record.name, r.isNew, r.places, r.food, withFood = talk == TalkLevel.CHATTY)
            }
            say(LunaLines.pick(LunaLines.restCompleted, random) + " " + stampLine)
            for (b in newBadges) {
                effect(SoundEffect.BADGE)
                if (talk == TalkLevel.CHATTY) say(LunaLines.newBadge(b))
            }
        }
        // 커피냅 중이면 깨운 뒤에 (반응 재측정 다음)
        if (_napping.value && talk != TalkLevel.MIN) {
            afterNap.clear()
            afterNap += speech
            return
        }
        // 휴식 안내(스트레칭)가 아직 이어지고 있으면 끊고 도장 안내
        val guiding = _busy.value == Intervention.REST_GUIDE
        if (talk != TalkLevel.MIN && (_busy.value == null || guiding)) {
            launchFlow(Intervention.ACK, lastAssessment, preempt = guiding) { speech() }
        } else {
            playLater(SoundEffect.STAMP)
            repeat(newBadges.size) { playLater(SoundEffect.BADGE) }
        }
    }

    // ───────────────────────── 기본 동작 ─────────────────────────

    private fun launchFlow(
        kind: Intervention,
        a: FatigueAssessment?,
        preempt: Boolean = false,
        block: suspend () -> Unit,
    ) {
        if (_busy.value != null && !preempt) return
        currentJob?.cancel()
        tts.stop()
        pendingReply?.cancel()
        pendingReply = null
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val self = coroutineContext[Job]
            _busy.value = kind
            _inGame.value = false // 게임 흐름이면 게임을 시작할 때 다시 켜짐
            host.setInteractive(true)
            audio.duck() // 달토끼가 말하는 동안 음악 소리 줄이기
            try {
                block()
            } finally {
                if (currentJob === self) {
                    _busy.value = null
                    _inGame.value = false
                    _suggestions.value = emptyList()
                    _awaiting.value = null
                    _askingRest.value = false
                    _listening.value = false
                    _speaking.value = false
                    host.setInteractive(false)
                    audio.unduck()
                    // 대화 중에 쌓였지만 아직 못 낸 효과음(배지 등)은 대화가 끝나면 바로
                    if (sfxQueue.isNotEmpty()) {
                        val left = sfxQueue.toList()
                        sfxQueue.clear()
                        scope.launch { left.forEach { effect(it) } }
                    }
                }
            }
        }
        currentJob = job
        a?.let { policy.markDone(kind, it.timestampMs) }
        host.onIntervention(kind)
        job.start()
    }

    /**
     * 대답이 없을 때: 음성 인식이 제대로 듣고 있었을 때만 '무응답'으로 셉니다.
     * 인식기가 없거나 곧바로 실패하는 상태라면 운전자가 대답했어도 못 들었을 수 있어서, 졸음 판단에 넣지 않습니다.
     * @return 무응답으로 셌으면 true
     */
    private fun recordNoAnswer(kind: ResponseKind): Boolean {
        if (lastWaitHeard) {
            host.recordMiss(kind)
            return true
        }
        if (!unheardNoted) {
            unheardNoted = true
            addLine(Speaker.SYSTEM, LunaLines.MISS_NOT_COUNTED)
        }
        return false
    }

    /** 효과음 하나 재생 (끝날 때까지 기다림) */
    private suspend fun effect(e: SoundEffect, step: Int = 0) {
        sfxLock.withLock { sfx.play(e, step) }
    }

    /** 대화 중이면 다음 말 앞에, 아니면 바로 재생 */
    private fun playLater(e: SoundEffect) {
        if (_busy.value != null) {
            sfxQueue.addLast(e)
        } else {
            scope.launch { effect(e) }
        }
    }

    private suspend fun correctSound() {
        effect(SoundEffect.CORRECT, sfxCombo)
        sfxCombo++
        // 3연속부터 콤보 음을 더하고, 5연속이면 "달빛 모드" 한마디
        if (sfxCombo >= 3) effect(SoundEffect.COMBO, sfxCombo - 3)
        // 콤보가 길어질수록 더 신나게 칭찬 (3 · 5 · 7연속)
        when (sfxCombo) {
            3 -> say(LunaLines.COMBO_3)
            5 -> {
                effect(SoundEffect.FANFARE)
                say(LunaLines.COMBO_MOON)
            }
            7 -> say(LunaLines.COMBO_7)
        }
    }

    /** 화면 연출에 맞춘 효과음 (달빛 조각 '띵'·팡파르·별 '쾅'). 말하는 중이어도 바로 */
    fun playNow(e: SoundEffect, step: Int = 0) {
        scope.launch { effect(e, step) }
    }

    /** 엔진이 부름: 안전 행동 점수 소리 (대화 중이면 다음 말 앞에) */
    fun chime(e: SoundEffect) = playLater(e)

    private suspend fun wrongSound() {
        sfxCombo = 0
        effect(SoundEffect.WRONG)
    }

    private suspend fun say(text: String) {
        while (sfxQueue.isNotEmpty()) effect(sfxQueue.removeFirst())
        addLine(Speaker.LUNA, text)
        _lastLunaLine.value = text
        _speaking.value = true
        try {
            val started = now()
            tts.speak(text)
            // 자동 시연: 소리가 없거나 빨리 끝나도 한 문장을 읽을 만큼은 보여줌
            if (autoDriver) {
                val left = autoPaceMs(text) - (now() - started)
                if (left > 0) delay(left)
            }
        } finally {
            _speaking.value = false
        }
    }

    /**
     * 운전자의 대답을 기다립니다. 음성 인식과 화면 입력 중 먼저 온 것을 씁니다.
     * 응답 지연 = 달토끼 질문이 끝난 시점 → 운전자가 말하기 시작한 시점
     */
    private suspend fun awaitReply(context: ReplyContext, timeoutMs: Long, hints: List<String> = emptyList(), expected: String? = null): Reply? {
        val deferred = CompletableDeferred<Reply>()
        pendingReply = deferred
        val promptEnd = now()
        val deadline = promptEnd + timeoutMs
        _listening.value = true
        _suggestions.value = hints
        _awaiting.value = AwaitingReply(context, hints, ++awaitSeq, expected)
        // 음성 인식이 제대로 듣고 있는지 (없거나 곧바로 계속 실패하면 false).
        // 자동 시연은 자동 운전자가 '듣는 쪽'이라 대답이 없으면 무응답으로 셈
        val auto = autoDriver
        var heard = stt.isAvailable || auto
        val sttJob = if (stt.isAvailable && !auto) {
            scope.launch {
                var quickFails = 0
                while (isActive && !deferred.isCompleted) {
                    val remaining = deadline - now()
                    if (remaining < 800) break
                    val started = now()
                    val r = stt.listen(remaining)
                    if (r != null) {
                        deferred.complete(r)
                        break
                    }
                    // 음성 인식이 곧바로 실패하는 환경(에뮬레이터 마이크 꺼짐 등)에서는
                    // 인식기를 계속 만들었다 지우지 않고 화면 입력만 기다립니다.
                    if (now() - started < 1_500) {
                        quickFails++
                        if (quickFails >= 2) {
                            heard = false
                            break
                        }
                    }
                    delay(300)
                }
            }
        } else null
        try {
            val r = withTimeoutOrNull(timeoutMs) { deferred.await() }
            if (r == null) {
                lastWaitHeard = heard
                return null
            }
            addLine(Speaker.DRIVER, r.text)
            return r.copy(latencyMs = (r.speechStartRealtime - promptEnd).coerceAtLeast(0L))
        } finally {
            if (pendingReply === deferred) pendingReply = null
            _listening.value = false
            _suggestions.value = emptyList()
            _awaiting.value = null
            sttJob?.cancel()
        }
    }

    private fun addLine(speaker: Speaker, text: String) {
        if (speaker == Speaker.DRIVER) {
            if (_driverSaid.value != text) showDriverLine(text)
            // 기억하는 대화: "부산 가는 길이야", "국밥 좋아해" → 운전자 기록에
            DriverMemory.extract(text).takeIf { it.isNotEmpty() }?.let { host.remember(it) }
        }
        val list = _transcript.value + TranscriptLine(speaker, text)
        _transcript.value = if (list.size > 60) list.takeLast(60) else list
    }

    private companion object {
        val YES_NO = listOf("응", "아니")
        const val WORD_TURNS = 6
        const val WORD_TURNS_TIRED = 3
        const val QUIZ_COUNT = 3
        const val QUIZ_COUNT_TIRED = 2
        const val REACTION_ROUNDS = 5
        const val REACTION_ROUNDS_TIRED = 3
        /** "달!" 뒤 반응을 기다리는 시간 */
        const val REACTION_TIMEOUT_MS = 4_000L
        /** 반응 게임 평균이 이보다 느리면 휴게소를 권함 */
        const val REACTION_SLOW_AVG_MS = 1_800L
        /** 위험 경고는 바쁜 순간이어도 최대 이 시간(차량 시각)까지만 미룸 */
        const val MAX_ALERT_DEFER_MS = 5_000L
        /** 운전자 말풍선 표시 시간 */
        const val DRIVER_LINE_SHOW_MS = 4_500L
        /** 커피냅 길이(분) */
        const val NAP_MIN = 15
    }
}
