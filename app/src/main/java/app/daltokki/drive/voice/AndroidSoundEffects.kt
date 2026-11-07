package app.daltokki.drive.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * 효과음 재생 (AudioTrack, 소리 파일 없이 코드로 합성).
 * 달토끼 목소리와 같은 음성 비서 오디오(USAGE_ASSISTANT)로 내보내 차량에서도 같은 볼륨으로 들립니다.
 */
class AndroidSoundEffects : SoundEffects {

    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    private val format = AudioFormat.Builder()
        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
        .setSampleRate(ToneSynth.SAMPLE_RATE)
        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
        .build()

    /** 같은 소리는 한 번만 합성 */
    private val cache = ConcurrentHashMap<String, ShortArray>()

    override suspend fun play(effect: SoundEffect, step: Int) {
        val pcm = cache.getOrPut("${effect.name}:$step") { ToneSynth.render(SoundBank.tones(effect, step)) }
        if (pcm.isEmpty()) return
        val track = withContext(Dispatchers.Default) {
            runCatching {
                AudioTrack.Builder()
                    .setAudioAttributes(attrs)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(pcm.size * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()
                    .also {
                        it.write(pcm, 0, pcm.size)
                        it.play()
                    }
            }.onFailure { Log.w("SoundEffects", "효과음 재생 실패: $effect", it) }.getOrNull()
        } ?: return
        try {
            delay(pcm.size * 1000L / ToneSynth.SAMPLE_RATE + 30L)
        } finally {
            withContext(NonCancellable) {
                runCatching {
                    track.stop()
                    track.release()
                }
            }
        }
    }
}
