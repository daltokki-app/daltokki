package app.daltokki.drive.domain

import app.daltokki.drive.data.nav.RestAreaDatabase
import app.daltokki.drive.data.nav.RestStop
import app.daltokki.drive.domain.dialog.UtteranceIntent
import app.daltokki.drive.domain.fatigue.FatigueConfig
import app.daltokki.drive.domain.fatigue.ResponseKind
import app.daltokki.drive.domain.fatigue.ResponseTracker
import app.daltokki.drive.domain.game.AwakeScore
import app.daltokki.drive.domain.game.Badges
import app.daltokki.drive.domain.game.StampBook
import app.daltokki.drive.domain.sim.Scenarios
import app.daltokki.drive.voice.SoundBank
import app.daltokki.drive.voice.SoundEffect
import app.daltokki.drive.voice.ToneSynth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/** 7차: 휴게소 도장 깨기 · 반응 게임 · 효과음 */
class StampAndReactionTest {

    private fun assetBytes(name: String): ByteArray {
        val candidates = listOf(File("src/main/assets/$name"), File("app/src/main/assets/$name"))
        return candidates.first { it.exists() }.readBytes()
    }

    private val db by lazy {
        RestAreaDatabase.merge(
            RestAreaDatabase.parse(RestAreaDatabase.decode(assetBytes("rest_areas/service_areas.csv")), "휴게소"),
            RestAreaDatabase.parse(RestAreaDatabase.decode(assetBytes("rest_areas/drowsy_shelters.csv")), "졸음쉼터"),
        )
    }

    private fun stop(name: String, route: String?, shelter: Boolean = false, lat: Double = 36.0, lon: Double = 127.0) =
        RestStop(name, if (shelter) "졸음쉼터" else "휴게소", lat, lon, route = route)

    @Test
    fun publicData_hasRouteAndFood_andScenarioStopsExist() {
        val mangHyang = db.findByName("망향(부산)")!!
        assertEquals("경부선", mangHyang.route)
        assertNotNull("휴게소 대표 음식", mangHyang.food)
        assertFalse(mangHyang.isShelter)
        assertTrue("졸음쉼터 데이터에도 노선", db.stops.filter { it.isShelter }.all { it.route != null })
        assertTrue("경부선 쉼터가 가장 많음", (db.routeTotals["경부선"] ?: 0) >= 60)
        // 시연 시나리오의 휴게소는 모두 실제 공공데이터에 있어야 함 (도장 시연)
        Scenarios.all.forEach { sc -> assertNotNull(sc.title, db.findByName(sc.restStopId!!)) }
        // 정차한 곳 700m 안의 쉼터 찾기
        assertEquals(mangHyang, db.nearestWithin(mangHyang.latitude + 0.002, mangHyang.longitude))
        assertNull(db.nearestWithin(37.5665, 126.9780)) // 서울 시청 근처에는 없음
    }

    @Test
    fun stampBook_newThenRevisit_routeProgress_andSaveLoad() {
        val totals = mapOf("경부선" to 65, "중부선" to 21)
        val book = StampBook(routeTotals = { totals })
        val r1 = book.stamp(stop("망향(부산)", "경부선"), 1_000L)
        assertTrue(r1.isNew)
        assertEquals(1, r1.places)
        assertEquals(1, r1.routeHave)
        assertEquals(65, r1.routeTotal)
        assertEquals("망향휴게소", r1.record.name)

        val again = book.stamp(stop("망향(부산)", "경부선"), 2_000L)
        assertFalse("다시 가면 새 도장 아님", again.isNew)
        assertEquals(2, again.record.visits)
        assertEquals(1, again.places)

        book.stamp(stop("오창", "중부선", shelter = true, lat = 36.73), 3_000L)
        assertEquals(1, book.shelters)
        assertEquals(listOf("경부선", "중부선"), book.routeProgress().map { it.route })
        assertEquals("오창졸음쉼터", book.recent(1).first().name)

        // 저장 → 불러오기
        val loaded = StampBook(StampBook.parse(book.serialize()), routeTotals = { totals })
        assertEquals(book.records, loaded.records)
        assertTrue(StampBook.parse("깨진 줄").isEmpty())
    }

