package app.daltokki.drive.domain

import app.daltokki.drive.domain.dialog.DialogPolicy
import app.daltokki.drive.domain.dialog.DriverMemory
import app.daltokki.drive.domain.dialog.Intervention
import app.daltokki.drive.domain.dialog.TalkLevel
import app.daltokki.drive.domain.dialog.UtteranceIntent
import app.daltokki.drive.domain.driver.DriverCodec
import app.daltokki.drive.domain.driver.DriverProfile
import app.daltokki.drive.domain.driver.DriverRoster
import app.daltokki.drive.domain.fatigue.CabinAirModel
import app.daltokki.drive.domain.game.AwakeScore
import app.daltokki.drive.domain.game.Badges
import app.daltokki.drive.domain.game.ChosungGame
import app.daltokki.drive.domain.game.DigitSpan
import app.daltokki.drive.domain.game.FoodQuestion
import app.daltokki.drive.domain.game.GameKind
import app.daltokki.drive.domain.game.GamePicker
import app.daltokki.drive.domain.game.Riddles
import app.daltokki.drive.domain.game.WordDictionary
import app.daltokki.drive.domain.model.FatigueLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** 14차: 실내 CO₂ 추정 · 게임 다양화 · 기억하는 대화 · 지루함 예방 · 커피냅 배지 */
class Features14Test {

    private val MIN = 60_000L

    private fun run(model: CabinAirModel, minutes: Int, recirc: Boolean?, fan: Int = 2, people: Int = 1, start: Long = 0L): Long {
        var t = start
        repeat(minutes * 60) {
            model.update(t, recirc, fan, people, driving = true)
            t += 1_000L
        }
        return t
    }

    @Test
    fun cabinAir_recirculationRaisesCo2_freshAirClearsIt() {
        val m = CabinAirModel()
        var t = run(m, 8, recirc = true)
        val after8 = m.ppm!!
        assertTrue("혼자 내기순환 8분 → 1,200ppm 근처 ($after8)", after8 in 1_100f..1_400f)
        t = run(m, 22, recirc = true, start = t)
        val after30 = m.ppm!!
        assertTrue("30분이면 2,000ppm 넘음 ($after30)", after30 > 2_000f)
        // 4명이면 훨씬 빨리 오름
        val four = CabinAirModel()
        run(four, 8, recirc = true, people = 4)
        assertTrue(four.ppm!! > after30)
        // 외기 + 팬 4단 → 몇 분 만에 바깥 수준으로
        run(m, 5, recirc = false, fan = 4, start = t)
        assertTrue("외기 5분 뒤 ${m.ppm}", m.ppm!! < 700f)
    }

    @Test
    fun cabinAir_unknownRecirculation_noEstimate_andRiskIsSmall() {
        val m = CabinAirModel()
        run(m, 5, recirc = null)
        assertNull("내기순환을 모르면 추정하지 않음", m.ppm)
        assertEquals(0f, CabinAirModel.risk(null), 0f)
        assertEquals(0f, CabinAirModel.risk(900f), 0f)
        assertEquals(0.05f, CabinAirModel.risk(2_000f), 0.001f)
        assertEquals("최대 +0.1 (보조 지표)", 0.1f, CabinAirModel.risk(5_000f), 0.001f)
        // 배속 재생에서 큰 시간 간격도 안정적 (정확한 지수 해)
        val big = CabinAirModel()
        big.update(0, true, 2, 1, true)
        big.update(60_000, true, 2, 1, true)
        assertTrue(big.ppm!! < 700f)
    }

