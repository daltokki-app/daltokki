package app.daltokki.drive.data.vehicle

import app.daltokki.drive.domain.model.VehicleSnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * 차량 데이터 공급원. 시뮬레이션과 실차를 같은 인터페이스로 다룹니다.
 * 실차 권한이나 SDK가 막혀도 시뮬레이션으로 시연할 수 있게 하는 핵심 장치입니다.
 */
interface VehicleDataSource {
    val name: String
    /** 사람이 읽을 상태 메시지 (예: "Car API 연결됨", "권한 필요") */
    val status: StateFlow<String>
    fun snapshots(): Flow<VehicleSnapshot>
}
