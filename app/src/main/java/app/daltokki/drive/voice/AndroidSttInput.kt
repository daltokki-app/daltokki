package app.daltokki.drive.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * 안드로이드 기본 음성 인식.
 * 휴대폰이나 Pleos SpeechToText를 쓸 수 없을 때의 대체 경로입니다.
 * 차량에서는 Pleos SpeechToText(온디바이스, 앱 등록 불필요)를 먼저 씁니다 → platform/PleosVoice.kt
 */
class AndroidSttInput(context: Context) : SpeechInput {

    private val ctx = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    private val _status = MutableStateFlow(describe())
    override val status: StateFlow<String> = _status

    override val isAvailable: Boolean
        get() = SpeechRecognizer.isRecognitionAvailable(ctx) &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun describe(): String = when {
        !SpeechRecognizer.isRecognitionAvailable(ctx) -> "음성 인식: 이 기기에 없음 → 화면 입력 사용"
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ->
            "음성 인식: 마이크 권한 필요"
        else -> "음성 인식: 사용 가능"
    }

    override fun refreshStatus() {
        _status.value = describe()
    }

    override suspend fun listen(timeoutMs: Long): Reply? = withContext(Dispatchers.Main) {
        refreshStatus()
        if (!isAvailable) return@withContext null
        withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val recognizer = SpeechRecognizer.createSpeechRecognizer(ctx)
                var speechStart = -1L
                var destroyed = false
                fun cleanup() {
                    if (destroyed) return
                    destroyed = true
                    main.post {
                        try {
                            recognizer.cancel()
                            recognizer.destroy()
                        } catch (_: Throwable) {
                        }
                    }
                }
                fun finish(r: Reply?) {
                    if (cont.isActive) cont.resume(r)
                    cleanup()
                }
                recognizer.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {}
                    override fun onBeginningOfSpeech() {
                        speechStart = SystemClock.elapsedRealtime()
                    }
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onError(error: Int) = finish(null)
                    override fun onResults(results: Bundle?) {
                        val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                        finish(
                            text?.takeIf { it.isNotBlank() }?.let {
                                Reply(it, if (speechStart > 0) speechStart else SystemClock.elapsedRealtime())
                            },
                        )
                    }
                    override fun onPartialResults(partialResults: Bundle?) {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                }
                cont.invokeOnCancellation { cleanup() }
                recognizer.startListening(intent)
            }
        }
    }
}
