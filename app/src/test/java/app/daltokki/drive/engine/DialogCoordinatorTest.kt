package app.daltokki.drive.engine

import app.daltokki.drive.ai.ScriptedLlmClient
import app.daltokki.drive.data.nav.RestArea
import app.daltokki.drive.domain.dialog.DriverMemory
import app.daltokki.drive.domain.dialog.Intervention
import app.daltokki.drive.domain.dialog.LunaLines
import app.daltokki.drive.domain.dialog.ReplyContext
import app.daltokki.drive.domain.sim.AutoDriverInput
import app.daltokki.drive.domain.sim.AutoDriverScript
import app.daltokki.drive.domain.dialog.TalkLevel
import app.daltokki.drive.domain.fatigue.ResponseKind
import app.daltokki.drive.domain.game.DigitSpan
import app.daltokki.drive.domain.game.GameKind
import app.daltokki.drive.domain.game.Hangul
import app.daltokki.drive.domain.game.QuizBank
import app.daltokki.drive.domain.game.StampRecord
import app.daltokki.drive.domain.game.StampResult
import app.daltokki.drive.domain.game.WordDictionary
import app.daltokki.drive.domain.model.BusyReason
import app.daltokki.drive.domain.model.DriveEvent
import app.daltokki.drive.domain.model.DriveState
import app.daltokki.drive.domain.model.DrivingFeatures
import app.daltokki.drive.domain.model.FatigueAssessment
import app.daltokki.drive.domain.model.FatigueLevel
import app.daltokki.drive.domain.model.TripStats
import app.daltokki.drive.domain.fatigue.RestPlanner
import app.daltokki.drive.voice.AudioEnvironment
import app.daltokki.drive.voice.NoAudioEnvironment
import app.daltokki.drive.voice.Reply
import app.daltokki.drive.voice.SpeechInput
import app.daltokki.drive.voice.SpeechOutput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.Executors

/** 대화 흐름 검증: 가짜 TTS/STT로 실제 코디네이터를 돌려 봅니다. */
class DialogCoordinatorTest {

    private class FakeTts : SpeechOutput {
        override val status: StateFlow<String> = MutableStateFlow("fake")
        val spoken: MutableList<String> = Collections.synchronizedList(mutableListOf())
        override suspend fun speak(text: String) { spoken += text }
        override fun stop() {}
        fun last(): String = synchronized(spoken) { spoken.lastOrNull() ?: "" }
    }

    private class NoStt : SpeechInput {
        override val isAvailable = false
        override val status: StateFlow<String> = MutableStateFlow("none")
        override suspend fun listen(timeoutMs: Long): Reply? = null
    }

    /** 음성 인식은 정상인데 운전자가 아무 말도 안 하는 경우 (듣다가 시간 초과) */
    private class SilentStt : SpeechInput {
        override val isAvailable = true
        override val status: StateFlow<String> = MutableStateFlow("silent")
        override suspend fun listen(timeoutMs: Long): Reply? {
            delay(timeoutMs)
            return null
        }
    }

    private class FakeAudio : AudioEnvironment {
        @Volatile override var inCall = false
        @Volatile var ducks = 0
        @Volatile var unducks = 0
        override fun duck() { ducks++ }
        override fun unduck() { unducks++ }
    }

    private class FakeHost : DialogHost {
        val baseline = mutableListOf<Long>()
        val responses = mutableListOf<Long>()
        var misses = 0
        var sleepy = 0
        var correct = 0
        var wrong = 0
        val claims = mutableListOf<Long?>()
        val interventions = mutableListOf<Intervention>()
        var talkSaved: TalkLevel? = null
        var waypoint: RestArea? = null
        @Volatile var level: FatigueLevel = FatigueLevel.NORMAL
        override fun recordBaselineResponse(latencyMs: Long) { baseline += latencyMs }
        val kinds = mutableListOf<ResponseKind>()
        override fun recordResponse(latencyMs: Long, kind: ResponseKind) { responses += latencyMs; kinds += kind }
        override fun recordMiss(kind: ResponseKind) { misses++ }
        override fun reportSleepy() { sleepy++ }
        override fun onGameCorrect(latencyMs: Long, isWordGame: Boolean, verified: Boolean) { correct++ }
        val reactions = mutableListOf<Long>()
        override fun onReactionHit(latencyMs: Long) { reactions += latencyMs }
        override fun onGameWrong() { wrong++ }
        override fun onAwakeClaim(latencyMs: Long?) { claims += latencyMs }
        override fun setInteractive(active: Boolean) {}
        override fun onIntervention(kind: Intervention) { interventions += kind }
        override fun onTalkLevelChanged(level: TalkLevel) { talkSaved = level }
        override fun isDriving() = true
        override fun currentLevel() = level
        override fun tripStats() = TripStats()
        override suspend fun nearestRestArea() = RestArea("한빛 휴게소", 12)
        override suspend fun addWaypoint(restArea: RestArea): Boolean { waypoint = restArea; return true }
        @Volatile var askSleep = false
        @Volatile var sleep: Float? = null
        override fun needsSleepCheck() = askSleep
        override fun onSleepReported(hours: Float): Int {
            sleep = hours
            askSleep = false
            return RestPlanner.intervalMin(13, hours)
        }

        // 14차
        @Volatile var freshAirResult = FreshAirResult.UNSUPPORTED
        @Volatile var freshAirCalls = 0
        override suspend fun setFreshAir(): FreshAirResult { freshAirCalls++; return freshAirResult }
        @Volatile var napMinutes: Int? = null
        @Volatile var napEnded = 0
        override fun startNap(minutes: Int) { napMinutes = minutes }
        override fun onNapEnded() { napEnded++ }
        @Volatile var retest: Pair<Long?, Long?>? = null
        override fun onNapRetest(beforeMs: Long?, afterMs: Long?) { retest = beforeMs to afterMs }
        var stops: List<Pair<String, String>> = emptyList()
        override fun foodStops() = stops
        val mini: MutableList<Pair<GameKind, Int>> = Collections.synchronizedList(mutableListOf())
        override fun onMiniGameCorrect(game: GameKind, detail: Int) { mini += game to detail }
        val mem: MutableMap<DriverMemory.Slot, String> = Collections.synchronizedMap(mutableMapOf())
        override fun memory(): Map<DriverMemory.Slot, String> = synchronized(mem) { mem.toMap() }
        override fun remember(updates: Map<DriverMemory.Slot, String>) { mem.putAll(updates) }
        // 18차
        @Volatile var coolResult = FreshAirResult.UNSUPPORTED
        @Volatile var coolCalls = 0
        override suspend fun lowerTemperature(): FreshAirResult { coolCalls++; return coolResult }
        @Volatile var deferred = 0
        override fun onTalkDeferred() { deferred++ }
    }

