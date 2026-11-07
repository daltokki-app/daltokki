package app.daltokki.drive.voice

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 게임 효과음. 운전 중에는 화면 연출 대신 짧은 소리로 "게임하는 느낌"을 줍니다.
 * 모두 0.1~0.6초로 짧고, 달토끼의 말과 겹치지 않게 말하기 전에 재생합니다.
 */
enum class SoundEffect {
    /** 게임 시작 */
    GAME_START,
    /** 정답 (콤보가 이어질수록 음이 한 단계씩 올라감) */
    CORRECT,
    /** 오답·시간 초과 (부드럽게 내려가는 음, 꾸짖는 느낌 없이) */
    WRONG,
    /** 반응 게임 신호 "띵" */
    CUE,
    /** 새 배지 */
    BADGE,
    /** 휴게소 도장 "쾅" */
    STAMP,
    /** 10분 휴식 완료 */
    REST_DONE,
    /** 커피냅 끝 — 깨우는 소리 (부드럽게 올라가는 세 번 종) */
    WAKE,
    /** 3연속 이상 콤보 — 정답음 뒤에 올라가는 두 음 (콤보가 길수록 높게) */
    COMBO,
    /** 안전 행동 점수 (외기 전환·온도 낮추기·휴게소 가기) — 맑은 바람 종 */
    SAFETY,
    /** 달빛 조각이 점수판으로 들어갈 때 '띵' (step만큼 높아짐) */
    SHARD,
    /** 잭팟·레벨업 팡파르 (짧게) */
    FANFARE,
    /** 운행 결과 별 하나 '쾅' */
    STAR,
}

interface SoundEffects {
    /** 재생이 끝날 때까지 기다립니다. 실패해도 조용히 넘어갑니다. */
    suspend fun play(effect: SoundEffect, step: Int = 0)
    fun release() = Unit
}

/** 효과음 없음 (단위 테스트·소리를 끈 경우) */
object NoSoundEffects : SoundEffects {
    override suspend fun play(effect: SoundEffect, step: Int) = Unit
}

/** 음 하나: 주파수(Hz), 길이(ms), 다음 음까지 간격(ms), 세기(0~1) */
data class Tone(val freqHz: Double, val durMs: Int, val gapMs: Int = 0, val gain: Double = 1.0)

/** 효과음 악보. 음 높이는 C5(523Hz) 기준 반음 단위. */
object SoundBank {
    private fun note(semitonesFromC5: Int): Double = 523.25 * 2.0.pow(semitonesFromC5 / 12.0)

    fun tones(effect: SoundEffect, step: Int = 0): List<Tone> {
        val up = step.coerceIn(0, 7) // 콤보 단계만큼 반음씩 올림 (최대 7)
        return when (effect) {
            SoundEffect.GAME_START -> listOf(Tone(note(0), 90, 20), Tone(note(7), 140))
            SoundEffect.CORRECT -> listOf(Tone(note(4 + up), 80, 15), Tone(note(9 + up), 160))
            SoundEffect.WRONG -> listOf(Tone(note(-3), 120, 10, 0.7), Tone(note(-7), 200, 0, 0.6))
            SoundEffect.CUE -> listOf(Tone(note(12), 130))
            SoundEffect.BADGE -> listOf(Tone(note(0), 90, 10), Tone(note(4), 90, 10), Tone(note(7), 90, 10), Tone(note(12), 280))
            SoundEffect.STAMP -> listOf(Tone(110.0, 90, 30, 1.0), Tone(note(7), 110, 10), Tone(note(12), 220))
            SoundEffect.REST_DONE -> listOf(Tone(note(-5), 140, 20), Tone(note(0), 140, 20), Tone(note(4), 300))
            SoundEffect.COMBO -> listOf(Tone(note(7 + up), 70, 10, 0.8), Tone(note(12 + up), 70, 10, 0.9), Tone(note(16 + up), 180))
            SoundEffect.SAFETY -> listOf(Tone(note(2), 100, 20, 0.7), Tone(note(9), 100, 20, 0.8), Tone(note(14), 260, 0, 0.8))
            SoundEffect.SHARD -> step.coerceIn(0, 12).let { k -> listOf(Tone(note(12 + k), 50, 0, 0.6), Tone(note(19 + k), 70, 0, 0.35)) }
            SoundEffect.FANFARE -> listOf(Tone(note(0), 80, 5), Tone(note(4), 80, 5), Tone(note(7), 80, 5), Tone(note(12), 100, 10), Tone(note(7), 60, 5), Tone(note(12), 220))
            SoundEffect.STAR -> listOf(Tone(110.0, 70, 10, 1.0), Tone(note(12), 90, 10, 0.9), Tone(note(19), 160))
            SoundEffect.WAKE -> listOf(Tone(note(0), 110, 30, 0.6), Tone(note(4), 110, 30, 0.8), Tone(note(7), 110, 30, 0.9), Tone(note(12), 230))
        }
    }

    fun durationMs(effect: SoundEffect, step: Int = 0): Int = tones(effect, step).sumOf { it.durMs + it.gapMs }
}

/**
 * 간단한 종소리 합성 (안드로이드 없이 동작 → 단위 테스트 가능).
 * 사인파 + 2배음, 짧은 시작(5ms) + 지수 감쇠로 '띵' 하는 소리.
 */
object ToneSynth {
    const val SAMPLE_RATE = 22_050

    fun render(tones: List<Tone>, volume: Double = 0.35, sampleRate: Int = SAMPLE_RATE): ShortArray {
        val total = tones.sumOf { ((it.durMs + it.gapMs) * sampleRate / 1000.0).roundToInt() }
        val out = ShortArray(total)
        var pos = 0
        for (t in tones) {
            val n = (t.durMs * sampleRate / 1000.0).roundToInt()
            val attack = (0.005 * sampleRate).roundToInt().coerceAtLeast(1)
            val decay = n / 4.0
            for (i in 0 until n) {
                val time = i.toDouble() / sampleRate
                val env = (if (i < attack) i.toDouble() / attack else 1.0) * exp(-i / decay) *
                    // 끝 5ms는 0으로 모아 '딱' 소리 방지
                    (if (i > n - attack) (n - i).toDouble() / attack else 1.0)
                val wave = sin(2 * PI * t.freqHz * time) * 0.8 + sin(4 * PI * t.freqHz * time) * 0.2
                val v = (wave * env * t.gain * volume).coerceIn(-1.0, 1.0)
                if (pos + i < out.size) out[pos + i] = (v * Short.MAX_VALUE).roundToInt().toShort()
            }
            pos += n + (t.gapMs * sampleRate / 1000.0).roundToInt()
        }
        return out
    }
}
