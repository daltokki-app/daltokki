package app.daltokki.drive.voice

import kotlinx.coroutines.flow.StateFlow

/** 운전자 발화 한 건 */
data class Reply(
    val text: String,
    /** 말하기 시작한 시각(SystemClock.elapsedRealtime). 응답 지연 계산에 사용. */
    val speechStartRealtime: Long,
    /** 달토끼 질문이 끝난 뒤 말하기 시작할 때까지 걸린 시간 */
    val latencyMs: Long = 0L,
)

/** 음성 합성(TTS). 차량에서는 Pleos TextToSpeech(온디바이스), 그 밖에는 안드로이드 기본 TTS. */
interface SpeechOutput {
    val status: StateFlow<String>
    /** 다 말할 때까지 기다립니다. */
    suspend fun speak(text: String)
    fun stop()
}

/** 음성 인식(STT). 차량에서는 Pleos SpeechToText(온디바이스), 그 밖에는 안드로이드 기본 SpeechRecognizer. */
interface SpeechInput {
    val isAvailable: Boolean
    val status: StateFlow<String>
    /** 한 번 듣기. 못 들으면 null. */
    suspend fun listen(timeoutMs: Long): Reply?
    /** 권한이 바뀌었을 때 상태 문구를 다시 계산 */
    fun refreshStatus() = Unit
}
