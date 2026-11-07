package app.daltokki.drive.domain.sim

import app.daltokki.drive.domain.dialog.ReplyContext
import app.daltokki.drive.domain.model.FatigueLevel

/** 자동 운전자의 대답 하나: 생각하는 시간(응답 지연) 뒤에 말함 */
data class AutoReply(val text: String, val thinkMs: Long)

/** 자동 운전자가 판단에 쓰는 지금 상황 */
data class AutoDriverInput(
    val context: ReplyContext,
    /** 추천 대답(끝말잇기는 규칙에 맞는 단어, 퀴즈는 정답 포함) */
    val hints: List<String>,
    /** 시나리오 구간 이름 (예: "피로 누적", "졸음 운전") */
    val phase: String,
    val level: FatigueLevel,
    /** 이번 운행에서 같은 종류의 질문을 받은 횟수 (0부터) */
    val seen: Int,
    /** 같은 종류 질문이 연달아 나온 순서 (끝말잇기 몇 번째 차례, 반응 게임 몇 번째 판). 0부터 */
    val round: Int,
    /** 정답이 정해진 질문의 정답 (초성·음식·숫자·수수께끼·퀴즈) */
    val expected: String? = null,
)

/** 자동 운전자가 먼저 하는 말·차량 이벤트 */
enum class AutoAction {
    /** 방향지시등 + 차선 변경 5초 (달토끼는 말 걸기를 미룸) */
    LANE_CHANGE,
    /** 통화 12초 (달토끼는 먼저 말을 걸지 않음) */
    CALL,
    /** "달토끼야, 초성 게임 하자" (14차: 끝말잇기 → 초성 게임으로 게임 다양화를 보여줌) */
    ASK_WORD_GAME,
    /** 초성 게임이 끝나면 "숫자 게임도 하자" → 콤보가 이어져 5연속 '달빛 모드'까지 */
    ASK_MORE_GAME,
}

/**
 * 자동 시연의 운전자 대본 (안드로이드에 의존하지 않는 순수 Kotlin).
 *
 * 졸음 판정은 실제 엔진이 하고, 자동 운전자는 '사람이 했을 법한 대답'만 대신합니다.
 * 대답 속도도 연기합니다: 또렷할 때 빠르게 → 피로가 쌓이면 느리게 → 졸음 구간에서는 대답하지 않음.
 * 그래서 화면의 근거("대답이 평소보다 2배 느림", "무응답 3회")는 엔진이 실제로 계산한 값입니다.
 *
 * 새벽 고속도로 대본 (말 수 '수다')
 * 1. 출발: "오늘도 부산 가?" → "응, 부산 가" (기억하는 대화) → "어젯밤 몇 시간 잤어?" → "다섯 시간 잤어" (권장 휴식 75분)
 * 2. 또렷함: 차선 변경 이벤트(말 걸기 대기)
 * 3. 피로 누적: 통화 12초(기다림) → "달토끼야, 초성 게임 하자" → 3문제 정답(초성 박사)
 *    · 내기순환으로 실내 CO₂ 추정이 오르면 "외기로 바꿔 줄까?" → "응, 바꿔 줘" → 외기 전환. 말 걸기에는 느리게 대답
 * 4. 졸음 운전: "반응 게임 하자"(느리게) → 1.3초·1.9초·놓침 → 휴게소 권유에 무응답 → 차량 주의 경고 → 위험
 * 5. 위험: "응, 경로에 추가해줘" → 휴게소 → "커피냅 15분 할래?" → "응, 잠깐 잘게"
 *    → 깨우고 반응 게임 재측정(빨라짐) → 도장 → 운행 리포트
 */
object AutoDriverScript {

    /** 졸음 구간인지 (대답을 늦추거나 하지 않음) */
    fun isDrowsy(phase: String): Boolean = phase.contains("졸음")

    /** 휴게소에서 쉬는 구간인지 */
    fun isResting(phase: String): Boolean = phase.contains("휴식") && !phase.contains("후")

