package app.daltokki.drive

import android.app.Application
import android.content.Context
import app.daltokki.drive.ai.LlmClient
import app.daltokki.drive.ai.ScriptedLlmClient
import app.daltokki.drive.data.nav.LocationTracker
import app.daltokki.drive.data.nav.RestAreaDatabase
import app.daltokki.drive.data.nav.WaypointNavigator
import app.daltokki.drive.data.vehicle.CarConnection
import app.daltokki.drive.data.vehicle.VehicleSignalsProvider
import app.daltokki.drive.domain.game.QuizBank
import app.daltokki.drive.domain.game.WordDictionary
import app.daltokki.drive.engine.CopilotEngine
import app.daltokki.drive.platform.PleosBridge
import app.daltokki.drive.voice.AndroidAudioEnvironment
import app.daltokki.drive.voice.AndroidSoundEffects
import app.daltokki.drive.voice.AudioEnvironment
import app.daltokki.drive.voice.AndroidSttInput
import app.daltokki.drive.voice.AndroidTtsOutput
import app.daltokki.drive.voice.SpeechInput
import app.daltokki.drive.voice.SoundEffects
import app.daltokki.drive.voice.SpeechOutput

class LunaApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

/**
 * 의존성 모음. Pleos SDK 제공 상태가 바뀌면 여기서 구현체만 바꾸면 됩니다.
 *
 * Pleos SDK 연동 현황
 * - Vehicle State 2.3.2, NaviHelper 3.0.5: 연동 (PleosBridge)
 * - TextToSpeech·SpeechToText 3.3.1: 온디바이스 모드로 연동 (앱 등록 불필요). 안 되면 안드로이드 기본 음성
 * - Gleo Interface: assets/schema.json + GleoActionReceiver (음성비서에서 끝말잇기·졸림 신고·휴게소 경유지)
 * - FusedLocation, Vehicle Control: 준비 중 → 안드로이드 위치 사용, 차량 제어는 하지 않음
 * - 대화형 LLM SDK: 참가자용 목록에 없음 → 규칙 기반 응답
 * 휴대폰에서는 Pleos 클래스를 아예 만들지 않고 안드로이드 기본 기능만 씁니다.
 */
class AppContainer(private val context: Context) {

    /** 차량이면 실행 중인 기기 */
    val isAutomotive: Boolean = CarConnection.isAutomotive(context)

    /** 음성 합성: 차량은 Pleos TTS(온디바이스) → 안 되면 안드로이드 기본, 휴대폰은 안드로이드 기본 */
    val speechOutput: SpeechOutput by lazy {
        if (isAutomotive) PleosBridge.speechOutput(context) { AndroidTtsOutput(context) } else AndroidTtsOutput(context)
    }

    /**
     * 자동 시연의 운전자 목소리: 안드로이드 기본 TTS의 한국어 남성 음성(없으면 음높이를 낮춤).
     * 달토끼 목소리(Pleos TTS)와 구분됩니다. 실제 사용에는 쓰지 않습니다.
     */
    val driverSpeechOutput: SpeechOutput by lazy { AndroidTtsOutput(context, maleVoice = true) }

    /** 음성 인식: 차량은 Pleos STT(온디바이스) → 안 되면 안드로이드 기본, 휴대폰은 안드로이드 기본 */
    val speechInput: SpeechInput by lazy {
        if (isAutomotive) PleosBridge.speechInput(context) { AndroidSttInput(context) } else AndroidSttInput(context)
    }

    /** 게임 효과음 (정답·콤보·배지·도장·반응 게임 신호). 코드로 합성하므로 소리 파일이 없습니다. */
    val soundEffects: SoundEffects by lazy { AndroidSoundEffects() }

    /** 달토끼가 말하는 동안 음악 소리 줄이기(오디오 포커스), 통화 중인지 확인 */
    val audioEnvironment: AudioEnvironment by lazy { AndroidAudioEnvironment(context) }

    /** 자유 대화용 짧은 반응. 끝말잇기 판정·퀴즈 채점·경고는 LLM 없이 코드로 합니다. */
    val llm: LlmClient by lazy { ScriptedLlmClient() }

    /** Pleos SDK가 빌드에 들어 있는지 (gradle.properties의 pleosSdk) */
    val pleosIncluded: Boolean = PleosBridge.INCLUDED

    /** Pleos Vehicle SDK 신호 (핸들 각도·방향지시등·경고등·내기순환) */
    val vehicleSignals: VehicleSignalsProvider by lazy { PleosBridge.vehicleSignals(context) }

    /** Pleos NaviHelper 경유지 추가 */
    val navigator: WaypointNavigator by lazy { PleosBridge.navigator(context) }

    val locationTracker: LocationTracker by lazy { LocationTracker(context) }

    /**
     * 휴게소·졸음쉼터 좌표 (공공데이터포털 표준데이터 원본 + 직접 추가한 파일).
     * - assets/rest_areas/service_areas.csv  : 전국휴게소정보표준데이터 (한국도로공사, 2026-01-26 기준 210곳)
     * - assets/rest_areas/drowsy_shelters.csv: 전국졸음쉼터표준데이터 (한국도로공사, 2026-02-25 기준 253곳)
     * - assets/rest_areas_ko.csv             : 직접 추가 (이름,종류,위도,경도)
     */
    val restAreaDatabase: RestAreaDatabase by lazy {
        fun load(path: String, type: String): RestAreaDatabase = runCatching {
            RestAreaDatabase.parse(RestAreaDatabase.decode(context.assets.open(path).use { it.readBytes() }), type)
        }.getOrElse { RestAreaDatabase(emptyList()) }
        RestAreaDatabase.merge(
            load("rest_areas/service_areas.csv", "휴게소"),
            load("rest_areas/drowsy_shelters.csv", "졸음쉼터"),
            load("rest_areas_ko.csv", "휴게소"),
        )
    }

    val wordDictionary: WordDictionary by lazy {
        WordDictionary.parse(context.assets.open("words_ko.txt").bufferedReader().use { it.readText() })
    }

    val quizBank: QuizBank by lazy {
        QuizBank.parse(context.assets.open("quiz_ko.txt").bufferedReader().use { it.readText() })
    }

    val engine: CopilotEngine by lazy { CopilotEngine(context, this) }
}

val Context.appContainer: AppContainer get() = (applicationContext as LunaApp).container
