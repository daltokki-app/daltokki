package app.daltokki.drive.platform

import android.content.Context
import app.daltokki.drive.data.nav.NoWaypointNavigator
import app.daltokki.drive.data.nav.WaypointNavigator
import app.daltokki.drive.data.vehicle.NoVehicleSignals
import app.daltokki.drive.data.vehicle.VehicleSignalsProvider
import app.daltokki.drive.voice.SpeechInput
import app.daltokki.drive.voice.SpeechOutput

/**
 * Pleos SDK 없이 빌드할 때(gradle.properties에서 pleosSdk=false) 쓰는 대체 구현.
 * Pleos Maven 저장소에 접속할 수 없거나 SDK 버전 문제로 빌드가 막힐 때 시연 빌드를 만들기 위한 안전장치입니다.
 * 실제 연동 코드는 app/src/pleos/java/.../platform/PleosBridge.kt 에 있습니다.
 */
object PleosBridge {
    const val INCLUDED = false

    @Suppress("UNUSED_PARAMETER")
    fun vehicleSignals(context: Context): VehicleSignalsProvider = NoVehicleSignals

    @Suppress("UNUSED_PARAMETER")
    fun navigator(context: Context): WaypointNavigator = NoWaypointNavigator

    @Suppress("UNUSED_PARAMETER")
    fun speechOutput(context: Context, fallback: () -> SpeechOutput): SpeechOutput = fallback()

    @Suppress("UNUSED_PARAMETER")
    fun speechInput(context: Context, fallback: () -> SpeechInput): SpeechInput = fallback()
}