    @Test
    fun policy_freshAirByCo2_andBoredom() {
        val p = DialogPolicy()
        assertTrue("내기순환 10분이어도 CO₂가 높으면 제안", p.decideFreshAir(10 * MIN, FatigueLevel.CAUTION, TalkLevel.NORMAL, 10f, null, co2Ppm = 1_300f))
        assertFalse(p.decideFreshAir(10 * MIN, FatigueLevel.CAUTION, TalkLevel.NORMAL, 10f, null, co2Ppm = 900f))
        assertFalse("최소에서는 말하지 않음", p.decideFreshAir(10 * MIN, FatigueLevel.CAUTION, TalkLevel.MIN, 30f, null, co2Ppm = 2_000f))
        assertFalse("위험이면 휴식이 먼저", p.decideFreshAir(10 * MIN, FatigueLevel.DANGER, TalkLevel.NORMAL, 30f, null, co2Ppm = 2_000f))

        val b = DialogPolicy()
        assertFalse("고속도로가 아니면 X", b.decideBoredom(70 * MIN, FatigueLevel.NORMAL, TalkLevel.NORMAL, 70f, false, null, null))
        assertFalse("60분 전", b.decideBoredom(50 * MIN, FatigueLevel.NORMAL, TalkLevel.NORMAL, 50f, true, null, null))
        assertFalse("경고면 말놀이 대신 다른 대응", b.decideBoredom(70 * MIN, FatigueLevel.WARNING, TalkLevel.NORMAL, 70f, true, null, null))
        assertFalse("최근 게임 뒤 30분 안", b.decideBoredom(70 * MIN, FatigueLevel.NORMAL, TalkLevel.NORMAL, 70f, true, 50 * MIN, null))
        assertTrue(b.decideBoredom(70 * MIN, FatigueLevel.NORMAL, TalkLevel.NORMAL, 70f, true, null, null))
        b.markDone(Intervention.BOREDOM_GAME, 70 * MIN)
        assertFalse("한 번 하면 30분 쉼", b.decideBoredom(90 * MIN, FatigueLevel.NORMAL, TalkLevel.NORMAL, 90f, true, null, null))
        assertTrue(b.decideBoredom(101 * MIN, FatigueLevel.NORMAL, TalkLevel.NORMAL, 101f, true, null, null))
    }

    @Test
    fun gamePicker_byLevel_noRepeats() {
        val r = Random(1)
        assertNull(GamePicker.pick(FatigueLevel.DANGER, emptyList(), true, r))
        repeat(20) {
            val g = GamePicker.pick(FatigueLevel.WARNING, emptyList(), true, Random(it))!!
            assertTrue("경고면 반응·숫자만", g == GameKind.REACTION || g == GameKind.DIGITS)
        }
        assertEquals(GameKind.DIGITS, GamePicker.pick(FatigueLevel.WARNING, listOf(GameKind.REACTION), true, r))
        val recent = mutableListOf<GameKind>()
        repeat(6) {
            val g = GamePicker.pick(FatigueLevel.NORMAL, recent, foodAvailable = false, random = r)!!
            assertTrue("최근 2번과 다름", g !in recent.takeLast(2))
            assertTrue("음식 데이터 없으면 음식 퀴즈 X", g != GameKind.FOOD)
            recent += g
        }
        assertTrue("거꾸로 세기 같은 부담 큰 게임은 없음", GameKind.entries.none { it.label.contains("거꾸로") })
    }

    @Test
    fun chosung_questionsAndChecking() {
        val dict = WordDictionary(listOf("기차", "기초", "고추", "나비", "나방", "사과", "수건", "가방"))
        val g = ChosungGame(dict, Random(3))
        assertEquals("ㄱㅊ", ChosungGame.pattern("기차"))
        assertEquals("기역 치읓", ChosungGame.spokenOf("ㄱㅊ"))
        val q = g.next()!!
        assertTrue("정답이 2개 이상인 문제 먼저", q.answers.size >= 2)
        assertEquals(q.answers.first(), g.check(q, "음... ${q.answers.first()}!"))
        assertNull(g.check(q, "몰라"))
        val gc = ChosungGame.Question("ㄱㅊ", "기역 치읓", listOf("고추", "기차", "기초"))
        assertEquals("사전에 없어도 초성이 맞으면 인정", "가치", g.check(gc, "가치"))
        assertEquals("조사 붙은 말도", "기차", g.check(gc, "기차요"))
        val used = mutableSetOf<String>()
        while (true) used += (g.next(used) ?: break).pattern
        assertEquals("같은 문제 안 냄", setOf("ㄱㅊ", "ㄴㅂ", "ㅅㄱ", "ㄱㅂ"), used)
    }

