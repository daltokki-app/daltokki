package app.daltokki.drive.engine

import android.os.SystemClock
import android.util.Log
import app.daltokki.drive.domain.dialog.ReplyContext
import app.daltokki.drive.domain.driver.HabitLearning
import app.daltokki.drive.domain.model.FatigueLevel
import app.daltokki.drive.domain.sim.AutoAction
import app.daltokki.drive.domain.sim.AutoDriverInput
import app.daltokki.drive.domain.sim.AutoDriverScript
import app.daltokki.drive.domain.sim.DemoCueSheet
import app.daltokki.drive.domain.sim.DriveScenario
import app.daltokki.drive.voice.AndroidTtsOutput
import app.daltokki.drive.voice.SpeechOutput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** 자동 시연 화면 상태 (카운트다운 · 장면 자막 · 종료 카드) */
data class AutoDemoUi(
    /** 자동 시연 중 (종료 카드가 떠 있는 동안도 true) */
    val active: Boolean = false,
    /** 시작 전 카운트다운 3·2·1 (없으면 null) */
    val countdown: Int? = null,
    /** 지금 보여주는 장면 자막 (없으면 null) */
    val sceneNo: Int = 0,
    val sceneTitle: String? = null,
    val sceneDetail: String? = null,
    /** 끝남 → 종료 카드 */
    val finished: Boolean = false,
    /** 큐시트를 저장한 폴더 (adb pull 경로 안내용) */
    val cueFolder: String? = null,
)

/**
 * 자동 시연 (녹화용): 운전자 대답을 대본([AutoDriverScript])대로 자동으로 넣습니다.
 *
 * - 졸음 판정·대화 흐름은 실제 엔진 그대로. 자동 운전자는 '사람이 했을 법한 대답'과 대답 속도만 대신합니다.
 * - 운전자 목소리: 안드로이드 기본 TTS 남성 음성(없으면 낮은 음높이). 소리가 안 나는 PC에서는 화면 말풍선만.
 * - 차선 변경·통화·"초성 게임 하자"는 구간에 맞춰 자동으로.
 * - 장면 자막(①, ②…)과 시작 카운트다운·종료 카드로 편집 없이도 설명되는 영상.
 * - 대사 큐시트(SRT·TSV)를 저장 → 편집에서 달토끼(여성)·운전자(남성) 음성을 같은 시각에 입힘.
 *   위치: /sdcard/Android/data/app.daltokki.drive/files/demo/
 */
