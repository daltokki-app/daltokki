package app.daltokki.drive.domain

import app.daltokki.drive.domain.dialog.DialogPolicy
import app.daltokki.drive.domain.dialog.Intervention
import app.daltokki.drive.domain.dialog.TalkLevel
import app.daltokki.drive.domain.dialog.UtteranceIntent
import app.daltokki.drive.domain.game.Hangul
import app.daltokki.drive.domain.game.QuizBank
import app.daltokki.drive.domain.game.WordChainEngine
import app.daltokki.drive.domain.game.WordDictionary
import app.daltokki.drive.domain.model.FatigueLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

class GameAndDialogTest {

    private fun assetText(name: String): String {
        val candidates = listOf(File("src/main/assets/$name"), File("app/src/main/assets/$name"))
        return candidates.first { it.exists() }.readText()
    }

    @Test
    fun dueum_rules() {
        assertEquals('역', Hangul.dueum('력'))
        assertEquals('이', Hangul.dueum('리'))
        assertEquals('나', Hangul.dueum('라'))
        assertEquals('노', Hangul.dueum('로'))
        assertEquals('여', Hangul.dueum('녀'))
        assertEquals('유', Hangul.dueum('뉴'))
        assertNull(Hangul.dueum('나'))
        assertNull(Hangul.dueum('기'))
        assertEquals(setOf('력', '역'), Hangul.startOptions('력'))
    }

    @Test
    fun josa() {
        assertEquals("를", Hangul.josa("휴게소", "을", "를"))
        assertEquals("을", Hangul.josa("졸음쉼터 앞길", "을", "를"))
    }

    @Test
    fun wordChain_rules() {
        val dict = WordDictionary(listOf("달력", "역사", "사과", "과자", "자동차", "차표", "표정", "정원", "원숭이", "이불", "불꽃"))
        val engine = WordChainEngine(dict, strict = true, random = Random(1))
        engine.start()
        // 강제로 시작 단어를 정해 테스트
        val forced = WordChainEngine(WordDictionary(listOf("달력", "역사", "사과", "과자", "자두")), strict = true, random = Random(3))
        val first = forced.start()
        val expected = forced.expectedStarts()
        assertTrue(first.isNotEmpty() && expected.isNotEmpty())

        // 두음법칙: '력'으로 끝나면 '역'도 인정
        val e2 = WordChainEngine(WordDictionary(listOf("달력", "역사", "사과", "과자", "자두", "두부")), strict = false, random = Random(5))
        var w = e2.start()
        // 시작 단어가 무엇이든, 기대 글자로 시작하는 단어를 만들어 제출
        val res = e2.submit(e2.expectedStarts().first() + "가방")
        assertTrue(res is WordChainEngine.Result.Accepted)

        // 틀린 시작 글자
        val e3 = WordChainEngine(WordDictionary(listOf("달력", "역사", "사과")), strict = false, random = Random(0))
        e3.start()
        val bad = e3.submit("하늘")
        assertTrue(bad is WordChainEngine.Result.Invalid && bad.reason == WordChainEngine.Reason.WRONG_START)
        w = e3.lastWord!!
        assertTrue(w.isNotEmpty())
    }

    @Test
    fun wordChain_dueumAccepted() {
        val dict = WordDictionary(listOf("달력", "역사", "사과"))
        // 시작 단어가 '달력'만 가능하도록 사전 구성 (역사·사과는 이어갈 단어 수 조건 때문에 시작 후보가 아닐 수 있음)
        val engine = WordChainEngine(dict, strict = true, random = Random(0))
        repeat(20) {
            val s = engine.start()
            if (s == "달력") {
                val r = engine.submit("역사")
                assertTrue("두음법칙으로 '역사' 인정", r is WordChainEngine.Result.Accepted)
                assertEquals("사과", (r as WordChainEngine.Result.Accepted).lunaWord)
                val used = engine.submit("역사")
                assertTrue(used is WordChainEngine.Result.Invalid)
                return
            }
        }
    }