    @Test
    fun digitSpan_parsesNumbersSinoAndNative() {
        assertEquals(listOf(3, 8, 5), DigitSpan.parse("삼 팔 오"))
        assertEquals(listOf(3, 8, 5), DigitSpan.parse("385"))
        assertEquals(listOf(3, 8, 5), DigitSpan.parse("셋 여덟 다섯"))
        assertEquals(listOf(1, 2, 9, 7), DigitSpan.parse("일, 이, 구, 칠"))
        assertTrue(DigitSpan.isCorrect(listOf(4, 6, 2), "사 육 이요"))
        assertFalse(DigitSpan.isCorrect(listOf(4, 6, 2), "사 육"))
        val d = DigitSpan.make(4, Random(9))
        assertEquals(4, d.size)
        assertTrue("같은 숫자가 연달아 나오지 않음", d.zipWithNext().none { it.first == it.second })
        assertTrue(d.all { it in 1..9 })
        assertEquals(d, DigitSpan.parse(DigitSpan.spoken(d)))
    }

    @Test
    fun foodQuiz_andRiddles() {
        val stops = listOf("망향휴게소" to "명품한우국밥", "천안휴게소" to "호두과자", "칠곡휴게소" to "돈가스", "안성휴게소" to "소고기국밥")
        val qs = FoodQuestion.make(stops, 2, Random(5))
        assertEquals(2, qs.size)
        val q = qs.first()
        assertEquals("망향휴게소", q.stopName)
        assertEquals(3, q.choices.size)
        assertFalse("비슷한 메뉴(국밥끼리)는 보기에 같이 안 넣음", q.choices.contains("소고기국밥"))
        assertTrue(q.isCorrect("한우국밥"))
        assertTrue(q.isCorrect("명품 한우 국밥이요"))
        assertFalse(q.isCorrect("모르겠어"))
        assertTrue("데이터가 적으면 안 냄", FoodQuestion.make(stops.take(2), 2, Random(5)).isEmpty())
        for (rd in Riddles.all) assertTrue(rd.question, rd.isCorrect(rd.answers.first()))
        assertEquals(2, Riddles.pick(2, Random(1)).distinct().size)
    }

    @Test
    fun memory_extractAndCodec() {
        assertEquals(mapOf(DriverMemory.Slot.DESTINATION to "부산"), DriverMemory.extract("부산 가는 길이야"))
        assertEquals("강릉", DriverMemory.extract("강릉으로 가")[DriverMemory.Slot.DESTINATION])
        assertEquals("부산", DriverMemory.extract("응, 부산 가")[DriverMemory.Slot.DESTINATION])
        assertNull("휴게소는 목적지가 아님", DriverMemory.extract("휴게소 가자")[DriverMemory.Slot.DESTINATION])
        assertNull("다른 데 가", DriverMemory.extract("아니, 다른 데 가")[DriverMemory.Slot.DESTINATION])
        assertEquals("국밥", DriverMemory.extract("나 국밥 좋아해")[DriverMemory.Slot.FOOD])
        assertNull("먹은 것만으로는 좋아하는 음식으로 바꾸지 않음", DriverMemory.extract("아까 김밥 먹었어")[DriverMemory.Slot.FOOD])
        assertNull(DriverMemory.extract("국밥 싫어")[DriverMemory.Slot.FOOD])
        assertEquals("아메리카노", DriverMemory.extract("아메리카노 마셨어")[DriverMemory.Slot.DRINK])
        assertTrue(DriverMemory.extract("응").isEmpty())
        assertTrue(DriverMemory.foodMatches("국밥", "명품한우국밥"))
        assertFalse(DriverMemory.foodMatches("국밥", "호두과자"))

        val m = mapOf(DriverMemory.Slot.DESTINATION to "부산", DriverMemory.Slot.FOOD to "국밥")
        assertEquals(m, DriverMemory.decode(DriverMemory.encode(m)))
        assertTrue(DriverMemory.decode(null).isEmpty())
        // 운전자 저장 글에도 들어감 (이전 버전 기록은 기억 없음)
        val roster = DriverRoster.initial(DriverProfile("d1", "엄마", 0).remember(m))
        assertEquals(m, DriverCodec.decode(DriverCodec.encode(roster))!!.current.memory)
        assertTrue(DriverProfile("d2", "아빠", 1).memory.isEmpty())
    }

