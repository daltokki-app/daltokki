package app.daltokki.drive.voice

/**
 * 차 안의 소리 상황.
 * - 달토끼가 말하는 동안 음악·라디오 소리를 줄임(덕킹) → 끝나면 원래대로
 * - 통화 중이면 달토끼는 먼저 말을 걸지 않고 기다림 (운전자가 부르면 대답)
 */
interface AudioEnvironment {
    /** 지금 통화 중인지 */
    val inCall: Boolean

    /** 달토끼가 말하기 시작할 때: 다른 소리를 줄여 달라고 요청 (여러 번 불러도 한 번만 요청) */
    fun duck()

    /** 달토끼 대화가 끝났을 때: 줄인 소리를 원래대로 */
    fun unduck()
}

/** 단위 테스트·소리 제어가 없는 환경 */
object NoAudioEnvironment : AudioEnvironment {
    override val inCall: Boolean = false
    override fun duck() = Unit
    override fun unduck() = Unit
}
