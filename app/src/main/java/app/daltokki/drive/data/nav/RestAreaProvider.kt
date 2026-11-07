package app.daltokki.drive.data.nav

import android.util.Log
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 휴게소·졸음쉼터 한 곳.
 * @param distanceKm 안내용 거리(km). 실차에서는 직선거리 × 1.2(도로 보정)입니다.
 */
data class RestArea(
    val name: String,
    val distanceKm: Int,
    val latitude: Double? = null,
    val longitude: Double? = null,
    /** 대표 음식 (공공데이터). 졸음쉼터·모르면 null */
    val food: String? = null,
)

/** 휴게소 찾기와 경유지 추가 */
interface RestAreaProvider {
    suspend fun nearestRestArea(): RestArea?
    suspend fun addWaypoint(restArea: RestArea): Boolean
    fun start() = Unit
    fun stop() = Unit
}

/** 시뮬레이션용: 시나리오에 정해 둔 휴게소를 돌려줍니다. */
class SimRestAreaProvider(private val restArea: RestArea) : RestAreaProvider {
    override suspend fun nearestRestArea(): RestArea = restArea
    override suspend fun addWaypoint(restArea: RestArea): Boolean {
        Log.i("SimRestArea", "경유지 추가(시뮬레이션): ${restArea.name}")
        return true
    }
}

/** 경유지 추가 (Pleos NaviHelper SDK). 구현은 `app/src/pleos/java/.../platform/PleosBridge.kt` */
interface WaypointNavigator {
    val status: String
    /** 성공하면 true. 내비 경로가 없거나 오류면 false. */
    suspend fun addWaypoint(latitude: Double, longitude: Double, name: String): Boolean
    fun release()
}

/** NaviHelper가 없는 빌드(pleosSdk=false) */
object NoWaypointNavigator : WaypointNavigator {
    override val status: String = "NaviHelper 없음"
    override suspend fun addWaypoint(latitude: Double, longitude: Double, name: String): Boolean = false
    override fun release() = Unit
}

/**
 * 실차용: 현재 위치(LocationManager) + 공공데이터 휴게소·졸음쉼터 좌표(assets/rest_areas/)로
 * 진행 방향 앞쪽, 같은 쪽 차로의 가장 가까운 곳을 찾고, NaviHelper로 경유지에 추가합니다.
 * (NaviHelper에는 휴게소 검색 기능이 없어 좌표 데이터를 앱에 넣는 방식입니다)
 */
class VehicleRestAreaProvider(
    private val database: () -> RestAreaDatabase,
    private val location: LocationTracker,
    private val navigator: WaypointNavigator,
) : RestAreaProvider {

    override fun start() = location.start()

    override fun stop() {
        location.stop()
    }

    override suspend fun nearestRestArea(): RestArea? {
        val loc = location.last ?: return null
        val heading = if (loc.hasBearing() && loc.speed > 3f) loc.bearing else null
        val (stop, km) = database().nearestAhead(loc.latitude, loc.longitude, heading) ?: return null
        return RestArea(stop.spokenName, max(1, (km * ROAD_FACTOR).roundToInt()), stop.latitude, stop.longitude, stop.food)
    }

    override suspend fun addWaypoint(restArea: RestArea): Boolean {
        val lat = restArea.latitude ?: return false
        val lon = restArea.longitude ?: return false
        return try {
            navigator.addWaypoint(lat, lon, restArea.name)
        } catch (t: Throwable) {
            Log.w("VehicleRestArea", "경유지 추가 실패", t)
            false
        }
    }

    private companion object {
        /** 직선거리를 도로 거리로 어림하는 보정값 */
        const val ROAD_FACTOR = 1.2
    }
}
