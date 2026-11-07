package app.daltokki.drive.data

import app.daltokki.drive.data.nav.RestAreaDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.charset.Charset

/** 공공데이터(전국휴게소정보표준데이터) 원본 파일 읽기와 진행 방향·차로 판별 */
class RestAreaDataTest {

    private fun assetBytes(name: String): ByteArray {
        val candidates = listOf(File("src/main/assets/$name"), File("app/src/main/assets/$name"))
        return candidates.first { it.exists() }.readBytes()
    }

    private val db by lazy {
        RestAreaDatabase.parse(RestAreaDatabase.decode(assetBytes("rest_areas/service_areas.csv")), "휴게소")
    }

    @Test
    fun officialFile_readsEveryRow() {
        assertEquals("전국휴게소정보표준데이터 2026-01-26 기준 210곳", 210, db.size)
        val anseong = db.stops.first { it.name == "안성(부산)" }
        assertEquals("부산", anseong.destination)
        assertEquals("안성휴게소", anseong.spokenName)
        val both = db.stops.first { it.name == "옥천만남" }
        assertNull("양방향 휴게소는 행선지 없음", both.destination)
    }

    @Test
    fun picksRestAreaOnOurSideOfTheRoad() {
        // 경부선 안성 근처. 남쪽(부산 방향)으로 달리면 더 가까운 '안성(서울)'이 아니라 '안성(부산)'
        val south = db.nearestAhead(37.10, 127.12, headingDeg = 180f)!!
        assertEquals("안성(부산)", south.first.name)
        // 북쪽(서울 방향)으로 달리면 '안성(서울)'
        val north = db.nearestAhead(36.95, 127.17, headingDeg = 0f)!!
        assertEquals("안성(서울)", north.first.name)
    }

    @Test
    fun csvAndEncodingHelpers() {
        assertEquals(listOf("가", "나,다", "라"), RestAreaDatabase.splitCsvLine("가,\"나,다\",라"))
        val cp949 = "휴게소명,위도,경도".toByteArray(Charset.forName("EUC-KR"))
        assertEquals("공공데이터포털 기본 인코딩(CP949)도 읽음", "휴게소명,위도,경도", RestAreaDatabase.decode(cp949))
        assertEquals("부산", RestAreaDatabase.destinationOf("○○졸음쉼터", "부산방향"))
        assertNull(RestAreaDatabase.destinationOf("○○졸음쉼터", "하행"))
        assertNull(RestAreaDatabase.destinationOf("하남드림", "양방향"))
    }

    @Test
    fun drowsyShelterStandardFormat_isReadable() {
        // 전국졸음쉼터표준데이터 머리글 형식(내려받아 넣으면 자동으로 읽힘)
        val text = """
            졸음쉼터명,도로종류,도로노선번호,도로노선명,도로노선방향,위도,경도,주차면수
            테스트졸음쉼터,고속국도,1,경부선,부산방향,36.50,127.40,20
        """.trimIndent()
        val shelters = RestAreaDatabase.parse(text, "졸음쉼터")
        assertEquals(1, shelters.size)
        assertEquals("테스트졸음쉼터", shelters.stops[0].spokenName)
        assertEquals("부산", shelters.stops[0].destination)
        assertTrue(RestAreaDatabase.merge(db, shelters).size == 211)
    }

    private val shelters by lazy {
        RestAreaDatabase.parse(RestAreaDatabase.decode(assetBytes("rest_areas/drowsy_shelters.csv")), "졸음쉼터")
    }

    @Test
    fun drowsyShelterOfficialFile_readsEveryRow() {
        assertEquals("전국졸음쉼터표준데이터 2026-02-25 기준 253곳", 253, shelters.size)
        val ochang = shelters.stops.first { it.name == "오창" }
        assertEquals("오창졸음쉼터", ochang.spokenName)
        assertEquals("통영기점 + 하남종점 → 하남 방향 차로", "하남", ochang.destination)
        assertEquals("하남", RestAreaDatabase.destinationOf("오창", "통영기점 + 하남종점"))
        assertEquals("광주", RestAreaDatabase.destinationOf("남상", "대구기점 + 광주광역시종점"))
        assertEquals("오타 보정", "양양", RestAreaDatabase.destinationOf("x", "서울기점 + 양앙종점"))
    }

    @Test
    fun picksShelterOnOurSideOfTheRoad() {
        // 경부선 옥천 졸음쉼터: 남동쪽(부산 방향)으로 가면 부산 방향 차로, 북서쪽(서울 방향)으로 가면 서울 방향 차로
        val southEast = shelters.nearestAhead(36.36, 127.50, headingDeg = 135f)!!.first
        assertEquals("옥천", southEast.name)
        assertEquals("부산", southEast.destination)
        val northWest = shelters.nearestAhead(36.30, 127.58, headingDeg = 315f)!!.first
        assertEquals("옥천", northWest.name)
        assertEquals("서울", northWest.destination)
    }
}
