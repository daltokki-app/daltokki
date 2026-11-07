package app.daltokki.drive.platform

import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.listener.ResultListener
import ai.pleos.playground.tts.TextToSpeech
import android.content.Context
import android.os.SystemClock
import android.util.Log
import app.daltokki.drive.voice.Reply
import app.daltokki.drive.voice.SpeechInput
import app.daltokki.drive.voice.SpeechOutput
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume
import ai.pleos.playground.stt.constant.ErrorType as SttErrorType
import ai.pleos.playground.stt.constant.Mode as SttMode
import ai.pleos.playground.stt.listener.OnInitializeListener as SttInitListener
import ai.pleos.playground.tts.constant.ErrorType as TtsErrorType
import ai.pleos.playground.tts.constant.Mode as TtsMode
import ai.pleos.playground.tts.listener.EventListener as TtsEventListener
import ai.pleos.playground.tts.listener.OnInitializeListener as TtsInitListener

/**
 * Pleos TextToSpeech SDK 3.3.1 (온디바이스 모드 — 네트워크·앱 등록 불필요).
 * 초기화에 실패하거나 3초 안에 준비되지 않으면 안드로이드 기본 TTS([fallback])로 말합니다.
 */
internal class PleosTtsOutput(
    context: Context,
    fallbackFactory: () -> SpeechOutput,
) : SpeechOutput {

    private val fallback: SpeechOutput by lazy(fallbackFactory)
    private val ready = CompletableDeferred<Boolean>()

    private val _status = MutableStateFlow("음성 합성: Pleos TTS 준비 중")
    override val status: StateFlow<String> = _status

    @Volatile private var pending: CancellableContinuation<Unit>? = null
    @Volatile private var usingFallback = false

    private val tts: TextToSpeech? = try {
        TextToSpeech(context.applicationContext, TtsMode.ON_DEVICE)
    } catch (t: Throwable) {
        Log.w(TAG, "Pleos TTS 생성 실패", t)
        null
    }

    private val listener = object : TtsEventListener {
        override fun onReady() = Unit
        override fun onStart() = Unit
        override fun onUpdatedRms(rms: Double) = Unit
        override fun onDone() = finish()
        override fun onStop() = finish()
        override fun onError(errorType: TtsErrorType) {
            Log.w(TAG, "Pleos TTS 오류: $errorType")
            finish()
        }
    }

    init {
        val engine = tts
        if (engine == null) {
            ready.complete(false)
        } else {
            try {
                engine.addEventListener(listener)
                engine.initialize(object : TtsInitListener {
                    override fun onSuccess() {
                        runCatching { engine.setLocale(Locale.KOREA) }
                        _status.value = "음성 합성: Pleos TTS (온디바이스)"
                        ready.complete(true)
                    }

                    override fun onFailure(errorType: TtsErrorType) {
                        Log.w(TAG, "Pleos TTS 초기화 실패: $errorType")
                        ready.complete(false)
                    }
                })
            } catch (t: Throwable) {
                Log.w(TAG, "Pleos TTS 초기화 실패", t)
                ready.complete(false)
            }
        }
    }

    private fun finish() {
        val c = pending ?: return
        pending = null
        if (c.isActive) c.resume(Unit)
    }

    private suspend fun pleosReady(): Boolean {
        val ok = withTimeoutOrNull(3_000) { ready.await() } ?: false
        if (!ok && !usingFallback) {
            usingFallback = true
            // 대체 경로의 상태 문구를 그대로 보여 줌
            _status.value = "음성 합성: Pleos TTS 사용 불가 → 안드로이드 기본"
        }
        return ok
    }

    override suspend fun speak(text: String) {
        val engine = tts
        if (engine == null || !pleosReady()) {
            fallback.speak(text)
            return
        }
        val estimate = (text.length * 110L).coerceIn(1_200L, 8_000L)
        withTimeoutOrNull(estimate * 3 + 5_000) {
            suspendCancellableCoroutine { cont ->
                pending = cont
                cont.invokeOnCancellation {
                    if (pending === cont) pending = null
                    runCatching { engine.stop() }
                }
                try {
                    engine.speak(text, true)
                } catch (t: Throwable) {
                    Log.w(TAG, "Pleos TTS speak 실패", t)
                    finish()
                }
            }
        }
    }

    override fun stop() {
        if (usingFallback) {
            fallback.stop()
            return
        }
        if (ready.isCompleted) runCatching { tts?.stop() }
    }

    private companion object {
        const val TAG = "PleosTts"
    }
}

