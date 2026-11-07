package app.daltokki.drive.voice

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/**
 * 안드로이드 기본 TTS.
 * 휴대폰이나 Pleos TextToSpeech를 쓸 수 없을 때의 대체 경로입니다.
 * 차량에서는 Pleos TextToSpeech(온디바이스, 앱 등록 불필요)를 먼저 씁니다 → platform/PleosVoice.kt
 *
 * @param maleVoice 자동 시연의 운전자 목소리: 한국어 남성 음성이 있으면 그것을, 없으면 음높이를 낮춰 구분합니다.
 */
class AndroidTtsOutput(context: Context, private val maleVoice: Boolean = false) : SpeechOutput {

    private val _status = MutableStateFlow("음성 합성 초기화 중")
    override val status: StateFlow<String> = _status

    private val ready = CompletableDeferred<Boolean>()
    private val pending = ConcurrentHashMap<String, CancellableContinuation<Unit>>()
    private lateinit var tts: TextToSpeech

    init {
        tts = TextToSpeech(context.applicationContext) { code ->
            if (code == TextToSpeech.SUCCESS) {
                val r = tts.setLanguage(Locale.KOREAN)
                val ok = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
                tts.setSpeechRate(1.05f)
                tts.setPitch(1.1f)
                if (maleVoice) {
                    val male = if (ok) pickMaleVoice() else null
                    if (male != null) tts.setVoice(male)
                    // 남성 음성을 못 찾으면 음높이를 낮춰 달토끼 목소리와 구분
                    tts.setPitch(if (male != null) 0.95f else 0.72f)
                    tts.setSpeechRate(1.0f)
                }
                // 차량 오디오에서 음성 비서 음성으로 처리(음악 볼륨을 잠시 낮추고 또렷하게 들림)
                tts.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) = finish(utteranceId)
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) = finish(utteranceId)
                    override fun onError(utteranceId: String?, errorCode: Int) = finish(utteranceId)
                    override fun onStop(utteranceId: String?, interrupted: Boolean) = finish(utteranceId)
                })
                _status.value = if (ok) STATUS_READY else "음성 합성: 한국어 음성 없음(화면 표시만)"
                ready.complete(ok)
            } else {
                _status.value = "음성 합성: 사용할 수 없음(화면 표시만)"
                ready.complete(false)
            }
        }
    }

    /**
     * 한국어 남성 음성 찾기. 엔진마다 이름이 달라서 'male'(female 제외)이 들어간 이름을 먼저,
     * 다음으로 Google TTS에서 남성으로 알려진 이름 후보를 봅니다. 없으면 null.
     */
    private fun pickMaleVoice(): android.speech.tts.Voice? = runCatching {
        val korean = tts.voices.orEmpty().filter { it.locale.language == "ko" && !it.isNetworkConnectionRequired }
        korean.firstOrNull { val n = it.name.lowercase(); n.contains("male") && !n.contains("female") }
            ?: MALE_NAME_HINTS.firstNotNullOfOrNull { hint -> korean.firstOrNull { it.name.lowercase().contains(hint) } }
    }.getOrNull()

    private fun finish(id: String?) {
        if (id == null) return
        pending.remove(id)?.let { if (it.isActive) it.resume(Unit) }
    }

    override suspend fun speak(text: String) {
        val ok = withTimeoutOrNull(3_000) { ready.await() } ?: false
        val estimate = (text.length * 110L).coerceIn(1_200L, 8_000L)
        if (!ok) {
            delay(estimate) // 음성이 없어도 대화 흐름(시연)은 이어지도록
            return
        }
        withTimeoutOrNull(estimate * 3 + 5_000) {
            suspendCancellableCoroutine { cont ->
                val id = UUID.randomUUID().toString()
                pending[id] = cont
                cont.invokeOnCancellation {
                    pending.remove(id)
                    tts.stop()
                }
                tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
            }
        }
    }

    override fun stop() {
        if (ready.isCompleted) tts.stop()
    }

    companion object {
        /** 한국어 음성이 준비된 상태 문구 (자동 시연에서 운전자 목소리를 쓸 수 있는지 확인용) */
        const val STATUS_READY = "음성 합성: 한국어 준비됨"
    }
}

/** Google TTS 한국어 남성 음성으로 알려진 이름 일부 (기기·버전마다 다를 수 있음) */
private val MALE_NAME_HINTS = listOf("ko-kr-x-koc", "ko-kr-x-kod")
