package app.daltokki.drive.domain

import app.daltokki.drive.domain.dialog.ReplyContext
import app.daltokki.drive.domain.dialog.UtteranceIntent
import app.daltokki.drive.domain.fatigue.RestPlanner
import app.daltokki.drive.domain.model.FatigueLevel
import app.daltokki.drive.domain.sim.AutoAction
import app.daltokki.drive.domain.sim.AutoDriverInput
import app.daltokki.drive.domain.sim.AutoDriverScript
import app.daltokki.drive.domain.sim.DemoCueSheet
import app.daltokki.drive.domain.sim.Scenarios
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 자동 시연 대본·큐시트 (11차) */
class AutoDemoTest {

    private fun input(
        ctx: ReplyContext,
        phase: String,
        level: FatigueLevel = FatigueLevel.NORMAL,
        hints: List<String> = emptyList(),
        seen: Int = 0,
        round: Int = 0,
    ) = AutoDriverInput(ctx, hints, phase, level, seen, round)

    @Test
    fun script_answersMatchTheAppsIntentParser() {
        // 대본의 대답이 앱의 의도 해석과 맞아야 원하는 장면이 나옴
        val sleep = AutoDriverScript.decide(input(ReplyContext.SLEEP, "출발 준비"))!!
        assertEquals(5f, RestPlanner.parseSleepHours(sleep.text)!!, 0.01f)
        val offer = AutoDriverScript.decide(input(ReplyContext.OFFER, "졸음 운전", FatigueLevel.WARNING))!!
        assertEquals(UtteranceIntent.REACTION_GAME, UtteranceIntent.parse(offer.text))
        val rest = AutoDriverScript.decide(input(ReplyContext.REST, "졸음 운전", FatigueLevel.DANGER))!!
        assertTrue("승낙(거절로 읽히면 안 됨)", UtteranceIntent.parse(rest.text) == UtteranceIntent.YES)
        assertEquals(UtteranceIntent.CHOSUNG_GAME, UtteranceIntent.parse(AutoDriverScript.ASK_WORD_GAME_TEXT))
        // 14차: 새 질문에 대한 대답도 앱이 그대로 알아들어야 함
        for (ctx in listOf(ReplyContext.FRESH_AIR, ReplyContext.NAP, ReplyContext.BOREDOM, ReplyContext.MEMORY)) {
            val r = AutoDriverScript.decide(input(ctx, "피로 누적", FatigueLevel.CAUTION))!!
            assertEquals(ctx.name, UtteranceIntent.YES, UtteranceIntent.parse(r.text))
        }
        val chosung = AutoDriverScript.decide(AutoDriverInput(ReplyContext.CHOSUNG, listOf("기차", "그만할래"), "피로 누적", FatigueLevel.CAUTION, 0, 0, expected = "기차"))!!
        assertEquals("기차", chosung.text)
        // 커피냅 뒤 재측정(휴게소)은 빠르게
        val retest = AutoDriverScript.decide(input(ReplyContext.REACTION, "휴게소 휴식", FatigueLevel.WARNING))!!
        assertTrue(retest.thinkMs < 1_000)
        assertTrue(UtteranceIntent.isStop(AutoDriverScript.STOP))
        // 피곤할 때 말 걸기 대답이 '졸려'로 읽혀 바로 휴식 흐름이 되면 안 됨
        val checkIn = AutoDriverScript.decide(input(ReplyContext.CHECK_IN, "피로 누적", FatigueLevel.CAUTION))!!
        assertEquals(UtteranceIntent.OTHER, UtteranceIntent.parse(checkIn.text))
        assertTrue("피로 구간에서는 대답이 느려짐", checkIn.thinkMs >= 2_000)
        val fresh = AutoDriverScript.decide(input(ReplyContext.CHECK_IN, "고속도로 · 또렷함"))!!
        assertTrue(fresh.thinkMs < 1_200)
    }

    @Test
    fun script_drowsyPhase_noAnswersAndSlowReactions() {
        assertNull("졸음 구간 말 걸기에는 무응답", AutoDriverScript.decide(input(ReplyContext.CHECK_IN, "졸음 운전", FatigueLevel.WARNING)))
        assertNull("위험 전 휴게소 권유에는 무응답", AutoDriverScript.decide(input(ReplyContext.REST, "졸음 운전", FatigueLevel.WARNING)))
        assertNull("두 번째 제안부터는 무응답", AutoDriverScript.decide(input(ReplyContext.OFFER, "졸음 운전", FatigueLevel.WARNING, seen = 1)))
        val r0 = AutoDriverScript.decide(input(ReplyContext.REACTION, "졸음 운전", FatigueLevel.WARNING, round = 0))!!
        val r1 = AutoDriverScript.decide(input(ReplyContext.REACTION, "졸음 운전", FatigueLevel.WARNING, round = 1))!!
        val r2 = AutoDriverScript.decide(input(ReplyContext.REACTION, "졸음 운전", FatigueLevel.WARNING, round = 2))
        assertTrue(r0.thinkMs >= 1_200 && r1.thinkMs > r0.thinkMs)
        assertNull("세 번째는 놓침", r2)
        // 또렷할 때 반응은 빠름 (기준 1.2초보다 빠르게)
        for (i in 0 until 5) {
            val r = AutoDriverScript.decide(input(ReplyContext.REACTION, "고속도로 · 또렷함", round = i))!!
            assertTrue(r.thinkMs < 1_000)
        }
    }

