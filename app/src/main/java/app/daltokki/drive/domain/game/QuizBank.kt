package app.daltokki.drive.domain.game

import kotlin.random.Random

data class QuizItem(
    val question: String,
    /** 인정하는 답들. 첫 번째가 대표 정답. */
    val answers: List<String>,
    val explanation: String? = null,
) {
    val displayAnswer: String get() = answers.first()

    fun isCorrect(reply: String): Boolean {
        val r = normalize(reply)
        if (r.isEmpty()) return false
        return answers.any { a -> val n = normalize(a); n.isNotEmpty() && r.contains(n) }
    }

    private fun normalize(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
}

/**
 * 짧고 운전 중에도 부담 없는 퀴즈 모음.
 * 질문 길이와 난이도가 비슷해야 응답 시간을 졸음 지표로 비교할 수 있습니다.
 */
class QuizBank(private val items: List<QuizItem>) {

    val size: Int get() = items.size

    fun pick(count: Int, random: Random = Random.Default): List<QuizItem> {
        val result = mutableListOf<QuizItem>()
        val pool = items.shuffled(random).toMutableList()
        repeat(count) { i ->
            // 세 문제 중 하나는 간단한 덧셈(응답 속도 비교에 가장 안정적)
            if (i == 1 || pool.isEmpty()) result += arithmetic(random) else result += pool.removeAt(0)
        }
        return result
    }

    companion object {
        /** 형식: 질문|정답1,정답2|해설(선택). '#'으로 시작하면 주석. */
        fun parse(text: String): QuizBank = QuizBank(
            text.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .mapNotNull { line ->
                    val parts = line.split("|")
                    if (parts.size < 2) return@mapNotNull null
                    val answers = parts[1].split(",").map { it.trim() }.filter { it.isNotEmpty() }
                    if (answers.isEmpty()) return@mapNotNull null
                    QuizItem(parts[0].trim(), answers, parts.getOrNull(2)?.trim()?.ifEmpty { null })
                }
                .toList(),
        )

        fun arithmetic(random: Random): QuizItem {
            val a = random.nextInt(2, 10)
            val b = random.nextInt(2, 10)
            val sum = a + b
            return QuizItem(
                question = "${sinoKorean(a)} 더하기 ${sinoKorean(b)}는?",
                answers = listOf(sum.toString(), sinoKorean(sum), nativeKorean(sum)).distinct(),
            )
        }

        private val SINO_DIGITS = listOf("", "일", "이", "삼", "사", "오", "육", "칠", "팔", "구")
        private val NATIVE_ONES = listOf("", "하나", "둘", "셋", "넷", "다섯", "여섯", "일곱", "여덟", "아홉")

        fun sinoKorean(n: Int): String {
            if (n == 0) return "영"
            val tens = n / 10
            val ones = n % 10
            val t = when (tens) {
                0 -> ""
                1 -> "십"
                else -> SINO_DIGITS[tens] + "십"
            }
            return t + SINO_DIGITS[ones]
        }

        fun nativeKorean(n: Int): String = when (n / 10) {
            0 -> NATIVE_ONES[n]
            1 -> "열" + NATIVE_ONES[n % 10]
            else -> sinoKorean(n)
        }
    }
}