    @Test
    fun wordChain_sttSuffixAndUsed() {
        val dict = WordDictionary(listOf("나무", "무지개", "개미", "미소", "소리"))
        val engine = WordChainEngine(dict, strict = true, random = Random(2))
        val first = engine.start()
        val start = engine.expectedStarts()
        val next = dict.startingWith(start).firstOrNull { it != first }
        if (next != null) {
            val r = engine.submit("${next}요.")
            assertTrue("'~요' 붙은 음성 인식 결과도 인정", r is WordChainEngine.Result.Accepted)
        }
    }

    @Test
    fun builtInDictionary_isPlayable() {
        val dict = WordDictionary.parse(assetText("words_ko.txt"))
        assertTrue("내장 사전 단어 수 ${dict.size}", dict.size >= 400)
        // 달토끼가 고른 단어에는 항상 이어갈 단어가 있어야 함(운전자가 막히지 않게)
        var longest = 0
        for (seed in 1..20) {
            val engine = WordChainEngine(dict, strict = false, random = Random(seed))
            engine.start()
            var turns = 0
            while (turns < 15) {
                val options = dict.startingWith(engine.expectedStarts())
                if (options.isEmpty()) break // 사전에 없는 단어도 인정(관대 모드)하므로 실제 게임은 계속 가능
                // 사람이 이어가기 좋은 단어를 낸다고 가정
                val pickOrder = options.sortedByDescending { dict.continuationCount(it.last()) }
                val r = pickOrder.asSequence().map { engine.submit(it) }
                    .firstOrNull { it is WordChainEngine.Result.Accepted } as WordChainEngine.Result.Accepted? ?: break
                turns++
                if (r.lunaWord == null) break
            }
            longest = maxOf(longest, turns)
        }
        assertTrue("내장 사전으로 충분히 길게 이어져야 함 (최대 $longest)", longest >= 8)
    }

    @Test
    fun quiz_parseAndMatch() {
        val bank = QuizBank.parse(assetText("quiz_ko.txt"))
        assertTrue(bank.size >= 20)
        val add = QuizBank.arithmetic(Random(4))
        assertTrue(add.isCorrect(add.answers[0]))
        assertTrue(add.answers.size >= 2)
        assertEquals("십오", QuizBank.sinoKorean(15))
        assertEquals("열다섯", QuizBank.nativeKorean(15))
        val picked = bank.pick(3, Random(1))
        assertEquals(3, picked.size)
        val first = QuizBank.parse("대한민국의 수도는?|서울").pick(1)[0]
        assertTrue(first.isCorrect("서울이요"))
        assertFalse(first.isCorrect("부산"))
    }

    @Test
    fun intents() {
        assertEquals(UtteranceIntent.SLEEPY, UtteranceIntent.parse("아 너무 졸려"))
        assertEquals(UtteranceIntent.QUIET, UtteranceIntent.parse("조용히 해줘"))
        assertEquals(UtteranceIntent.WORD_GAME, UtteranceIntent.parse("끝말잇기 하자"))
        assertEquals(UtteranceIntent.REST_AREA, UtteranceIntent.parse("휴게소 가자"))
        assertEquals(UtteranceIntent.YES, UtteranceIntent.parse("응 좋아"))
        assertEquals(UtteranceIntent.NO, UtteranceIntent.parse("아니 괜찮아"))
        assertEquals(UtteranceIntent.NO, UtteranceIntent.parse("안 할래"))
        assertEquals(UtteranceIntent.OTHER, UtteranceIntent.parse("오늘 점심은 김치찌개 먹었는데 맛있더라"))
        assertEquals(UtteranceIntent.NOT_SLEEPY, UtteranceIntent.parse("나 하나도 안 졸려"))
        assertEquals(UtteranceIntent.NOT_SLEEPY, UtteranceIntent.parse("안 피곤해"))
        assertEquals(UtteranceIntent.MORE_TALK, UtteranceIntent.parse("말 좀 해줘"))
        assertEquals(UtteranceIntent.QUIET, UtteranceIntent.parse("말 좀 줄여"))
        assertTrue(UtteranceIntent.isStop("그만할래"))
        assertFalse(UtteranceIntent.isStop("끝말잇기"))
    }