    /** 피로가 쌓이는 구간인지 (대답이 조금 느려짐) */
    fun isTiring(phase: String): Boolean = phase.contains("피로")

    /** 반응 게임 반응 시간: 또렷할 때 / 졸음 구간 (null = 놓침) */
    private val REACTION_AWAKE = listOf(650L, 720L, 600L, 780L, 690L)
    private val REACTION_DROWSY = listOf<Long?>(1_300L, 1_900L, null)

    /** 끝말잇기는 이만큼 대답하고 "그만할래" */
    const val WORD_TURNS = 3

    fun decide(i: AutoDriverInput): AutoReply? {
        val drowsy = isDrowsy(i.phase)
        val tiring = isTiring(i.phase)
        return when (i.context) {
            ReplyContext.SLEEP -> AutoReply("다섯 시간 잤어", 1_100)
            ReplyContext.GREETING -> AutoReply("부산 가는 길이야", 1_000)
            ReplyContext.CHECK_IN -> when {
                drowsy || i.level >= FatigueLevel.WARNING -> null
                tiring || i.level >= FatigueLevel.CAUTION -> AutoReply("아까 김밥 먹었어", 2_300)
                else -> AutoReply("부산 가는 길이야", 900)
            }
            ReplyContext.CHECK_IN_RETRY -> if (drowsy || i.level >= FatigueLevel.WARNING) null else AutoReply("응 들려", 1_700)
            // 경고 단계 제안: 처음 한 번만 반응 게임을 고르고(느리게), 그다음부터는 대답하지 않음
            ReplyContext.OFFER -> if (i.seen == 0) AutoReply("반응 게임 하자", if (drowsy) 2_600 else 1_800) else null
            ReplyContext.AWAKE_CHALLENGE -> AutoReply("반응 게임", 1_500)
            // 휴게소: 위험 단계에서만 승낙. 그 전에는 대답하지 않음(무응답도 졸음 근거)
            ReplyContext.REST -> if (i.level == FatigueLevel.DANGER) AutoReply("응, 경로에 추가해줘", 2_400) else null
            ReplyContext.WORD -> {
                val word = i.hints.firstOrNull { it != STOP }
                if (i.round < WORD_TURNS && word != null) AutoReply(word, 1_300L + i.round * 250L) else AutoReply(STOP, 1_000)
            }
            ReplyContext.QUIZ -> {
                val answer = i.hints.firstOrNull { it != "모르겠어" } ?: "모르겠어"
                AutoReply(answer, if (tiring || drowsy) 2_200 else 1_500)
            }
            ReplyContext.REACTION -> {
                // 커피냅 뒤 재측정(휴게소)은 또렷하게
                val ms = if (!isResting(i.phase) && (drowsy || i.level >= FatigueLevel.WARNING)) {
                    REACTION_DROWSY.getOrNull(i.round)
                } else {
                    REACTION_AWAKE[i.round % REACTION_AWAKE.size]
                }
                ms?.let { AutoReply("빛!", it) }
            }
            ReplyContext.FRESH_AIR -> AutoReply("응, 바꿔 줘", if (tiring || drowsy) 1_900 else 1_200)
            ReplyContext.COOL_DOWN -> AutoReply("응, 낮춰 줘", if (tiring || drowsy) 2_000 else 1_300)
            ReplyContext.NAP -> AutoReply("응, 잠깐 잘게", 1_400)
            ReplyContext.BOREDOM -> AutoReply("응, 좋아", 1_200)
            ReplyContext.MEMORY -> AutoReply("응, 부산 가", 900)
            ReplyContext.CHOSUNG, ReplyContext.FOOD, ReplyContext.DIGITS, ReplyContext.RIDDLE -> {
                val answer = i.expected ?: i.hints.firstOrNull { it != STOP && it != "모르겠어" } ?: "모르겠어"
                AutoReply(answer, (if (tiring || drowsy) 1_900L else 1_400L) + i.round * 150L)
            }
        }
    }

