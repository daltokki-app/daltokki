package app.daltokki.drive.data.nav

import java.nio.charset.Charset
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 휴게소·졸음쉼터 한 곳.
 * @param destination 이 쉼터가 있는 차로의 행선지(예: "안성(부산)" → "부산"). 양방향이거나 모르면 null.
 * @param route 도로 노선 이름(예: "경부선"). 도장 깨기의 노선별 진행에 씁니다.
 * @param food 휴게소 대표 음식(공공데이터 '휴게소대표음식명'). 졸음쉼터는 없음.
 */
data class RestStop(
    val name: String,
    val type: String,
    val latitude: Double,
    val longitude: Double,
    val destination: String? = null,
    val route: String? = null,
    val food: String? = null,
) {
    /** 졸음쉼터인지 (도장 깨기의 '쉼터 탐험가' 배지용) */
    val isShelter: Boolean get() = type.contains("졸음")

    /** 말할 때 쓰는 이름: "안성(부산)" → "안성휴게소", "○○졸음쉼터" → 그대로 */
    val spokenName: String
        get() {
            val base = name.substringBefore('(').trim()
            val suffix = if (type.contains("졸음")) "졸음쉼터" else "휴게소"
            return if (base.endsWith("휴게소") || base.endsWith("쉼터")) base else base + suffix
        }
}

/**
 * 휴게소·졸음쉼터 좌표 목록과 "진행 방향 앞쪽, 같은 쪽 차로에서 가장 가까운 곳" 찾기.
 * 안드로이드에 의존하지 않는 순수 Kotlin이라 단위 테스트로 검증합니다.
 *
 * 읽을 수 있는 파일 (첫 줄 머리글로 열을 찾음)
 * - 공공데이터포털 표준데이터 원본: 전국휴게소정보표준데이터(휴게소명·도로노선방향·위도·경도…),
 *   전국졸음쉼터표준데이터(졸음쉼터명·도로노선방향 "A기점 + B종점"·위도·경도…). UTF-8·CP949(EUC-KR) 모두 가능
 * - 직접 만든 파일: 이름,종류,위도,경도
 *
 * 방향 판별: 공공데이터의 휴게소 이름에는 그 차로의 행선지가 괄호로 붙어 있고(예: "안성(부산)"),
 * 졸음쉼터는 방향 열에 "통영기점 + 하남종점"처럼 종점(행선지)이 있습니다.
 * 행선지 도시가 지금 진행 방향 쪽(±90°)에 있을 때만 고르므로, 맞은편 차로 휴게소를 권하지 않습니다.
 */
class RestAreaDatabase(val stops: List<RestStop>) {

    val size: Int get() = stops.size

    /** 노선별 쉼터 수 (도장 깨기 진행률의 분모) */
    val routeTotals: Map<String, Int> by lazy {
        stops.mapNotNull { it.route }.groupingBy { it }.eachCount()
    }

    /**
     * 지금 위치에서 [withinKm] 안에 있는 가장 가까운 쉼터 (방향 무시).
     * 정차해서 쉬는 곳이 휴게소·졸음쉼터인지 확인할 때 씁니다.
     */
    fun nearestWithin(latitude: Double, longitude: Double, withinKm: Double = 0.7): RestStop? =
        stops.map { it to distanceKm(latitude, longitude, it.latitude, it.longitude) }
            .filter { it.second <= withinKm }
            .minByOrNull { it.second }
            ?.first

    /** 이름으로 찾기 (시뮬레이션 시나리오의 휴게소) */
    fun findByName(name: String): RestStop? = stops.firstOrNull { it.name == name }