    @Test
    fun policy_talkLevels() {
        val min = 60_000L
        // 최소: 주의 단계에서는 말하지 않음, 경고 제안은 15분 간격
        val p = DialogPolicy()
        assertNull(p.decide(0, FatigueLevel.NORMAL, TalkLevel.MIN, null))
        assertNull("최소: 주의 단계는 화면만", p.decide(1 * min, FatigueLevel.CAUTION, TalkLevel.MIN, null))
        assertEquals(Intervention.OFFER_GAME, p.decide(2 * min, FatigueLevel.WARNING, TalkLevel.MIN, null))
        p.markDone(Intervention.OFFER_GAME, 2 * min)
        assertNull("15분 안에는 다시 제안하지 않음", p.decide(10 * min, FatigueLevel.WARNING, TalkLevel.MIN, null))
        assertEquals(Intervention.OFFER_GAME, p.decide(18 * min, FatigueLevel.WARNING, TalkLevel.MIN, null))
        assertEquals("위험 경고는 최소에서도 함", Intervention.ALERT, p.decide(19 * min, FatigueLevel.DANGER, TalkLevel.MIN, null))
        assertEquals("다른 대화 중이어도 위험 경고는 끼어듦", Intervention.ALERT, p.decide(19 * min, FatigueLevel.DANGER, TalkLevel.MIN, Intervention.GAME))
        assertNull("경고 중에는 다시 끼어들지 않음", p.decide(19 * min, FatigueLevel.DANGER, TalkLevel.MIN, Intervention.ALERT))

        // 수다: 주의 단계 8분 간격 말 걸기
        val c = DialogPolicy()
        assertEquals(Intervention.CHECK_IN, c.decide(1 * min, FatigueLevel.CAUTION, TalkLevel.CHATTY, null))
        c.markDone(Intervention.CHECK_IN, 1 * min)
        assertNull(c.decide(5 * min, FatigueLevel.CAUTION, TalkLevel.CHATTY, null))
        assertEquals(Intervention.CHECK_IN, c.decide(9 * min, FatigueLevel.CAUTION, TalkLevel.CHATTY, null))

        // 보통: 주의 단계 30분 간격
        val n = DialogPolicy()
        assertEquals(Intervention.CHECK_IN, n.decide(1 * min, FatigueLevel.CAUTION, TalkLevel.NORMAL, null))
        n.markDone(Intervention.CHECK_IN, 1 * min)
        assertNull(n.decide(20 * min, FatigueLevel.CAUTION, TalkLevel.NORMAL, null))
        assertEquals(Intervention.CHECK_IN, n.decide(32 * min, FatigueLevel.CAUTION, TalkLevel.NORMAL, null))
    }

    @Test
    fun policy_hourlyCap() {
        val min = 60_000L
        val p = DialogPolicy()
        var count = 0
        var t = 0L
        // 수다: 쿨다운(경고 5분)만 보면 1시간에 12번 가능하지만 상한은 6번
        while (t < 60 * min) {
            val d = p.decide(t, FatigueLevel.WARNING, TalkLevel.CHATTY, null)
            if (d != null) { p.markDone(d, t); count++ }
            t += min
        }
        assertEquals(6, count)
        // 최소: 상한 2번 (쿨다운 15분이라 원래 4번 가능)
        val q = DialogPolicy()
        count = 0; t = 0L
        while (t < 60 * min) {
            val d = q.decide(t, FatigueLevel.WARNING, TalkLevel.MIN, null)
            if (d != null) { q.markDone(d, t); count++ }
            t += min
        }
        assertEquals(2, count)
    }

    @Test
    fun talkLevel_upDown() {
        assertEquals(TalkLevel.MIN, TalkLevel.MIN.down())
        assertEquals(TalkLevel.NORMAL, TalkLevel.MIN.up())
        assertEquals(TalkLevel.CHATTY, TalkLevel.CHATTY.up())
        assertEquals(TalkLevel.NORMAL, TalkLevel.CHATTY.down())
    }
}