    /**
     * 구간이 시작되고 나서 할 일 (자동 운전자가 먼저).
     * @param done 이미 한 일
     */
    fun actionFor(phase: String, level: FatigueLevel, done: Set<AutoAction>): AutoAction? = when {
        phase.contains("또렷") && AutoAction.LANE_CHANGE !in done -> AutoAction.LANE_CHANGE
        isTiring(phase) && AutoAction.CALL !in done -> AutoAction.CALL
        isTiring(phase) && level < FatigueLevel.WARNING && AutoAction.ASK_WORD_GAME !in done -> AutoAction.ASK_WORD_GAME
        isTiring(phase) && level < FatigueLevel.WARNING && AutoAction.ASK_MORE_GAME !in done -> AutoAction.ASK_MORE_GAME
        else -> null
    }

    /** 운전자가 먼저 하는 말 */
    const val ASK_WORD_GAME_TEXT = "달토끼야, 초성 게임 하자"

    /** 초성 게임 뒤에 이어서 (콤보가 끊기지 않고 5연속까지) */
    const val ASK_MORE_GAME_TEXT = "숫자 게임도 하자"

    const val STOP = "그만할래"

    /**
     * 장면 자막: 구간·상황이 바뀔 때 화면 위에 잠깐 띄우는 한 줄 (번호는 화면에서 순서대로 붙임).
     * 같은 키는 한 번만 보여줍니다.
     */
    enum class Scene(val title: String, val detail: String) {
        DRIVER("운전자별 습관 기억", "지난 12번 운행 습관으로 출발하자마자 판단"),
        MEMORY("기억하는 대화", "지난 목적지·좋아하는 음식 기억 (기기 안에만)"),
        START("출발 전 컨디션", "어젯밤 잠으로 오늘의 휴식 간격을 정해요"),
        HABIT_GUARD("졸린 날은 습관을 배우지 않아요", "졸린 날 운행은 습관 기준에서 빼요"),
        QUIET("멀쩡할 때는 조용히", "차선 변경·제동 중에는 말 걸기를 미뤄요"),
        CALL("통화 중에는 기다려요", "먼저 말을 걸지 않아요"),
        EARLY("말 걸기 전에 먼저 알아채요", "핸들 무조작 후 급꺾음 · 대답 속도"),
        WORD_GAME("게임으로 깨어 있게", "7가지 게임을 피로 단계에 맞게"),
        COMBO("연속 정답 콤보", "2연속 ×1.5 · 3연속 ×2 · 5연속 ×3"),
        REWARD("쉬면 보상이 터진다", "운전 중엔 귀로 쌓고 · 쉬면 눈으로"),
        GIFT("달빛 선물 상자", "쉴 때마다 하나 · 달토끼 꾸미기"),
        AIR("실내 공기도 봐요", "CO₂ 추정 → 외기 전환 · 온도 2도 낮추기"),
        DROWSY("졸음 신호가 쌓이면", "대답 지연 · 무응답 · 차선 이탈"),
        REACTION("반응 게임", "반응 속도도 졸음 판단의 근거"),
        DANGER("위험 · 차량 주의 경고", "같은 방향 가장 가까운 휴게소를 내비에"),
        REST("쉬는 게 가장 큰 보상", "휴게소 도장 447곳 · 달 레벨"),
        NAP("커피냅 15분", "깨면 반응 게임으로 개운함 측정"),
        REPORT("운행 결과", "별 3개 · 등급 · 신기록 · 점수 내역"),
    }

    /** 구간 이름 → 장면 */
    fun sceneForPhase(phase: String): Scene? = when {
        phase.contains("출발") || phase.contains("시내") -> Scene.START
        phase.contains("또렷") -> Scene.QUIET
        isTiring(phase) -> Scene.EARLY
        isDrowsy(phase) -> Scene.DROWSY
        isResting(phase) -> Scene.REST
        phase.contains("도착") -> Scene.REPORT
        else -> null
    }
}