    /**
     * @param headingDeg 진행 방향(0=북, 시계 방향). null이면 방향 무시.
     * @return (가장 가까운 곳, 직선거리 km). 범위 안에 없으면 null.
     */
    fun nearestAhead(
        latitude: Double,
        longitude: Double,
        headingDeg: Float?,
        maxKm: Double = 80.0,
        aheadToleranceDeg: Double = 60.0,
    ): Pair<RestStop, Double>? {
        val withDistance = stops.map { it to distanceKm(latitude, longitude, it.latitude, it.longitude) }
            .filter { it.second <= maxKm }
        if (withDistance.isEmpty()) return null
        if (headingDeg != null) {
            val heading = headingDeg.toDouble()
            val ahead = withDistance.filter { (s, d) ->
                val inFront = d < 0.3 || angleDiff(bearingDeg(latitude, longitude, s.latitude, s.longitude), heading) <= aheadToleranceDeg
                inFront && servesDirection(s, latitude, longitude, heading)
            }
            ahead.minByOrNull { it.second }?.let { return it }
        }
        return withDistance.minByOrNull { it.second }
    }

    /** 이 쉼터가 지금 가는 방향의 차로에 있는지 (행선지 도시가 진행 방향 ±90° 안에 있으면 같은 방향) */
    private fun servesDirection(s: RestStop, lat: Double, lon: Double, heading: Double): Boolean {
        val dest = s.destination ?: return true
        val city = CITY_COORDS[dest] ?: return true // 모르는 행선지는 거르지 않음
        // 행선지 근처(15km 안)에서는 방위가 불안정하므로 거르지 않음
        if (distanceKm(lat, lon, city.first, city.second) < 15.0) return true
        return angleDiff(bearingDeg(lat, lon, city.first, city.second), heading) <= 90.0
    }