/**
 * Pleos SpeechToText SDK 3.3.1 (온디바이스 모드).
 * 준비되지 않았으면 안드로이드 기본 음성 인식([fallback])을 씁니다.
 *
 * 응답 지연(대답 시작 시각)은 처음 글자가 인식된 순간으로 잡습니다.
 * (Pleos STT의 끝점 검출 값은 시간이 아니라 오디오 버퍼 위치라서, 첫 부분 결과 시각을 씁니다)
 */
internal class PleosSttInput(
    context: Context,
    fallbackFactory: () -> SpeechInput,
) : SpeechInput {

    private val fallback: SpeechInput by lazy(fallbackFactory)

    @Volatile private var ready = false
    @Volatile private var failed = false
    @Volatile private var pending: CancellableContinuation<Reply?>? = null
    @Volatile private var speechStart = -1L
    @Volatile private var lastPartial: String? = null

    private val _status = MutableStateFlow("음성 인식: Pleos STT 준비 중")
    override val status: StateFlow<String> = _status

    private val stt: SpeechToText? = try {
        SpeechToText(context.applicationContext, SttMode.ON_DEVICE)
    } catch (t: Throwable) {
        Log.w(TAG, "Pleos STT 생성 실패", t)
        null
    }

    private val listener = object : ResultListener {
        override fun onReady() = Unit
        override fun onStartedRecognition() = Unit
        override fun onUpdated(stt: String, completed: Boolean) {
            val text = stt
            if (text.isNotBlank()) {
                if (speechStart < 0) speechStart = SystemClock.elapsedRealtime()
                lastPartial = text
            }
            if (completed) finish(text.takeIf { it.isNotBlank() } ?: lastPartial)
        }
        override fun onUpdatedEpdData(on: Long, off: Long) = Unit
        override fun onUpdatedRms(rms: Double) = Unit
        // 최종 결과 없이 끝나면 마지막 부분 결과라도 씀
        override fun onEndedRecognition() = finish(lastPartial)
        override fun onError(errorType: SttErrorType) {
            Log.w(TAG, "Pleos STT 오류: $errorType")
            finish(null)
        }
    }

    init {
        val engine = stt
        if (engine == null) {
            failed = true
        } else {
            try {
                engine.addListener(listener)
                engine.initialize(object : SttInitListener {
                    override fun onSuccess() {
                        runCatching { engine.setLocale(Locale.KOREA) }
                        ready = true
                        _status.value = "음성 인식: Pleos STT (온디바이스)"
                    }

                    override fun onFailure(errorType: SttErrorType) {
                        Log.w(TAG, "Pleos STT 초기화 실패: $errorType")
                        failed = true
                        _status.value = "음성 인식: Pleos STT 사용 불가 → 안드로이드 기본"
                    }
                })
            } catch (t: Throwable) {
                Log.w(TAG, "Pleos STT 초기화 실패", t)
                failed = true
            }
        }
    }

    private fun finish(text: String?) {
        val c = pending ?: return
        pending = null
        val start = speechStart
        if (c.isActive) {
            c.resume(text?.let { Reply(it, if (start > 0) start else SystemClock.elapsedRealtime()) })
        }
    }

    override val isAvailable: Boolean
        get() = (ready && stt != null) || fallback.isAvailable

    override fun refreshStatus() {
        if (!ready) fallback.refreshStatus()
    }

    override suspend fun listen(timeoutMs: Long): Reply? {
        val engine = stt
        if (engine == null || !ready) return fallback.listen(timeoutMs)
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                speechStart = -1L
                lastPartial = null
                pending = cont
                cont.invokeOnCancellation {
                    if (pending === cont) pending = null
                    runCatching { engine.stop() }
                }
                try {
                    engine.request()
                } catch (t: Throwable) {
                    Log.w(TAG, "Pleos STT request 실패", t)
                    finish(null)
                }
            }
        }
    }

    private companion object {
        const val TAG = "PleosStt"
    }
}