    @Test
    fun script_wordGame_threeHintsThenStop() {
        val hints = listOf("기차", "기러기", "그만할래")
        for (round in 0 until AutoDriverScript.WORD_TURNS) {
            assertEquals("기차", AutoDriverScript.decide(input(ReplyContext.WORD, "피로 누적", hints = hints, round = round))!!.text)
        }
        assertEquals(AutoDriverScript.STOP, AutoDriverScript.decide(input(ReplyContext.WORD, "피로 누적", hints = hints, round = 3))!!.text)
        assertEquals("힌트가 없으면 그만", AutoDriverScript.STOP, AutoDriverScript.decide(input(ReplyContext.WORD, "피로 누적", hints = listOf("그만할래")))!!.text)
    }

    @Test
    fun script_actions_followNightScenarioPhases() {
        // 새벽 고속도로 시나리오 구간 이름이 대본 키워드와 맞는지 (이름이 바뀌면 이 테스트가 알려 줌)
        val phases = Scenarios.nightHighway.phases.map { it.label }
        assertTrue(phases.any { it.contains("또렷") })
        assertTrue(phases.any { AutoDriverScript.isTiring(it) })
        assertTrue(phases.any { AutoDriverScript.isDrowsy(it) })
        val done = HashSet<AutoAction>()
        assertEquals(AutoAction.LANE_CHANGE, AutoDriverScript.actionFor("고속도로 · 또렷함", FatigueLevel.NORMAL, done))
        done += AutoAction.LANE_CHANGE
        assertNull(AutoDriverScript.actionFor("고속도로 · 또렷함", FatigueLevel.NORMAL, done))
        assertEquals(AutoAction.CALL, AutoDriverScript.actionFor("피로 누적", FatigueLevel.CAUTION, done))
        done += AutoAction.CALL
        assertEquals(AutoAction.ASK_WORD_GAME, AutoDriverScript.actionFor("피로 누적", FatigueLevel.CAUTION, done))
        assertNull("경고면 끝말잇기를 먼저 청하지 않음", AutoDriverScript.actionFor("피로 누적", FatigueLevel.WARNING, done))
        // 장면 자막: 출발 → 조용히 → 먼저 알아챔 → 졸음 → 휴식
        val scenes = phases.mapNotNull { AutoDriverScript.sceneForPhase(it) }.distinct()
        assertEquals(
            listOf(
                AutoDriverScript.Scene.START, AutoDriverScript.Scene.QUIET, AutoDriverScript.Scene.EARLY,
                AutoDriverScript.Scene.DROWSY, AutoDriverScript.Scene.REST, AutoDriverScript.Scene.REPORT,
            ),
            scenes,
        )
    }

    @Test
    fun cueSheet_srtAndTsv() {
        val cue = DemoCueSheet { 2_000L }
        cue.add(1_000, DemoCueSheet.Kind.TOKKI, "어젯밤 몇 시간 잤어?")
        cue.add(2_500, DemoCueSheet.Kind.DRIVER, "다섯 시간 잤어")
        cue.add(9_000, DemoCueSheet.Kind.CAPTION, "② 멀쩡할 때는 조용히 — 차선 변경 중에는 기다려요")
        cue.add(9_500, DemoCueSheet.Kind.NOTE, "차선 변경 중 · 말 걸기 잠시 대기")
        val srt = cue.dialogSrt()
        assertTrue(srt.startsWith("1\n00:00:01,000 --> 00:00:02,450\n달토끼: 어젯밤 몇 시간 잤어?"))
        assertTrue("다음 대사 전까지만", srt.contains("00:00:02,500 --> 00:00:04,500\n운전자: 다섯 시간 잤어"))
        assertTrue("장면·기록은 대사 자막에 넣지 않음", !srt.contains("멀쩡") && !srt.contains("대기"))
        assertTrue(cue.captionSrt().contains("00:00:09,000 --> 00:00:13,000\n② 멀쩡할 때는 조용히"))
        val tsv = cue.tsv().lines()
        assertEquals("시작(초)\t끝(초)\t화자\t문장", tsv[0])
        assertEquals("1.0\t2.5\t달토끼\t어젯밤 몇 시간 잤어?", tsv[1])
        assertEquals(5, tsv.count { it.isNotBlank() })
        assertEquals("01:02:03,004", DemoCueSheet.srtTime(3_723_004))
        assertNotNull(DemoCueSheet.paceMs("가"))
        assertEquals(9_000L, DemoCueSheet.paceMs("가".repeat(200)))
    }
}