class AutoDriver(
    private val engine: CopilotEngine,
    private val scope: CoroutineScope,
    /** 큐시트 저장 폴더 */
    private val folder: () -> File?,
    /** 운전자 목소리 (없으면 말풍선만) */
    private val driverVoice: () -> SpeechOutput?,
    private val now: () -> Long = { SystemClock.elapsedRealtime() },
) {
    private val _ui = MutableStateFlow(AutoDemoUi())
    val ui: StateFlow<AutoDemoUi> = _ui

    /** 대본대로 진행 중 (카운트다운 포함, 종료 카드 전까지) */
    val running: Boolean get() = _ui.value.active && !_ui.value.finished

    private var job: Job? = null
    private var replyJob: Job? = null
    private val cue = DemoCueSheet()
    private var t0 = 0L
    private var stamp = ""
    private var sceneCount = 0
    private val shownScenes = HashSet<AutoDriverScript.Scene>()
    private var sceneQueue = Channel<AutoDriverScript.Scene>(Channel.UNLIMITED)
    /** "숫자 게임도 하자"를 말할 수 있는 시각 (초성 게임이 시작되고 조금 뒤, 대화가 끝났을 때) */
    private var moreGameAfter = Long.MAX_VALUE

    /** 자동 시연 시작: 카운트다운 3초 → 시연 출발 (새벽 고속도로 대본) */
    fun begin(scenario: DriveScenario) {
        cancel(save = false)
        cue.clear()
        shownScenes.clear()
        sceneCount = 0
        sceneQueue = Channel(Channel.UNLIMITED)
        moreGameAfter = Long.MAX_VALUE
        t0 = now()
        stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        _ui.value = AutoDemoUi(active = true, countdown = COUNTDOWN)
        driverVoice() // 카운트다운 동안 운전자 목소리(TTS) 준비 시작
        cue.add(0, DemoCueSheet.Kind.NOTE, "자동 시연 시작 (카운트다운 3)")
        job = scope.launch {
            for (n in COUNTDOWN downTo 1) {
                _ui.update { it.copy(countdown = n) }
                delay(1_000)
            }
            _ui.update { it.copy(countdown = null) }
            val initialReport = engine.ui.value.lastTripStats
            engine.startDemo(scenario, auto = true)
            queueScene(AutoDriverScript.Scene.DRIVER)
            val workers = listOf(
                launch { watchAwaiting() },
                launch { watchTranscript() },
                launch { showScenes() },
                launch { watchStory() },
                launch { autosave() },
            )
            waitForEnd(initialReport)
            // 끝나면 자동 운전자는 더 대답하지 않음 (종료 카드 뒤 수동 조작은 원래대로)
            workers.forEach { it.cancel() }
            replyJob?.cancel()
            finish()
        }
    }

    /** 중지 (■ 버튼, 다른 시연 시작, 데이터 모드 변경) */
    fun cancel(save: Boolean = true) {
        val wasActive = _ui.value.active
        job?.cancel()
        job = null
        replyJob?.cancel()
        replyJob = null
        engine.endAutoMode()
        engine.exitDemoDriver()
        if (save && wasActive && cue.size > 1) {
            cue.add(now() - t0, DemoCueSheet.Kind.NOTE, "자동 시연 중지")
            scope.launch { save() }
        }
        _ui.value = AutoDemoUi()
    }

    /** 종료 카드 닫기 */
    fun dismiss() {
        if (_ui.value.finished) {
            _ui.value = AutoDemoUi()
            engine.exitDemoDriver() // 실제 운전자로 돌아감
        }
    }

    // ───────────────────────── 대답 ─────────────────────────

    private suspend fun watchAwaiting() {
        val dialog = engine.dialog
        var lastContext: ReplyContext? = null
        var round = 0
        val seen = HashMap<ReplyContext, Int>()
        dialog.awaiting.collect { a ->
            if (a == null) return@collect
            round = if (a.context == lastContext) round + 1 else 0
            lastContext = a.context
            val seenBefore = seen[a.context] ?: 0
            seen[a.context] = seenBefore + 1
            when (a.context) {
                ReplyContext.WORD, ReplyContext.CHOSUNG, ReplyContext.FOOD, ReplyContext.DIGITS, ReplyContext.RIDDLE ->
                    queueScene(AutoDriverScript.Scene.WORD_GAME)
                ReplyContext.REACTION -> queueScene(AutoDriverScript.Scene.REACTION)
                ReplyContext.MEMORY -> queueScene(AutoDriverScript.Scene.MEMORY)
                ReplyContext.FRESH_AIR, ReplyContext.COOL_DOWN -> queueScene(AutoDriverScript.Scene.AIR)
                ReplyContext.NAP -> queueScene(AutoDriverScript.Scene.NAP)
                else -> Unit
            }
            val st = engine.ui.value
            val reply = AutoDriverScript.decide(
                AutoDriverInput(
                    context = a.context,
                    hints = a.hints,
                    phase = st.phaseLabel,
                    level = st.assessment?.level ?: FatigueLevel.NORMAL,
                    seen = seenBefore,
                    round = round,
                    expected = a.expected,
                ),
            ) ?: return@collect // 대본상 대답하지 않음 (무응답 장면)
            replyJob?.cancel()
            replyJob = scope.launch {
                delay(reply.thinkMs)
                if (dialog.awaiting.value?.seq != a.seq) return@launch
                val started = now()
                speakAsDriver(reply.text, started, waitVoice = a.context != ReplyContext.REACTION)
                dialog.submitAuto(a.seq, reply.text, started)
            }
        }
    }

    /**
     * 운전자가 말함: 말풍선 → (목소리) → 최소 표시 시간.
     * 목소리는 한국어 음성이 준비됐을 때만, 대답 제한 시간을 넘지 않게 최대 '표시 시간 + 1.5초'까지만 기다립니다.
     * 반응 게임("빛!")은 시간이 중요해서 목소리를 기다리지 않습니다.
     */
    private suspend fun speakAsDriver(text: String, started: Long, waitVoice: Boolean = true) {
        engine.dialog.showDriverSaying(text)
        cue.add(started - t0, DemoCueSheet.Kind.DRIVER, text)
        val pace = DemoCueSheet.paceMs(text)
        val voice = driverVoice()?.takeIf { it.status.value == AndroidTtsOutput.STATUS_READY }
        if (voice != null) {
            if (waitVoice) {
                withTimeoutOrNull(pace + DRIVER_VOICE_EXTRA_MS) { voice.speak(text) }
            } else {
                scope.launch { withTimeoutOrNull(DRIVER_VOICE_EXTRA_MS) { voice.speak(text) } }
            }
        }
        val left = pace - (now() - started)
        if (left > 0) delay(left)
    }

    // ───────────────────────── 대사 기록 ─────────────────────────

    private suspend fun watchTranscript() {
        val dialog = engine.dialog
        var lastId = dialog.transcript.value.maxOfOrNull { it.id } ?: 0L
        dialog.transcript.collect { list ->
            for (line in list) {
                if (line.id <= lastId) continue
                lastId = line.id
                val t = now() - t0
                when (line.speaker) {
                    Speaker.LUNA -> cue.add(t, DemoCueSheet.Kind.TOKKI, line.text)
                    Speaker.SYSTEM -> cue.add(t, DemoCueSheet.Kind.NOTE, line.text)
                    Speaker.DRIVER -> Unit // 말하기 시작한 시각으로 이미 기록함
                }
            }
        }
    }

    // ───────────────────────── 장면·이벤트 ─────────────────────────

    private suspend fun watchStory() {
        val dialog = engine.dialog
        var phase = ""
        var phaseStart = now()
        val done = HashSet<AutoAction>()
        var callEndsAt = 0L
        var dangerShown = false
        var lastRewardSeq = engine.ui.value.game.lastGain?.seq
        while (scope.isActive) {
            val st = engine.ui.value
            // 보상 장면 부각: 콤보 2연속부터 자막, 5연속 달빛 모드
            if (st.game.combo >= 2) queueScene(AutoDriverScript.Scene.COMBO)
            // 휴식 잭팟: 시뮬레이션을 잠깐 멈추고 자막 → 잭팟이 끝나면 달빛 선물 상자를 엶
            val g = st.game.lastGain
            if (g != null && g.seq != lastRewardSeq && g.reason == GainReason.REST) {
                lastRewardSeq = g.seq
                queueScene(AutoDriverScript.Scene.REWARD)
                engine.holdSim(REWARD_HOLD_MS)
                scope.launch {
                    delay(GIFT_AFTER_MS)
                    queueScene(AutoDriverScript.Scene.GIFT)
                    engine.openGift()
                    delay(GIFT_SHOW_MS)
                    engine.dismissGift()
                }
            }
            if (st.phaseLabel != phase) {
                phase = st.phaseLabel
                phaseStart = now()
                AutoDriverScript.sceneForPhase(phase)?.let { queueScene(it) }
            }
            val level = st.assessment?.level ?: FatigueLevel.NORMAL
            // 오늘 습관을 누적하지 않기로 함(새벽·수면 부족·졸음 신호) → 장면 자막
            if (st.habitNotice?.verdict == HabitLearning.Verdict.SKIP_TIRED) queueScene(AutoDriverScript.Scene.HABIT_GUARD)
            if (level == FatigueLevel.DANGER && !dangerShown) {
                dangerShown = true
                queueScene(AutoDriverScript.Scene.DANGER)
            }
            val idle = dialog.busy.value == null && dialog.awaiting.value == null
            val since = now() - phaseStart
            when (AutoDriverScript.actionFor(phase, level, done)) {
                AutoAction.LANE_CHANGE -> if (since >= LANE_CHANGE_AFTER_MS && idle) {
                    done += AutoAction.LANE_CHANGE
                    engine.injectLaneChange()
                }
                AutoAction.CALL -> if (since >= CALL_AFTER_MS && idle) {
                    done += AutoAction.CALL
                    queueScene(AutoDriverScript.Scene.CALL)
                    engine.simulateCall(CALL_MS)
                    callEndsAt = now() + CALL_MS
                }
                AutoAction.ASK_WORD_GAME -> if (now() >= callEndsAt + AFTER_CALL_MS && idle) {
                    done += AutoAction.ASK_WORD_GAME
                    val started = now()
                    speakAsDriver(AutoDriverScript.ASK_WORD_GAME_TEXT, started)
                    dialog.submitAutoFree(AutoDriverScript.ASK_WORD_GAME_TEXT)
                    moreGameAfter = now() + MORE_GAME_GAP_MS
                }
                AutoAction.ASK_MORE_GAME -> if (now() >= moreGameAfter && idle) {
                    done += AutoAction.ASK_MORE_GAME
                    val started = now()
                    speakAsDriver(AutoDriverScript.ASK_MORE_GAME_TEXT, started)
                    dialog.submitAutoFree(AutoDriverScript.ASK_MORE_GAME_TEXT)
                }
                null -> Unit
            }
            delay(TICK_MS)
        }
    }

    private fun queueScene(scene: AutoDriverScript.Scene) {
        if (shownScenes.add(scene)) sceneQueue.trySend(scene)
    }

    /** 장면 자막을 하나씩 4초씩 */
    private suspend fun showScenes() {
        for (scene in sceneQueue) {
            sceneCount++
            _ui.update { it.copy(sceneNo = sceneCount, sceneTitle = scene.title, sceneDetail = scene.detail) }
            cue.add(now() - t0, DemoCueSheet.Kind.CAPTION, "${circled(sceneCount)} ${scene.title} — ${scene.detail}")
            delay(DemoCueSheet.CAPTION_MS)
            _ui.update { it.copy(sceneTitle = null, sceneDetail = null) }
            delay(400)
        }
    }

    // ───────────────────────── 끝 ─────────────────────────

    private suspend fun waitForEnd(initialReport: Any?) {
        // 운행 리포트가 새로 생기면(운행 종료) 끝. 시뮬레이션이 멈춰 버린 경우도 끝.
        var sawRunning = false
        while (true) {
            val st = engine.ui.value
            if (st.running) sawRunning = true
            if (st.lastTripStats != null && st.lastTripStats !== initialReport) break
            if (sawRunning && !st.running && st.assessment?.driveState?.isDriving != true) {
                delay(3_000)
                if (!engine.ui.value.running) break
            }
            delay(TICK_MS)
        }
        queueScene(AutoDriverScript.Scene.REPORT)
        delay(1_500)
        // 운행 리포트 말이 끝날 때까지
        withTimeoutOrNull(30_000) { engine.dialog.busy.first { it == null } }
        delay(END_HOLD_MS)
    }

    private suspend fun finish() {
        engine.endAutoMode()
        cue.add(now() - t0, DemoCueSheet.Kind.NOTE, "자동 시연 끝")
        val path = save()
        // 큐시트 위치는 화면(녹화 영상)에 보이지 않게 데모 패널 대화 기록에만 남김 (경로에 패키지명이 들어감)
        path?.let { engine.dialog.note("대사 큐시트 저장됨: $it") }
        _ui.update { it.copy(finished = true, sceneTitle = null, sceneDetail = null, cueFolder = path) }
    }

    private suspend fun autosave() {
        while (scope.isActive) {
            delay(10_000)
            save()
        }
    }

    /** 큐시트 저장. 성공하면 폴더 경로 */
    private suspend fun save(): String? = withContext(Dispatchers.IO) {
        runCatching {
            val dir = folder() ?: return@runCatching null
            dir.mkdirs()
            val dialogSrt = cue.dialogSrt()
            val captionSrt = cue.captionSrt()
            val tsv = "﻿" + cue.tsv() // 엑셀에서 한글이 깨지지 않게 BOM
            for (prefix in listOf(stamp, "latest")) {
                File(dir, "${prefix}_dialog.srt").writeText(dialogSrt)
                File(dir, "${prefix}_captions.srt").writeText(captionSrt)
                File(dir, "${prefix}_cue.tsv").writeText(tsv)
            }
            dir.absolutePath
        }.onFailure { Log.w(TAG, "큐시트 저장 실패", it) }.getOrNull()
    }

    private companion object {
        const val TAG = "AutoDriver"
        const val COUNTDOWN = 3
        const val TICK_MS = 300L
        /** 또렷한 구간이 시작되고 차선 변경까지 */
        const val LANE_CHANGE_AFTER_MS = 4_000L
        /** 피로 구간이 시작되고 통화까지 */
        const val CALL_AFTER_MS = 2_500L
        const val CALL_MS = 12_000L
        /** 통화가 끝나고 "끝말잇기 하자"까지 */
        const val AFTER_CALL_MS = 2_500L
        /** 운전자 목소리를 표시 시간보다 더 기다리는 최대 시간 */
        const val DRIVER_VOICE_EXTRA_MS = 1_500L
        /** 끝나고 종료 카드까지 */
        const val END_HOLD_MS = 2_500L
        /** 초성 게임을 청하고 나서 "숫자 게임도 하자"까지 최소 간격 (그사이 초성 게임이 끝남) */
        const val MORE_GAME_GAP_MS = 4_000L
        /** 휴식 잭팟 동안 시뮬레이션 멈춤 · 선물 상자 여는 때 · 보여 주는 시간 */
        const val REWARD_HOLD_MS = 12_000L
        const val GIFT_AFTER_MS = 6_500L
        const val GIFT_SHOW_MS = 5_000L

        /** ①②③… (20 넘으면 숫자) */
        fun circled(n: Int): String = if (n in 1..20) (0x2460 + n - 1).toChar().toString() else "($n)"
    }
}
