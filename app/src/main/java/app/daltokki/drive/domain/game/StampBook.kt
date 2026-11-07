package app.daltokki.drive.domain.game

import app.daltokki.drive.data.nav.RestStop
import java.util.Locale

/** 도장 한 개 (한 곳에 여러 번 가면 방문 횟수만 늘어남) */
data class StampRecord(
    val key: String,
    /** 말할 때 쓰는 이름 (예: "망향휴게소") */
    val name: String,
    val isShelter: Boolean,
    val route: String?,
    val visits: Int,
    val firstMs: Long,
    val lastMs: Long,
)

/** 도장을 찍은 결과 */
data class StampResult(
    val record: StampRecord,
    /** 처음 가 본 곳인지 */
    val isNew: Boolean,
    /** 서로 다른 도장 수 */
    val places: Int,
    /** 그중 졸음쉼터 수 */
    val shelters: Int,
    /** 이 노선에서 받은 도장 수 / 이 노선의 전체 쉼터 수 (노선을 모르면 0/0) */
    val routeHave: Int,
    val routeTotal: Int,
    /** 한 노선에서 받은 최대 도장 수 (배지용) */
    val bestRoute: Int,
    /** 대표 음식 (달토끼가 한마디) */
    val food: String?,
)

/** 노선별 진행 (화면 표시용) */
data class RouteProgress(val route: String, val have: Int, val total: Int)

/**
 * 휴게소 도장 깨기.
 *
 * 실제 휴게소·졸음쉼터에서 10분 쉬면 그곳 도장을 받습니다. 게임의 목표가 "쉬기"라서
 * 재미와 안전이 같은 방향을 향합니다(오래 운전할수록 이득이 되는 구조가 아님).
 * 공공데이터(휴게소 210곳 + 졸음쉼터 253곳)를 그대로 도장판으로 씁니다.
 *
 * 안드로이드에 의존하지 않는 순수 Kotlin. 저장은 [serialize]/[parse]의 글(SharedPreferences).
 */
class StampBook(
    records: List<StampRecord> = emptyList(),
    /** 노선별 전체 쉼터 수 (RestAreaDatabase.routeTotals) */
    private val routeTotals: () -> Map<String, Int> = { emptyMap() },
) {
    private val byKey = LinkedHashMap<String, StampRecord>().apply { records.forEach { put(it.key, it) } }

    val records: List<StampRecord> get() = byKey.values.toList()
    val places: Int get() = byKey.size
    val shelters: Int get() = byKey.values.count { it.isShelter }

    fun has(stop: RestStop): Boolean = keyOf(stop) in byKey

    /** 쉼 완료 → 도장 */
    fun stamp(stop: RestStop, timeMs: Long): StampResult {
        val key = keyOf(stop)
        val old = byKey[key]
        val rec = if (old == null) {
            StampRecord(key, stop.spokenName, stop.isShelter, stop.route, 1, timeMs, timeMs)
        } else {
            old.copy(visits = old.visits + 1, lastMs = timeMs)
        }
        byKey[key] = rec
        val routeHave = rec.route?.let { r -> byKey.values.count { it.route == r } } ?: 0
        val routeTotal = rec.route?.let { r -> maxOf(routeTotals()[r] ?: 0, routeHave) } ?: 0
        return StampResult(
            record = rec,
            isNew = old == null,
            places = places,
            shelters = shelters,
            routeHave = routeHave,
            routeTotal = routeTotal,
            bestRoute = bestRoute(),
            food = stop.food,
        )
    }

    /** 한 노선에서 받은 최대 도장 수 */
    fun bestRoute(): Int = byKey.values.mapNotNull { it.route }.groupingBy { it }.eachCount().values.maxOrNull() ?: 0

    /** 도장이 있는 노선들 (많이 받은 순) */
    fun routeProgress(): List<RouteProgress> {
        val totals = routeTotals()
        return byKey.values.mapNotNull { it.route }.groupingBy { it }.eachCount()
            .map { (route, have) -> RouteProgress(route, have, maxOf(totals[route] ?: 0, have)) }
            .sortedWith(compareByDescending<RouteProgress> { it.have }.thenBy { it.route })
    }

    /** 최근 도장 (최근 방문 순) */
    fun recent(limit: Int = 6): List<StampRecord> = byKey.values.sortedByDescending { it.lastMs }.take(limit)

    /** 한 줄에 도장 하나, 칸은 탭으로 구분 */
    fun serialize(): String = byKey.values.joinToString("\n") { r ->
        listOf(r.key, r.name, if (r.isShelter) "1" else "0", r.route.orEmpty(), r.visits, r.firstMs, r.lastMs)
            .joinToString("\t") { it.toString().replace('\t', ' ').replace('\n', ' ') }
    }

    companion object {
        /** 같은 곳 판별: 원본 이름 + 좌표(소수 셋째 자리 ≈ 100m). 지역 설정과 무관하게 점(.)으로 씀 */
        fun keyOf(stop: RestStop): String =
            "${stop.name}|${String.format(Locale.ROOT, "%.3f", stop.latitude)}|${String.format(Locale.ROOT, "%.3f", stop.longitude)}"

        fun parse(text: String?): List<StampRecord> = text.orEmpty().lineSequence().mapNotNull { line ->
            val c = line.split('\t')
            if (c.size < 7) return@mapNotNull null
            StampRecord(
                key = c[0],
                name = c[1],
                isShelter = c[2] == "1",
                route = c[3].takeIf { it.isNotEmpty() },
                visits = c[4].toIntOrNull() ?: return@mapNotNull null,
                firstMs = c[5].toLongOrNull() ?: 0L,
                lastMs = c[6].toLongOrNull() ?: 0L,
            )
        }.toList()
    }
}