    companion object {
        private const val EARTH_KM = 6371.0

        /**
         * 공공데이터 휴게소 이름의 괄호 안 행선지 → 대략의 도시 좌표(위도, 경도).
         * 방향 판별(±90°)에만 쓰므로 시청 근처 수준의 대략 좌표면 충분합니다.
         */
        val CITY_COORDS: Map<String, Pair<Double, Double>> = mapOf(
            "서울" to (37.57 to 126.98), "부산" to (35.18 to 129.08), "서부산" to (35.15 to 128.98),
            "인천" to (37.46 to 126.71), "대전" to (36.35 to 127.38), "대구" to (35.87 to 128.60),
            "광주" to (35.16 to 126.85), "울산" to (35.54 to 129.31), "순천" to (34.95 to 127.49),
            "목포" to (34.81 to 126.39), "창원" to (35.23 to 128.68), "양평" to (37.49 to 127.49),
            "춘천" to (37.88 to 127.73), "강릉" to (37.75 to 128.88), "천안" to (36.82 to 127.11),
            "통영" to (34.85 to 128.43), "영덕" to (36.42 to 129.37), "청주" to (36.64 to 127.49),
            "완주" to (35.90 to 127.16), "평택" to (36.99 to 127.11), "제천" to (37.13 to 128.19),
            "하남" to (37.54 to 127.21), "남이" to (36.57 to 127.43), "포항" to (36.02 to 129.34),
            "함양" to (35.52 to 127.73), "속초" to (38.21 to 128.59), "당진" to (36.89 to 126.63),
            "삼척" to (37.45 to 129.17), "양양" to (38.08 to 128.62), "무안" to (34.99 to 126.48),
            "장수" to (35.65 to 127.52), "익산" to (35.95 to 126.96), "서천" to (36.08 to 126.69),
            "공주" to (36.45 to 127.12), "영암" to (34.80 to 126.70), "논산" to (36.19 to 127.10),
            "일산" to (37.66 to 126.77), "여주" to (37.30 to 127.64), "원주" to (37.34 to 127.92),
            "전주" to (35.82 to 127.15), "진주" to (35.18 to 128.11), "김해" to (35.23 to 128.89),
            "경주" to (35.86 to 129.22), "안동" to (36.57 to 128.73), "세종" to (36.48 to 127.29),
            // 졸음쉼터 표준데이터의 종점(분기점·나들목 이름 포함)
            "판교" to (37.39 to 127.11), "회덕" to (36.39 to 127.42), "고창" to (35.43 to 126.70),
            "담양" to (35.32 to 126.99), "달서" to (35.83 to 128.53), "동대구" to (35.88 to 128.63),
            "구리" to (37.59 to 127.13), "언양" to (35.56 to 129.12), "기장" to (35.24 to 129.22),
            "새만금" to (35.80 to 126.55), "포천" to (37.89 to 127.20), "산인" to (35.31 to 128.47),
            "산내" to (36.27 to 127.46), "퇴계원" to (37.65 to 127.14), "서대전" to (36.32 to 127.37),
            "파주" to (37.76 to 126.78), "양주" to (37.79 to 127.05),
        )

        /** 원본 데이터의 표기 차이·오타 보정 */
        private val DESTINATION_ALIASES = mapOf("양앙" to "양양", "회턱" to "회덕")

        private val NAME_HEADERS = listOf("휴게소명", "졸음쉼터명", "쉼터명", "시설명", "이름", "명칭")
        private val TYPE_HEADERS = listOf("휴게소종류", "졸음쉼터종류", "종류")
        private val DIRECTION_HEADERS = listOf("도로노선방향", "방향")
        private val ROUTE_HEADERS = listOf("도로노선명", "노선명", "노선")
        private val FOOD_HEADERS = listOf("휴게소대표음식명", "대표음식명", "대표음식")

        /** 두 원본에서 같은 노선을 다르게 부르는 경우 하나로 (도장 노선별 진행용) */
        private val ROUTE_ALIASES = mapOf("대전통영선" to "통영대전선")

        /**
         * CSV 글 읽기.
         * @param defaultType 종류 열이 없을 때 쓸 종류(예: "졸음쉼터")
         */
        fun parse(text: String, defaultType: String = "휴게소"): RestAreaDatabase {
            val lines = text.removePrefix("﻿").lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .toList()
            if (lines.isEmpty()) return RestAreaDatabase(emptyList())
            val header = splitCsvLine(lines.first())
            val latCol = header.indexOfFirst { it.contains("위도") }
            val lonCol = header.indexOfFirst { it.contains("경도") }
            if (latCol < 0 || lonCol < 0) return RestAreaDatabase(emptyList())
            val nameCol = NAME_HEADERS.firstNotNullOfOrNull { h -> header.indexOf(h).takeIf { it >= 0 } } ?: 0
            val typeCol = TYPE_HEADERS.firstNotNullOfOrNull { h -> header.indexOf(h).takeIf { it >= 0 } }
            val dirCol = DIRECTION_HEADERS.firstNotNullOfOrNull { h -> header.indexOf(h).takeIf { it >= 0 } }
            val routeCol = ROUTE_HEADERS.firstNotNullOfOrNull { h -> header.indexOf(h).takeIf { it >= 0 } }
            val foodCol = FOOD_HEADERS.firstNotNullOfOrNull { h -> header.indexOf(h).takeIf { it >= 0 } }

            val stops = lines.drop(1).mapNotNull { line ->
                val cols = splitCsvLine(line)
                val lat = cols.getOrNull(latCol)?.toDoubleOrNull() ?: return@mapNotNull null
                val lon = cols.getOrNull(lonCol)?.toDoubleOrNull() ?: return@mapNotNull null
                if (lat !in 33.0..39.0 || lon !in 124.0..132.0) return@mapNotNull null // 대한민국 범위 밖
                val name = cols.getOrNull(nameCol)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val type = typeCol?.let { cols.getOrNull(it) }?.takeIf { it.isNotBlank() } ?: defaultType
                val direction = dirCol?.let { cols.getOrNull(it) }.orEmpty()
                val route = routeCol?.let { cols.getOrNull(it) }?.trim()?.takeIf { it.isNotEmpty() }
                    ?.let { ROUTE_ALIASES[it] ?: it }
                val food = foodCol?.let { cols.getOrNull(it) }?.trim()?.takeIf { it.isNotEmpty() }
                RestStop(name, type, lat, lon, destinationOf(name, direction), route, food)
            }
            return RestAreaDatabase(stops)
        }

        /** 여러 파일을 합침 (같은 이름·같은 위치는 하나로) */
        fun merge(vararg dbs: RestAreaDatabase): RestAreaDatabase =
            RestAreaDatabase(
                dbs.flatMap { it.stops }.distinctBy { "${it.name}|${"%.4f".format(it.latitude)}|${"%.4f".format(it.longitude)}" },
            )

        /**
         * 행선지 찾기. 양방향·통합이면 null
         * - 휴게소 표준데이터: 이름 끝 괄호 "안성(부산)" → 부산
         * - 졸음쉼터 표준데이터: 방향 열 "통영기점 + 하남종점" → 하남 (기점에서 종점 쪽으로 가는 차로)
         * - 그 밖: 방향 열 "부산방향" → 부산
         */
        internal fun destinationOf(name: String, direction: String): String? {
            if (direction.contains("양방향")) return null
            val fromName = Regex("""\(([^)]*)\)\s*$""").find(name)?.groupValues?.get(1)?.trim()
            val fromEnd = Regex("""([^\s+]+)종점""").find(direction)?.groupValues?.get(1)?.trim()
            val candidate = fromName?.takeIf { it.isNotEmpty() }
                ?: fromEnd?.takeIf { it.isNotEmpty() }
                ?: direction.trim().removeSuffix("방향").trim()
                    .takeIf { it.isNotEmpty() && it != "상행" && it != "하행" && !it.contains("기점") }
            val normalized = candidate?.removeSuffix("광역시")?.removeSuffix("특별시")?.let { DESTINATION_ALIASES[it] ?: it }
            return normalized?.takeIf { it != "통합" && it != "양방향" }
        }

        /** 따옴표로 묶인 칸(쉼표 포함)을 처리하는 CSV 한 줄 나누기 */
        internal fun splitCsvLine(line: String): List<String> {
            val out = ArrayList<String>()
            val sb = StringBuilder()
            var quoted = false
            var i = 0
            while (i < line.length) {
                val c = line[i]
                when {
                    c == '"' && quoted && i + 1 < line.length && line[i + 1] == '"' -> { sb.append('"'); i++ }
                    c == '"' -> quoted = !quoted
                    c == ',' && !quoted -> { out += sb.toString().trim(); sb.setLength(0) }
                    else -> sb.append(c)
                }
                i++
            }
            out += sb.toString().trim()
            return out
        }

        /** 파일 바이트를 글자로: UTF-8이 아니면 공공데이터포털 기본 인코딩(CP949/EUC-KR)으로 읽음 */
        fun decode(bytes: ByteArray): String {
            val utf8 = String(bytes, Charsets.UTF_8)
            if (!utf8.contains('�')) return utf8
            val cp949 = runCatching { Charset.forName("x-windows-949") }.getOrNull()
                ?: runCatching { Charset.forName("EUC-KR") }.getOrNull()
                ?: return utf8
            return String(bytes, cp949)
        }

        fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
            return 2 * EARTH_KM * atan2(sqrt(a), sqrt(1 - a))
        }

        /** 1 → 2 방향(0=북, 시계 방향, 0~360) */
        fun bearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val p1 = Math.toRadians(lat1)
            val p2 = Math.toRadians(lat2)
            val dLon = Math.toRadians(lon2 - lon1)
            val y = sin(dLon) * cos(p2)
            val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dLon)
            return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
        }

        fun angleDiff(a: Double, b: Double): Double {
            val d = abs(a - b) % 360.0
            return if (d > 180.0) 360.0 - d else d
        }
    }
}
