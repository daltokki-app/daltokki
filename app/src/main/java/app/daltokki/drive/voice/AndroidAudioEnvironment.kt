package app.daltokki.drive.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.util.Log

/**
 * 안드로이드 오디오 포커스로 덕킹하고, 오디오 모드로 통화 중인지 봅니다.
 * - 덕킹: AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK (음악 앱이 볼륨을 잠깐 줄였다가 되돌림). 달토끼 목소리와 같은 음성 비서 용도.
 * - 통화: AudioManager.mode가 통화(IN_CALL) 또는 인터넷 통화(IN_COMMUNICATION)면 통화 중.
 *   (별도 권한 없이 확인 가능. 차량 블루투스 핸즈프리 통화도 이 모드로 바뀝니다)
 */
class AndroidAudioEnvironment(context: Context) : AudioEnvironment {

    private val am = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val focusRequest: AudioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        .setWillPauseWhenDucked(false)
        .setOnAudioFocusChangeListener { }
        .build()

    @Volatile private var ducked = false

    override val inCall: Boolean
        get() = runCatching {
            val mode = am.mode
            mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION
        }.getOrDefault(false)

    @Synchronized
    override fun duck() {
        if (ducked) return
        ducked = runCatching { am.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED }
            .onFailure { Log.w("AudioEnvironment", "오디오 포커스 요청 실패", it) }
            .getOrDefault(false)
    }

    @Synchronized
    override fun unduck() {
        if (!ducked) return
        runCatching { am.abandonAudioFocusRequest(focusRequest) }
        ducked = false
    }
}