    @Test
    fun gameBadges_andNap() {
        val s = AwakeScore()
        s.startTrip()
        assertTrue(s.onGameCorrect(GameKind.CHOSUNG).isEmpty())
        assertTrue(s.onGameCorrect(GameKind.CHOSUNG).isEmpty())
        assertEquals(listOf(Badges.CHOSUNG), s.onGameCorrect(GameKind.CHOSUNG))
        assertTrue("한 번만", s.onGameCorrect(GameKind.CHOSUNG).isEmpty())
        assertTrue("세 자리는 배지 아님", s.onGameCorrect(GameKind.DIGITS, 3).isEmpty())
        assertEquals(listOf(Badges.MEMORY), s.onGameCorrect(GameKind.DIGITS, 4))
        assertEquals(listOf(Badges.FOODIE), s.onGameCorrect(GameKind.FOOD))
        assertEquals(0, s.onNapRetest(faster = false).points)
        val nap = s.onNapRetest(faster = true)
        assertEquals(AwakeScore.NAP_POINTS, nap.points)
        assertTrue(nap.newBadges.contains(Badges.NAP))
        assertEquals(17, Badges.all.size)
        assertEquals(Badges.all.size, Badges.all.map { it.id }.distinct().size)
        // 커피냅·환기 질문 "응, 잠깐 잘게"·"응, 바꿔 줘"는 승낙
        assertEquals(UtteranceIntent.YES, UtteranceIntent.parse("응, 잠깐 잘게"))
        assertEquals(UtteranceIntent.CHOSUNG_GAME, UtteranceIntent.parse("초성 게임 하자"))
        assertEquals(UtteranceIntent.DIGIT_GAME, UtteranceIntent.parse("숫자 게임"))
        assertEquals(UtteranceIntent.FOOD_QUIZ, UtteranceIntent.parse("휴게소 음식 퀴즈 내 줘"))
        assertEquals(UtteranceIntent.RIDDLE_GAME, UtteranceIntent.parse("수수께끼 내 줘"))
        assertEquals(UtteranceIntent.ANY_GAME, UtteranceIntent.parse("게임 한 판 하자"))
    }

    // ───────────── 16차: 기본 운전자 이름 · 게임 고르기 ─────────────

    @Test
    fun driverDefaultNames() {
        assertEquals("운전자 1", DriverRoster.defaultName(1))
        assertTrue(DriverRoster.isDefaultName("운전자 1"))
        assertTrue(DriverRoster.isDefaultName("운전자 12"))
        assertFalse(DriverRoster.isDefaultName("아빠"))
        assertFalse(DriverRoster.isDefaultName("운전자"))
        assertEquals("운전자 2", DriverRoster.nextDefaultName(listOf("운전자 1")))
        assertEquals("빈 번호부터", "운전자 2", DriverRoster.nextDefaultName(listOf("운전자 1", "운전자 3", "엄마")))
        assertEquals("운전자 1", DriverRoster.nextDefaultName(listOf("아빠")))
        assertEquals("시연 운전자도 기본 이름", "운전자 1", app.daltokki.drive.domain.driver.DemoDriver.NAME)
    }

    @Test
    fun gamePicker_allowedByLevel() {
        assertEquals(GameKind.entries.toSet(), GamePicker.allowed(FatigueLevel.NORMAL).toSet())
        assertEquals(GameKind.entries.toSet(), GamePicker.allowed(FatigueLevel.CAUTION).toSet())
        assertEquals(setOf(GameKind.REACTION, GameKind.DIGITS), GamePicker.allowed(FatigueLevel.WARNING).toSet())
        assertTrue(GamePicker.allowed(FatigueLevel.DANGER).isEmpty())
    }
}
