package app.daltokki.drive.domain.dialog

/** 운전자 발화의 의도 (키워드 기반, 빠르고 예측 가능하게) */
enum class UtteranceIntent {
    SLEEPY, NOT_SLEEPY, QUIET, MORE_TALK, WORD_GAME, QUIZ, REACTION_GAME, REST_AREA, STOP_GAME, YES, NO, OTHER,
    CHOSUNG_GAME, FOOD_QUIZ, DIGIT_GAME, RIDDLE_GAME, ANY_GAME;

    companion object {
        /** 말 수 줄이기 ("조용한 노래"처럼 '조용'만 들어간 말은 제외) */
        private val K_QUIET = listOf(
            "조용히", "조용해", "조용 해", "조용 좀", "그만 말", "말 걸지", "말걸지", "말 시키지", "말시키지",
            "말 줄여", "말 좀 줄", "시끄러",
        )
        /** 말 수 늘리기 */
        private val K_MORE = listOf("말 걸어", "말걸어", "다시 말", "수다 떨", "심심해", "말 좀 해", "말 많이", "말 해줘", "말해줘")
        /** "안 졸려" 류 — "졸려"보다 먼저 확인해야 함 */
        private val K_NOT_SLEEPY = listOf("안 졸려", "안졸려", "안 졸리", "안졸리", "졸리지 않", "졸리진 않", "안 피곤", "안피곤", "멀쩡", "쌩쌩", "말짱")
        private val K_SLEEPY = listOf("졸려", "졸리", "졸음", "잠 와", "잠와", "잠이 와", "피곤", "눈이 감", "하품")
        private val K_WORD_GAME = listOf("끝말")
        private val K_CHOSUNG = listOf("초성")
        private val K_FOOD_QUIZ = listOf("음식 퀴즈", "음식퀴즈", "메뉴 퀴즈", "메뉴퀴즈", "휴게소 퀴즈", "휴게소퀴즈")
        private val K_DIGITS = listOf("숫자 게임", "숫자게임", "숫자 따라", "숫자놀이", "숫자 놀이")
        private val K_RIDDLE = listOf("수수께끼")
        private val K_ANY_GAME = listOf("게임 하자", "게임하자", "게임 할래", "게임할래", "놀자", "말놀이", "게임 한 판", "게임 한판")
        private val K_QUIZ = listOf("퀴즈", "문제 내", "문제내")
        private val K_REACTION = listOf("반응", "달빛")
        private val K_REST = listOf("휴게소", "쉼터", "쉬고 싶", "쉬자", "쉬어 가", "쉬어가", "쉴래")
        private val K_STOP = listOf("그만할래", "그만 할래", "그만하자", "그만 하자", "안 할래", "안할래", "그만")

        /** 거절: 낱말이 이것으로 시작하면 NO */
        private val K_NO_WORDS = listOf("아니", "싫어", "됐어", "괜찮아", "안해", "안할", "노노", "나중에", "놉")
        /** 거절: 띄어 쓴 표현 */
        private val K_NO_PHRASES = listOf("안 해", "안 할", "필요 없", "안 갈")
        /** 승낙: 한 글자 대답은 첫 낱말 전체가 일치해야 함 ("어디야"를 '예'로 오해하지 않게) */
        private val K_YES_EXACT = setOf("응", "어", "네", "예", "콜", "웅", "넵", "넹", "엉", "ㅇㅇ", "응응", "네네", "예예", "어어")
        /** 승낙: 낱말이 이것으로 시작하면 YES */
        private val K_YES_WORDS = listOf("그래", "좋아", "좋지", "오케이", "할래", "하자", "당연", "알겠", "추가해", "가자")

        private val PUNCT = charArrayOf('.', ',', '!', '?', '~', '…')

        fun parse(text: String): UtteranceIntent {
            val t = text.trim()
            if (K_QUIET.any { t.contains(it) }) return QUIET
            if (K_MORE.any { t.contains(it) }) return MORE_TALK
            if (K_WORD_GAME.any { t.contains(it) }) return WORD_GAME
            if (K_CHOSUNG.any { t.contains(it) }) return CHOSUNG_GAME
            if (K_FOOD_QUIZ.any { t.contains(it) }) return FOOD_QUIZ
            if (K_RIDDLE.any { t.contains(it) }) return RIDDLE_GAME
            if (K_DIGITS.any { t.contains(it) }) return DIGIT_GAME
            if (K_QUIZ.any { t.contains(it) }) return QUIZ
            if (K_REACTION.any { t.contains(it) }) return REACTION_GAME
            if (K_NOT_SLEEPY.any { t.contains(it) }) return NOT_SLEEPY
            if (K_SLEEPY.any { t.contains(it) }) return SLEEPY
            if (K_REST.any { t.contains(it) }) return REST_AREA
            if (K_ANY_GAME.any { t.contains(it) }) return ANY_GAME

            val compact = t.replace(" ", "")
            if (compact.length <= 10) {
                val words = t.split(' ').map { it.trim(*PUNCT) }.filter { it.isNotEmpty() }
                // 거절을 먼저 봅니다: "어 아니"는 NO
                if (K_NO_PHRASES.any { t.contains(it) } || words.any { w -> K_NO_WORDS.any { w.startsWith(it) } }) return NO
                val first = words.firstOrNull().orEmpty()
                val firstPlain = first.removeSuffix("요")
                if (first in K_YES_EXACT || firstPlain in K_YES_EXACT) return YES
                if (words.any { w -> K_YES_WORDS.any { w.startsWith(it) } }) return YES
            }
            return OTHER
        }

        /** 게임 도중 "그만"이라고 했는지 */
        fun isStop(text: String): Boolean {
            val t = text.trim()
            return K_STOP.any { t.contains(it) } || t == "끝"
        }
    }
}