    @Test
    fun stampKey_doesNotDependOnLocale() {
        val old = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY) // 소수점이 쉼표인 지역
            assertEquals("망향(부산)|36.856|127.181", StampBook.keyOf(stop("망향(부산)", "경부선", lat = 36.8556501, lon = 127.180929)))
        } finally {
            Locale.setDefault(old)
        }
    }

    @Test
    fun stampBadges_andPoints() {
        val s = AwakeScore()
        val first = s.onStamp(isNew = true, places = 1, shelters = 0, bestRoute = 1)
        assertEquals(AwakeScore.NEW_STAMP_POINTS, first.points)
        assertTrue(first.newBadges.contains(Badges.FIRST_STAMP))
        val revisit = s.onStamp(isNew = false, places = 1, shelters = 0, bestRoute = 1)
        assertEquals("다시 간 곳은 도장 점수 없음(휴식 +500만)", 0, revisit.points)
        val more = s.onStamp(isNew = true, places = 10, shelters = 3, bestRoute = 5)
        assertTrue(more.newBadges.containsAll(listOf(Badges.STAMP_10, Badges.SHELTER_3, Badges.ROUTE_5)))
    }

    @Test
    fun reaction_isJudgedAgainstItsOwnBaseline() {
        val cfg = FatigueConfig()
        val t = ResponseTracker(cfg)
        assertTrue(t.isFast(1_000L, ResponseKind.REACTION))
        assertFalse("짧은 질문 기준으로는 빠른 1.5초도 반응 게임에서는 느림", t.isFast(1_500L, ResponseKind.REACTION))
        t.recordResponse(0L, 3_500L, ResponseKind.REACTION)
        assertTrue("느린 반응은 대화 반응 저하로 반영", t.impairment(0L)!!.first > 0.5f)
        val miss = ResponseTracker(cfg).also { it.recordMiss(0L, ResponseKind.REACTION) }
        assertEquals(cfg.reactionMissImpairment, miss.impairment(0L)!!.first, 0.001f)
        assertFalse("반응 게임은 개인 기준(짧은 질문)에 들어가지 않음", t.hasPersonalBaseline)

        val s = AwakeScore()
        s.startTrip()
        repeat(2) { s.onCorrect(fast = true, isWordGame = false, isReaction = true) }
        assertFalse(s.isEarned(Badges.LIGHTNING))
        val third = s.onCorrect(fast = true, isWordGame = false, isReaction = true)
        assertTrue("한 운행에서 빠른 반응 3번 → 번개 반응", third.newBadges.contains(Badges.LIGHTNING))
        assertEquals(UtteranceIntent.REACTION_GAME, UtteranceIntent.parse("반응 게임 하자"))
    }

    @Test
    fun soundEffects_areShortAndDoNotClip() {
        SoundEffect.entries.forEach { e ->
            val ms = SoundBank.durationMs(e, step = 7)
            assertTrue("$e 는 0.7초 안", ms in 100..700)
            val pcm = ToneSynth.render(SoundBank.tones(e, 7))
            assertEquals(ms * ToneSynth.SAMPLE_RATE / 1000.0, pcm.size.toDouble(), ToneSynth.SAMPLE_RATE * 0.01)
            val peak = pcm.maxOf { kotlin.math.abs(it.toInt()) }
            assertTrue("$e 소리 크기 적당(잘림 없음)", peak in 1_000..(Short.MAX_VALUE * 0.5).toInt())
        }
        // 콤보가 오르면 정답음이 높아짐
        assertTrue(SoundBank.tones(SoundEffect.CORRECT, 3).first().freqHz > SoundBank.tones(SoundEffect.CORRECT, 0).first().freqHz)
    }
}