    private val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    // 모든 단어가 이어지도록 구성(어떤 시작 단어가 나와도 다음 단어가 있음)
    private val dict = WordDictionary(listOf("기차", "차기", "기러기", "기타", "타조", "조기", "기자", "자기"))

    private fun assessment(level: FatigueLevel, events: List<DriveEvent> = emptyList(), t: Long = 0L) = FatigueAssessment(
        timestampMs = t, driveState = DriveState.MONITORING, score = 0f, sensorScore = 0f, bonus = 0f,
        bonusApplied = 0f, bonusBlocked = false, rawScore = 0f, level = level,
        contextRisk = 0f, steeringAnomaly = null, laneRisk = null, dialogImpairment = null,
        continuousDriveMin = 0f, baselineReady = true, calibrationProgress = 1f,
        features = DrivingFeatures.EMPTY, selfReportActive = false, events = events,
    )

    private fun waitUntil(timeoutMs: Long = 3_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!cond()) {
            if (System.currentTimeMillis() > end) throw AssertionError("시간 초과")
            Thread.sleep(20)
        }
    }

    private fun newCoordinator(
        tts: FakeTts,
        host: FakeHost,
        talk: TalkLevel,
        stt: SpeechInput = NoStt(),
        audio: AudioEnvironment = NoAudioEnvironment,
    ) = DialogCoordinator(
        scope = scope, tts = tts, stt = stt, llm = ScriptedLlmClient(), host = host,
        dictionary = { dict }, quizBank = { QuizBank.parse("대한민국의 수도는?|서울") },
        initialTalkLevel = talk,
        reactionWaitMs = 10L..30L,
        audio = audio,
        now = { System.nanoTime() / 1_000_000L },
        autoPaceMs = { 0L },
    )

    private fun onMain(block: () -> Unit) = runBlocking { withContext(dispatcher) { block() } }

    @Test
    fun minTalk_greetingAndCautionAreSilent() {
        val tts = FakeTts(); val host = FakeHost()
        val c = newCoordinator(tts, host, TalkLevel.MIN)
        onMain { c.onAssessment(assessment(FatigueLevel.NORMAL, listOf(DriveEvent.TripStarted))) }
        onMain { c.onAssessment(assessment(FatigueLevel.CAUTION, t = 60_000)) }
        Thread.sleep(300)
        assertTrue("최소: 출발·주의 단계에서 말하지 않음", tts.spoken.isEmpty())
        assertTrue(c.busy.value == null)
    }

    @Test
    fun chattyTalk_greetingRecordsBaseline() {
        val tts = FakeTts(); val host = FakeHost()
        val c = newCoordinator(tts, host, TalkLevel.CHATTY)
        onMain { c.onAssessment(assessment(FatigueLevel.NORMAL, listOf(DriveEvent.TripStarted))) }
        waitUntil { c.busy.value == Intervention.GREETING && c.listening.value }
        onMain { c.submitText("부산 가는 길이야") }
        waitUntil { c.busy.value == null }
        assertEquals(1, host.baseline.size)
        assertTrue("대화가 끝나면 추천 대답이 사라짐", c.suggestions.value.isEmpty())
    }

    @Test
    fun warning_offerThenWordGame_rewardsCorrectAnswers() {
        val tts = FakeTts(); val host = FakeHost()
        host.level = FatigueLevel.WARNING
        val c = newCoordinator(tts, host, TalkLevel.MIN)
        onMain { c.onAssessment(assessment(FatigueLevel.WARNING, t = 60_000)) }
        waitUntil { c.busy.value == Intervention.OFFER_GAME && c.listening.value }
        assertTrue("경고 단계는 생각할 게 적은 게임만 추천", c.suggestions.value.contains("숫자 게임"))
        assertTrue("경고 단계에서는 반응 게임을 먼저 권함", c.suggestions.value.first() == "반응 게임")
        onMain { c.submitText("끝말잇기") }
        waitUntil { c.listening.value && tts.last().startsWith("내가 먼저 할게") }
        val first = tts.last().substringAfter("내가 먼저 할게. ").removeSuffix("!")
        // 추천 대답 버튼에는 지금 규칙에 맞는 단어가 들어 있어야 함
        val hinted = c.suggestions.value.filter { it != "그만할래" }
        assertTrue(hinted.isNotEmpty())
        assertTrue(hinted.all { it.first() in Hangul.startOptions(first.last()) })
        onMain { c.submitText(hinted.first()) }
        waitUntil { host.correct == 1 }
        onMain { c.submitText("그만할래") }
        waitUntil { c.busy.value == null }
        assertEquals("정답 1번 → 보너스·점수 요청", 1, host.correct)
        // 제안에 대한 대답은 짧은 질문, 끝말잇기 대답은 게임으로 따로 기록 (게임 생각 시간을 졸음으로 오해하지 않게)
        assertEquals(ResponseKind.SIMPLE, host.kinds.first())
        assertTrue(host.kinds.drop(1).all { it == ResponseKind.GAME })
    }

    @Test
    fun wrongWord_breaksCombo() {
        val tts = FakeTts(); val host = FakeHost()
        val c = newCoordinator(tts, host, TalkLevel.MIN)
        onMain { c.startWordGame() }
        waitUntil { c.listening.value && tts.spoken.isNotEmpty() }
        onMain { c.submitText("하늘") }
        waitUntil { tts.last().contains("시작해야") && c.listening.value }
        assertEquals(1, host.wrong)
        onMain { c.submitText("그만할래") }
        waitUntil { c.busy.value == null }
    }

    @Test
    fun danger_alertPreemptsAndAcceptRestButtonAddsWaypoint() {
        val tts = FakeTts(); val host = FakeHost()
        val c = newCoordinator(tts, host, TalkLevel.CHATTY)
        onMain { c.onAssessment(assessment(FatigueLevel.CAUTION, t = 60_000)) }
        waitUntil { c.busy.value == Intervention.CHECK_IN }
        host.level = FatigueLevel.DANGER
        onMain { c.onAssessment(assessment(FatigueLevel.DANGER, t = 70_000)) }
        waitUntil { c.busy.value == Intervention.ALERT && c.askingRest.value }
        onMain { c.acceptRest() } // 화면의 "경로에 추가" 버튼
        waitUntil { host.waypoint != null }
        waitUntil { c.busy.value == null }
        assertFalse(c.askingRest.value)
    }

    @Test
    fun danger_gameRequestIsPausedForRest() {
        val tts = FakeTts(); val host = FakeHost()
        host.level = FatigueLevel.DANGER
        val c = newCoordinator(tts, host, TalkLevel.MIN)
        onMain { c.startWordGame() }
        waitUntil { tts.spoken.isNotEmpty() }
        assertTrue("위험 단계에서는 게임 대신 휴식 안내", tts.spoken.first() in LunaLines.gamePausedDanger)
        waitUntil { c.askingRest.value }
        onMain { c.declineRest() }
        waitUntil { c.busy.value == null }
        assertEquals(null, host.waypoint)
    }

    @Test
    fun notSleepyClaim_givesNoFreeBonus_andChallenges() {
        val tts = FakeTts(); val host = FakeHost()
        host.level = FatigueLevel.CAUTION
        val c = newCoordinator(tts, host, TalkLevel.MIN)
        onMain { c.submitText("나 하나도 안 졸려") }
        waitUntil { c.listening.value }
        assertEquals("먼저 한 말은 지연 시간 없음(보너스 없음)", listOf<Long?>(null), host.claims)
        assertTrue(tts.spoken.first() in LunaLines.awakeClaimFree)
        onMain { c.submitText("괜찮아") }
        waitUntil { c.busy.value == null }
    }

    @Test
    fun demoTalkLevel_isNotSaved() {
        val tts = FakeTts(); val host = FakeHost()
        val c = newCoordinator(tts, host, TalkLevel.NORMAL)
        onMain { c.setTalkLevel(TalkLevel.CHATTY, persist = false) }
        assertEquals(TalkLevel.CHATTY, c.talkLevel.value)
        assertEquals("시연용 '수다'는 저장하지 않음", null, host.talkSaved)
        // 시연 중에 사용자가 직접 같은 단계를 골라도 저장됨
        onMain { c.setTalkLevel(TalkLevel.CHATTY) }
        assertEquals(TalkLevel.CHATTY, host.talkSaved)
    }

    @Test
    fun voiceCommands_changeTalkLevel() {
        val tts = FakeTts(); val host = FakeHost()
        val c = newCoordinator(tts, host, TalkLevel.NORMAL)
        onMain { c.submitText("조용히 해") }
        waitUntil { c.talkLevel.value == TalkLevel.MIN && c.busy.value == null }
        assertEquals(TalkLevel.MIN, host.talkSaved)
        onMain { c.submitText("말 좀 해줘") }
        waitUntil { c.talkLevel.value == TalkLevel.NORMAL && c.busy.value == null }
        onMain { c.submitText("말 좀 해줘") }
        waitUntil { c.talkLevel.value == TalkLevel.CHATTY && c.busy.value == null }
        onMain { c.submitText("말 좀 해줘") }
        waitUntil { tts.last() == LunaLines.talkLevelUnchanged(TalkLevel.CHATTY) }
    }

    @Test
    fun vehicleWarning_usesVehicleAlertLine() {
        val tts = FakeTts(); val host = FakeHost()
        host.level = FatigueLevel.DANGER
        val c = newCoordinator(tts, host, TalkLevel.MIN)
        val a = assessment(FatigueLevel.DANGER, listOf(DriveEvent.VehicleAttentionWarning), t = 60_000).copy(vehicleWarningActive = true)
        onMain { c.onAssessment(a) }
        waitUntil { c.busy.value == Intervention.ALERT && tts.spoken.isNotEmpty() }
        assertTrue("차량 경고가 원인이면 차량 경고 문장", tts.spoken.first() in LunaLines.alertVehicleWarning)
        assertTrue(c.transcript.value.any { it.speaker == Speaker.SYSTEM && it.text.contains("주의 경고") })
        waitUntil { c.askingRest.value }
        onMain { c.declineRest() }
        waitUntil { c.busy.value == null }
    }

    @Test
    fun freshAir_spokenOnlyWhenNotMinimal() {
        val tts = FakeTts(); val host = FakeHost()
        host.level = FatigueLevel.CAUTION
        val min = newCoordinator(tts, host, TalkLevel.MIN)
        val a = assessment(FatigueLevel.CAUTION, t = 60_000).copy(recirculationMin = 25f)
        onMain { min.onAssessment(a) }
        Thread.sleep(200)
        assertTrue("최소: 환기 안내는 화면만", tts.spoken.isEmpty())

        val tts2 = FakeTts()
        val normal = newCoordinator(tts2, FakeHost().also { it.level = FatigueLevel.CAUTION }, TalkLevel.NORMAL)
        // 보통: 주의 단계 첫 판단은 말 걸기(CHECK_IN)이므로, 말 걸기를 이미 한 상태를 만든 뒤 확인
        onMain { normal.onAssessment(assessment(FatigueLevel.CAUTION, t = 60_000)) }
        waitUntil { normal.busy.value == Intervention.CHECK_IN && normal.listening.value }
        onMain { normal.submitText("응") }
        waitUntil { normal.busy.value == null }
        onMain { normal.onAssessment(a.copy(timestampMs = 120_000)) }
        // 14차: "외기로 바꿔 줄까?" → 응 → (권한 없음) 음성 안내로 대체
        waitUntil { normal.awaiting.value?.context == ReplyContext.FRESH_AIR }
        onMain { normal.submitText("응") }
        waitUntil { tts2.last() == LunaLines.FRESH_AIR_NO_PERMISSION }
    }

    @Test
    fun busyMoment_defersCheckIn_thenTalksAfter() {
        val tts = FakeTts(); val host = FakeHost()
        host.level = FatigueLevel.CAUTION
        val c = newCoordinator(tts, host, TalkLevel.CHATTY)
        onMain { c.onAssessment(assessment(FatigueLevel.CAUTION, t = 60_000).copy(driverBusy = BusyReason.LANE_CHANGE)) }
        Thread.sleep(200)
        assertTrue("차선 변경 중에는 말을 걸지 않음", tts.spoken.isEmpty())
        assertTrue(c.transcript.value.any { it.speaker == Speaker.SYSTEM && it.text.startsWith("차선 변경 중") })
        onMain { c.onAssessment(assessment(FatigueLevel.CAUTION, t = 64_000)) }
        waitUntil { c.busy.value == Intervention.CHECK_IN }
    }

    @Test
    fun busyMoment_defersDangerAlertAtMostFiveSeconds() {
        val tts = FakeTts(); val host = FakeHost()
        host.level = FatigueLevel.DANGER
        val c = newCoordinator(tts, host, TalkLevel.MIN)
        fun braking(t: Long) = assessment(FatigueLevel.DANGER, t = t).copy(driverBusy = BusyReason.BRAKING)
        onMain { c.onAssessment(braking(60_000)) }
        onMain { c.onAssessment(braking(63_000)) }
        Thread.sleep(200)
        assertTrue("제동 중에는 위험 경고도 잠깐 미룸", tts.spoken.isEmpty())
        onMain { c.onAssessment(braking(65_100)) }
        waitUntil { c.busy.value == Intervention.ALERT && c.askingRest.value }
        onMain { c.declineRest() }
        waitUntil { c.busy.value == null }
    }

    // ───────────── 7차: 반응 게임 · 피로별 난이도 · 도장 ─────────────

    private fun cues(tts: FakeTts) = tts.spoken.count { it == LunaLines.REACTION_CUE }

    @Test
    fun reactionGame_fiveRoundsWhenAlert_recordsReactionKind() {
        val tts = FakeTts(); val host = FakeHost()
        host.level = FatigueLevel.CAUTION
        val c = newCoordinator(tts, host, TalkLevel.MIN)
        onMain { c.submitText("반응 게임 하자") }
        for (i in 1..5) {
            // 추천 대답 버튼은 "빛" 하나
            waitUntil { cues(tts) == i && c.listening.value && c.suggestions.value == listOf("빛") }
            onMain { c.submitText("빛") }
            waitUntil { host.reactions.size == i }
        }
        waitUntil { c.busy.value == null }
        assertEquals("평소(주의 이하)는 5번", 5, cues(tts))
        assertTrue(host.kinds.all { it == ResponseKind.REACTION })
        assertTrue("결과 발표", tts.spoken.any { it.startsWith("평균") })
        assertFalse("다 받았으면 휴게소를 권하지 않음", c.askingRest.value)
    }

    @Test
    fun reactionGame_threeRoundsWhenTired_andMissesLeadToRest() {
        val tts = FakeTts(); val host = FakeHost()
        host.level = FatigueLevel.WARNING
        val c = newCoordinator(tts, host, TalkLevel.MIN, stt = SilentStt())
        onMain { c.submitText("반응 게임 하자") } // ('경고'면 게임 버튼은 반응·숫자 게임을 돌아가며)
        waitUntil { cues(tts) == 1 && c.listening.value }
        onMain { c.submitText("빛") }
        // 두 번 연속 놓치면(4초씩) 게임을 끝내고 휴게소를 권함
        waitUntil(15_000) { c.askingRest.value }
        assertEquals(2, host.misses)
        assertTrue("1번 성공 2번 놓침 → 느려졌다는 결과", tts.spoken.any { it.contains("3번 중 1번") })
        onMain { c.declineRest() }
        waitUntil { c.busy.value == null }
        assertEquals("경고 단계는 3번까지", 3, cues(tts))
    }

    @Test
    fun wordGame_switchesToReactionGame_whenFatigueRises() {
        val tts = FakeTts(); val host = FakeHost()
        host.level = FatigueLevel.NORMAL
        val c = newCoordinator(tts, host, TalkLevel.MIN)
        onMain { c.startWordGame() }
        waitUntil { c.listening.value && tts.last().startsWith("내가 먼저 할게") && c.suggestions.value.size > 1 }
        val word = c.suggestions.value.first { it != "그만할래" }
        host.level = FatigueLevel.WARNING
        onMain { c.submitText(word) }
        // 전체 테스트를 함께 돌릴 때(부하) 늦을 수 있어 넉넉히
        waitUntil(8_000) { tts.spoken.contains(LunaLines.GAME_EASIER) }
        waitUntil(8_000) { cues(tts) == 1 && c.listening.value }
        assertTrue(tts.spoken.any { it in LunaLines.reactionIntro })
        onMain { c.submitText("그만할래") }
        waitUntil { c.busy.value == null }
        assertEquals(LunaLines.GAME_STOPPED, tts.last())
    }

    @Test
    fun stamp_isAnnouncedByTalkLevel() {
        val record = StampRecord("망향(부산)|36.856|127.181", "망향휴게소", false, "경부선", 1, 0L, 0L)
        val result = StampResult(record, isNew = true, places = 3, shelters = 0, routeHave = 2, routeTotal = 65, bestRoute = 2, food = "명품한우국밥")

        val tts = FakeTts()
        val chatty = newCoordinator(tts, FakeHost(), TalkLevel.CHATTY)
        onMain { chatty.announceStamp(result, listOf("첫 도장")) }
        waitUntil { chatty.busy.value == null && tts.spoken.isNotEmpty() }
        assertTrue(tts.spoken.first().contains("망향휴게소 도장 쾅! 3곳째"))
        assertTrue("수다: 대표 음식까지", tts.spoken.first().contains("명품한우국밥"))
        assertTrue(tts.spoken.contains(LunaLines.newBadge("첫 도장")))
        assertTrue(chatty.transcript.value.any { it.speaker == Speaker.SYSTEM && it.text.contains("경부선 2/65곳") })

        val quiet = FakeTts()
        val min = newCoordinator(quiet, FakeHost(), TalkLevel.MIN)
        onMain { min.announceStamp(result) }
        Thread.sleep(200)
        assertTrue("최소: 말하지 않고 효과음·기록만", quiet.spoken.isEmpty())
        assertTrue(min.transcript.value.any { it.text.startsWith("도장 · 망향휴게소") })
    }

    // ───────────── 8차: 음성 인식 상태 · 통화 · 덕킹 · 출발 전 컨디션 ─────────────

    @Test
    fun noVoiceRecognition_missesAreNotCounted() {
        val tts = FakeTts(); val host = FakeHost()
        host.level = FatigueLevel.WARNING
        val c = newCoordinator(tts, host, TalkLevel.MIN) // 음성 인식 없음
        onMain { c.submitText("반응 게임 하자") }
        waitUntil(15_000) { c.busy.value == null && cues(tts) >= 2 }
        assertEquals("못 들은 대답 없음은 무응답으로 세지 않음", 0, host.misses)
        assertEquals("콤보도 끊지 않음", 0, host.wrong)
        assertEquals(LunaLines.REACTION_VOICE_OFF, tts.last())
        assertFalse("못 들었으면 결과·휴게소 권유도 하지 않음", tts.spoken.any { it.startsWith("평균") })
        assertTrue(c.transcript.value.any { it.text == LunaLines.MISS_NOT_COUNTED })
    }

    @Test
    fun voiceRecognitionWorking_silenceIsCountedAsMiss() {
        val tts = FakeTts(); val host = FakeHost()
        host.level = FatigueLevel.CAUTION
        val c = newCoordinator(tts, host, TalkLevel.NORMAL, stt = SilentStt())
        onMain { c.onAssessment(assessment(FatigueLevel.CAUTION, t = 60_000)) }
        waitUntil(12_000) { host.misses == 1 }
        waitUntil { c.busy.value == null }
    }

    @Test
    fun duringCall_lunaWaits_thenTalksAfterCall() {
        val tts = FakeTts(); val host = FakeHost(); val audio = FakeAudio()
        host.level = FatigueLevel.CAUTION
        audio.inCall = true
        val c = newCoordinator(tts, host, TalkLevel.CHATTY, audio = audio)
        onMain { c.onAssessment(assessment(FatigueLevel.CAUTION, t = 60_000)) }
        Thread.sleep(200)
        assertTrue("통화 중에는 말을 걸지 않음", tts.spoken.isEmpty())
        assertTrue(c.transcript.value.any { it.text == LunaLines.CALL_HOLD })
        audio.inCall = false
        onMain { c.onAssessment(assessment(FatigueLevel.CAUTION, t = 64_000)) }
        waitUntil { c.busy.value == Intervention.CHECK_IN && c.listening.value }
        assertEquals("말하는 동안 음악 소리 줄이기", 1, audio.ducks)
        onMain { c.submitText("응") }
        waitUntil { c.busy.value == null }
        waitUntil { audio.unducks >= 1 }
    }

    @Test
    fun chattyDeparture_asksSleep_andSetsRestInterval() {
        val tts = FakeTts(); val host = FakeHost()
        host.askSleep = true
        val c = newCoordinator(tts, host, TalkLevel.CHATTY)
        onMain { c.onAssessment(assessment(FatigueLevel.NORMAL, listOf(DriveEvent.TripStarted))) }
        waitUntil { c.listening.value && tts.last() in LunaLines.sleepQuestion }
        assertEquals(listOf("4시간", "6시간", "8시간"), c.suggestions.value)
        onMain { c.submitText("4시간밖에 못 잤어") }
        waitUntil { c.busy.value == null }
        assertEquals(4f, host.sleep!!, 0.01f)
        assertEquals("대답 속도는 개인 기준으로", 1, host.baseline.size)
        assertEquals(LunaLines.sleepAck(4f, 75), tts.last())
        assertTrue(c.transcript.value.any { it.text.contains("권장 휴식 간격 75분") })
    }

    // ───────────────────────── 자동 시연 (11차) ─────────────────────────

    @Test
    fun autoDriver_awaitingShowsContext_andNoAnswerCountsAsMiss() {
        val tts = FakeTts(); val host = FakeHost()
        host.level = FatigueLevel.WARNING
        val c = newCoordinator(tts, host, TalkLevel.CHATTY) // 음성 인식 없음(NoStt)
        c.autoDriver = true
        onMain { c.onAssessment(assessment(FatigueLevel.WARNING, t = 60_000)) }
        waitUntil { c.awaiting.value != null }
        assertEquals(ReplyContext.OFFER, c.awaiting.value!!.context)
        // 자동 운전자가 일부러 대답하지 않음 → 마이크가 없어도 무응답으로 셈 (수동 시연에서는 세지 않음)
        waitUntil(12_000) { host.misses == 1 }
        waitUntil { c.busy.value == null }
        assertTrue("기다림이 끝나면 비움", c.awaiting.value == null)
    }

    @Test
    fun autoDriver_submitAuto_usesSpeechStart_andIgnoresStaleQuestion() {
        val tts = FakeTts(); val host = FakeHost()
        host.askSleep = true
        val c = newCoordinator(tts, host, TalkLevel.CHATTY)
        c.autoDriver = true
        onMain { c.onAssessment(assessment(FatigueLevel.NORMAL, listOf(DriveEvent.TripStarted))) }
        waitUntil { c.awaiting.value?.context == ReplyContext.SLEEP }
        val a = c.awaiting.value!!
        assertFalse("다른 질문 번호면 버림", c.submitAuto(a.seq + 99, "다섯 시간 잤어", System.nanoTime() / 1_000_000L))
        Thread.sleep(400)
        val started = System.nanoTime() / 1_000_000L
        Thread.sleep(300) // 말하는 시간 (응답 지연에는 넣지 않음)
        var ok = false
        onMain { ok = c.submitAuto(a.seq, "다섯 시간 잤어", started) }
        assertTrue(ok)
        waitUntil { c.busy.value == null }
        assertEquals(5f, host.sleep!!, 0.01f)
        assertTrue("응답 지연은 말하기 시작한 시각까지", host.baseline.single() in 300L..1_200L)
        assertTrue("운전자 말풍선", c.transcript.value.any { it.speaker == Speaker.DRIVER && it.text == "다섯 시간 잤어" })
    }

    /**
     * 자동 시연 대본의 졸음 장면: 경고 → "반응 게임 하자" → 1.3초·1.9초·놓침 → 휴게소 권유에 무응답
     * → 위험 → "응, 경로에 추가해줘" → 경유지 추가. (AutoDriver의 대답 로직을 그대로 흉내 냄)
     */
    @Test
    fun autoScript_drowsyScene_reactionMissThenRestAccepted() {
        val tts = FakeTts(); val host = FakeHost()
        host.level = FatigueLevel.WARNING
        val c = newCoordinator(tts, host, TalkLevel.CHATTY)
        c.autoDriver = true
        val phase = "졸음 운전"
        val driver: Job = scope.launch {
            var last: ReplyContext? = null
            var round = 0
            val seen = HashMap<ReplyContext, Int>()
            c.awaiting.collect { a ->
                if (a == null) return@collect
                round = if (a.context == last) round + 1 else 0
                last = a.context
                val before = seen[a.context] ?: 0
                seen[a.context] = before + 1
                val r = AutoDriverScript.decide(AutoDriverInput(a.context, a.hints, phase, host.level, before, round)) ?: return@collect
                launch {
                    delay(r.thinkMs)
                    c.submitAuto(a.seq, r.text, System.nanoTime() / 1_000_000L)
                }
            }
        }
        try {
            onMain { c.onAssessment(assessment(FatigueLevel.WARNING, t = 60_000)) }
            // 반응 게임 3판: 2번 반응(느림), 1번 놓침 → 휴게소 권유 → 대답 없음
            waitUntil(30_000) { host.misses == 2 && c.busy.value == null }
            assertEquals(2, host.reactions.size)
            assertTrue("졸음 구간 반응은 느림", host.reactions.all { it >= 1_200L })
            assertTrue("위험 전에는 휴게소를 받아들이지 않음", host.waypoint == null)
            // 위험 → 경고 + 휴게소 → 승낙
            host.level = FatigueLevel.DANGER
            onMain { c.onAssessment(assessment(FatigueLevel.DANGER, t = 900_000)) }
            waitUntil(15_000) { host.waypoint != null }
            assertTrue(c.transcript.value.any { it.speaker == Speaker.DRIVER && it.text == "응, 경로에 추가해줘" })
        } finally {
            driver.cancel()
        }
    }

    // ───────────── 14차: 외기 전환 · 커피냅 · 기억하는 대화 · 게임 다양화 ─────────────

    private fun answerAwaiting(c: DialogCoordinator, ctx: ReplyContext, text: (AwaitingReply) -> String) {
        waitUntil(5_000) { c.awaiting.value?.context == ctx && c.listening.value }
        val a = c.awaiting.value!!
        onMain { c.submitText(text(a)) }
        waitUntil { c.awaiting.value?.seq != a.seq }
    }

    @Test
    fun freshAir_co2Ask_yesSwitchesToFreshAir() {
        val tts = FakeTts(); val host = FakeHost()
        host.level = FatigueLevel.CAUTION
        host.freshAirResult = FreshAirResult.DONE
        val c = newCoordinator(tts, host, TalkLevel.CHATTY)
        onMain { c.onAssessment(assessment(FatigueLevel.CAUTION, t = 60_000)) }
        answerAwaiting(c, ReplyContext.CHECK_IN) { "응" }
        waitUntil { c.busy.value == null }
        // 내기순환은 10분뿐이지만 CO₂ 추정이 1,600ppm → 제안
        onMain { c.onAssessment(assessment(FatigueLevel.CAUTION, t = 200_000).copy(recirculationMin = 10f, co2Ppm = 1_600f)) }
        waitUntil { c.awaiting.value?.context == ReplyContext.FRESH_AIR }
        assertTrue(tts.last().contains("1,600") || tts.last().contains("1600"))
        onMain { c.submitText("응, 바꿔 줘") }
        waitUntil { tts.last() == LunaLines.FRESH_AIR_DONE }
        assertEquals(1, host.freshAirCalls)
    }

    @Test
    fun freshAir_declined_doesNotTouchHvac() {
        val tts = FakeTts(); val host = FakeHost()
        host.level = FatigueLevel.CAUTION
        val c = newCoordinator(tts, host, TalkLevel.CHATTY)
        onMain { c.onAssessment(assessment(FatigueLevel.CAUTION, t = 60_000)) }
        answerAwaiting(c, ReplyContext.CHECK_IN) { "응" }
        waitUntil { c.busy.value == null }
        onMain { c.onAssessment(assessment(FatigueLevel.CAUTION, t = 200_000).copy(co2Ppm = 1_500f)) }
        answerAwaiting(c, ReplyContext.FRESH_AIR) { "아니" }
        waitUntil { tts.last() == LunaLines.FRESH_AIR_DECLINED }
        assertEquals(0, host.freshAirCalls)
    }

    @Test
    fun nap_wakeRetest_thenDeferredStamp() {
        val tts = FakeTts(); val host = FakeHost()
        val c = newCoordinator(tts, host, TalkLevel.CHATTY)
        onMain { c.startNap() }
        waitUntil { c.napping.value && tts.last() == LunaLines.NAP_START }
        assertEquals(15, host.napMinutes)
        // 자는 동안 도장이 나오면 깬 뒤로 미룸
        val record = StampRecord("망향(부산)|36.856|127.181", "망향휴게소", false, "경부선", 1, 0L, 0L)
        val result = StampResult(record, isNew = true, places = 5, shelters = 0, routeHave = 5, routeTotal = 65, bestRoute = 5, food = "명품한우국밥")
        onMain { c.announceStamp(result) }
        Thread.sleep(200)
        assertFalse("자는 동안 말하지 않음", tts.spoken.any { it.contains("도장 쾅") })
        onMain { c.napWake() }
        waitUntil { tts.spoken.contains(LunaLines.NAP_WAKE) }
        repeat(3) { answerAwaiting(c, ReplyContext.REACTION) { "빛" } }
        waitUntil(5_000) { tts.spoken.any { it.contains("도장 쾅") } }
        assertFalse(c.napping.value)
        assertTrue(host.retest != null && host.retest!!.second != null)
        val iResult = tts.spoken.indexOfFirst { it == LunaLines.napResult(host.retest!!.first, host.retest!!.second) }
        val iStamp = tts.spoken.indexOfFirst { it.contains("도장 쾅") }
        assertTrue("재측정 결과 → 도장 순서", iResult in 0 until iStamp)
        assertEquals("반응 3판", 3, cues(tts))
    }

    @Test
    fun nap_offeredAtRestArea_noLeadsToStretching() {
        val tts = FakeTts(); val host = FakeHost()
        val c = newCoordinator(tts, host, TalkLevel.CHATTY)
        onMain { c.onAssessment(assessment(FatigueLevel.NORMAL, listOf(DriveEvent.RestStarted), t = 60_000)) }
        answerAwaiting(c, ReplyContext.NAP) { "아니" }
        waitUntil { tts.spoken.contains(LunaLines.NAP_DECLINED) }
        assertEquals(null, host.napMinutes)
    }

    @Test
    fun memory_destinationQuestionAndFoodRemembered() {
        val tts = FakeTts(); val host = FakeHost()
        host.mem[DriverMemory.Slot.DESTINATION] = "부산"
        val c = newCoordinator(tts, host, TalkLevel.CHATTY)
        onMain { c.onAssessment(assessment(FatigueLevel.NORMAL, listOf(DriveEvent.TripStarted))) }
        waitUntil { c.awaiting.value?.context == ReplyContext.MEMORY }
        assertEquals(LunaLines.destinationAsk("부산"), tts.last())
        onMain { c.submitText("응, 부산 가") }
        waitUntil { c.busy.value == null }
        assertEquals(LunaLines.destinationYes("부산"), tts.last())
        assertEquals("빠르게 대답할 수 있는 질문 → 대답 속도 기준", 1, host.baseline.size)
        // 운전자 말에서 좋아하는 음식 기억
        onMain { c.submitText("나 국밥 좋아해") }
        waitUntil { host.mem[DriverMemory.Slot.FOOD] == "국밥" }
    }

    @Test
    fun memory_favoriteFoodAtStamp() {
        val tts = FakeTts(); val host = FakeHost()
        host.mem[DriverMemory.Slot.FOOD] = "국밥"
        val c = newCoordinator(tts, host, TalkLevel.CHATTY)
        val record = StampRecord("망향(부산)|36.856|127.181", "망향휴게소", false, "경부선", 1, 0L, 0L)
        val result = StampResult(record, isNew = true, places = 5, shelters = 0, routeHave = 5, routeTotal = 65, bestRoute = 5, food = "명품한우국밥")
        onMain { c.announceStamp(result) }
        waitUntil { tts.spoken.isNotEmpty() && c.busy.value == null }
        assertTrue(tts.spoken.first().contains("국밥 좋아하잖아"))
    }

    @Test
    fun chosungGame_threeCorrectAnswers() {
        val tts = FakeTts(); val host = FakeHost()
        val c = newCoordinator(tts, host, TalkLevel.MIN)
        onMain { c.submitText("초성 게임 하자") }
        repeat(3) { answerAwaiting(c, ReplyContext.CHOSUNG) { it.expected!! } }
        waitUntil { c.busy.value == null }
        assertEquals(3, host.mini.count { it.first == GameKind.CHOSUNG })
        assertTrue(tts.spoken.contains(LunaLines.CHOSUNG_START))
    }

    @Test
    fun digitGame_tiredIsShorter_andWrongAnswerSaysTheDigits() {
        val tts = FakeTts(); val host = FakeHost()
        host.level = FatigueLevel.WARNING
        val c = newCoordinator(tts, host, TalkLevel.MIN)
        onMain { c.submitText("숫자 게임 하자") }
        answerAwaiting(c, ReplyContext.DIGITS) { it.expected!! }
        assertEquals(GameKind.DIGITS to 3, host.mini.single())
        answerAwaiting(c, ReplyContext.DIGITS) { "몰라" }
        waitUntil { c.busy.value == null }
        assertTrue(tts.spoken.any { it.startsWith("아까워! 정답은") })
        assertEquals("피곤하면 2번만", 2, tts.spoken.count { line -> !line.startsWith("아까워") && DigitSpan.parse(line).size == 3 })
    }

    @Test
    fun foodQuiz_usesRestAreaMenus_riddleWorks() {
        val tts = FakeTts(); val host = FakeHost()
        host.stops = listOf("망향휴게소" to "명품한우국밥", "천안휴게소" to "호두과자", "칠곡휴게소" to "돈가스", "안성휴게소" to "소고기국밥")
        val c = newCoordinator(tts, host, TalkLevel.MIN)
        onMain { c.submitText("휴게소 음식 퀴즈 내 줘") }
        repeat(2) { answerAwaiting(c, ReplyContext.FOOD) { it.expected!! } }
        waitUntil { c.busy.value == null }
        assertEquals(2, host.mini.count { it.first == GameKind.FOOD })
        onMain { c.submitText("수수께끼 내 줘") }
        repeat(2) { answerAwaiting(c, ReplyContext.RIDDLE) { it.expected!! } }
        waitUntil { c.busy.value == null }
        assertEquals(2, host.mini.count { it.first == GameKind.RIDDLE })
    }

    @Test
    fun boredom_longMonotoneHighway_offersGame() {
        val tts = FakeTts(); val host = FakeHost()
        val c = newCoordinator(tts, host, TalkLevel.NORMAL)
        val a = assessment(FatigueLevel.NORMAL, t = 4_000_000).copy(
            continuousDriveMin = 70f, features = DrivingFeatures.EMPTY.copy(meanSpeedKmh = 100f),
        )
        onMain { c.onAssessment(a) }
        waitUntil { c.awaiting.value?.context == ReplyContext.BOREDOM }
        assertTrue(tts.last().contains("한 판 할래"))
        onMain { c.submitText("아니") }
        waitUntil { c.busy.value == null }
        assertEquals(LunaLines.BOREDOM_DECLINED, tts.last())
        // 거절 뒤 30분 안에는 다시 묻지 않음
        onMain { c.onAssessment(a.copy(timestampMs = 4_600_000)) }
        Thread.sleep(200)
        assertEquals(LunaLines.BOREDOM_DECLINED, tts.last())
    }

    @Test
    fun gameButton_rotatesGamesAndAvoidsRepeats() {
        val tts = FakeTts(); val host = FakeHost()
        val c = newCoordinator(tts, host, TalkLevel.MIN)
        val starts = listOf(LunaLines.CHOSUNG_START, LunaLines.RIDDLE_START, LunaLines.DIGITS_START, LunaLines.FOOD_START)
        val seen = mutableListOf<String>()
        repeat(3) {
            val before = tts.spoken.size
            onMain { c.startGame() }
            waitUntil { c.listening.value }
            seen += tts.spoken.drop(before).firstOrNull { it in starts || it.startsWith("내가 먼저 할게") || it.contains("퀴즈") } ?: "?"
            onMain { c.submitText("그만할래") }
            waitUntil { c.busy.value == null }
        }
        assertEquals("같은 게임이 연달아 나오지 않음", 3, seen.distinct().size)
    }

    // ───────────── 16차: 게임 고르기 · [게임 그만] ─────────────

    @Test
    fun pickGame_chosenKind_andStopButton() {
        val tts = FakeTts(); val host = FakeHost()
        val c = newCoordinator(tts, host, TalkLevel.MIN)
        onMain { c.startGame(GameKind.RIDDLE) }
        waitUntil { c.awaiting.value?.context == ReplyContext.RIDDLE && c.listening.value }
        assertTrue("게임 중 표시", c.inGame.value)
        onMain { c.stopGame() }
        waitUntil { c.busy.value == null && tts.last() == LunaLines.GAME_STOPPED }
        assertFalse(c.inGame.value)
        assertEquals("멈추면 더 묻지 않음", 1, tts.spoken.count { it == LunaLines.RIDDLE_START })
        // 게임이 아니면 [게임 그만]은 아무것도 안 함
        val before = tts.spoken.size
        onMain { c.stopGame() }
        Thread.sleep(200)
        assertEquals(before, tts.spoken.size)
    }

    @Test
    fun pickGame_warningFallsBackToShortGame() {
        val tts = FakeTts(); val host = FakeHost()
        host.level = FatigueLevel.WARNING
        val c = newCoordinator(tts, host, TalkLevel.MIN)
        // 경고에서 끝말잇기를 고르면 반응·숫자 게임으로
        onMain { c.startGame(GameKind.WORD) }
        waitUntil { c.listening.value }
        assertTrue(c.awaiting.value?.context == ReplyContext.REACTION || c.awaiting.value?.context == ReplyContext.DIGITS)
        onMain { c.stopGame() }
        waitUntil { c.busy.value == null }
        // 위험이면 게임 대신 휴게소
        host.level = FatigueLevel.DANGER
        onMain { c.startGame(GameKind.REACTION) }
        waitUntil { c.askingRest.value }
    }

    // ───────────── 18차: 온도 낮추기 · 기다린 순간 세기 · Gleo 확인 ─────────────

    @Test
    fun coolDown_afterCheckIn_yesLowersTemperature() {
        val tts = FakeTts(); val host = FakeHost()
        host.level = FatigueLevel.CAUTION
        host.coolResult = FreshAirResult.DONE
        val c = newCoordinator(tts, host, TalkLevel.CHATTY)
        onMain { c.onAssessment(assessment(FatigueLevel.CAUTION, t = 60_000)) }
        answerAwaiting(c, ReplyContext.CHECK_IN) { "응" }
        waitUntil { c.busy.value == null }
        onMain { c.onAssessment(assessment(FatigueLevel.CAUTION, t = 60_000 + 3 * 60_000)) }
        waitUntil { c.awaiting.value?.context == ReplyContext.COOL_DOWN }
        assertEquals(LunaLines.COOL_ASK, tts.last())
        onMain { c.submitText("응, 낮춰 줘") }
        waitUntil { tts.last() == LunaLines.COOL_DONE }
        assertEquals(1, host.coolCalls)
    }

    @Test
    fun deferrals_countedOncePerEpisode() {
        val tts = FakeTts(); val host = FakeHost()
        val c = newCoordinator(tts, host, TalkLevel.MIN)
        val busy = assessment(FatigueLevel.NORMAL, t = 1_000).copy(driverBusy = BusyReason.LANE_CHANGE)
        onMain { c.onAssessment(busy) }
        onMain { c.onAssessment(busy.copy(timestampMs = 2_000)) }
        onMain { c.onAssessment(assessment(FatigueLevel.NORMAL, t = 3_000)) }
        onMain { c.onAssessment(busy.copy(timestampMs = 4_000)) }
        assertEquals("차선 변경 두 번", 2, host.deferred)
    }

    @Test
    fun gleoRestRequest_whileDriving_asksDriverFirst() {
        val tts = FakeTts(); val host = FakeHost()
        val c = newCoordinator(tts, host, TalkLevel.MIN)
        onMain { c.confirmRestFromAssistant() }
        waitUntil { c.askingRest.value }
        assertTrue("확인 전에는 경로를 바꾸지 않음", host.waypoint == null)
        onMain { c.acceptRest() }
        waitUntil { host.waypoint != null }
    }
}
