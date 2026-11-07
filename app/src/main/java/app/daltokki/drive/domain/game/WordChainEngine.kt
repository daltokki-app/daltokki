package app.daltokki.drive.domain.game

import kotlin.random.Random

/** 끝말잇기용 단어 사전 */
class WordDictionary(words: Collection<String>) {

    private val set: Set<String> = words.asSequence()
        .map { it.trim() }
        .filter { w -> w.length in 2..6 && w.all { Hangul.isSyllable(it) } }
        .toSet()

    private val byFirst: Map<Char, List<String>> = set.groupBy { it.first() }

    val size: Int get() = set.size

    /** 전체 단어 (초성 게임용) */
    val words: Collection<String> get() = this.set

    fun contains(word: String): Boolean = word in set

    fun startingWith(chars: Set<Char>): List<String> = chars.flatMap { byFirst[it].orEmpty() }

    /** 이 글자로 끝나는 단어 다음에 이어갈 수 있는 단어 수 */
    fun continuationCount(last: Char): Int = startingWith(Hangul.startOptions(last)).size

    fun randomStarter(random: Random): String {
        val good = set.filter { continuationCount(it.last()) >= 3 }
        return (good.ifEmpty { set.toList() }).random(random)
    }

    companion object {
        /** 한 줄에 한 단어. '#'으로 시작하는 줄은 주석. */
        fun parse(text: String): WordDictionary =
            WordDictionary(text.lineSequence().filterNot { it.isBlank() || it.trimStart().startsWith("#") }.toList())
    }
}

/**
 * 끝말잇기 규칙 엔진. 판정은 LLM이 아니라 코드로 합니다.
 *
 * @param strict true면 사전에 있는 단어만 인정. false면 사전에 없어도 한글 2~6글자면 인정
 *               (내장 사전이 작을 때 운전자의 정상 단어를 틀렸다고 하지 않기 위함).
 *               국립국어원 우리말샘 등 큰 사전을 넣은 뒤에는 true를 권장합니다.
 */
class WordChainEngine(
    private val dict: WordDictionary,
    private val strict: Boolean = false,
    private val random: Random = Random.Default,
) {
    sealed interface Result {
        /**
         * 운전자 단어 인정. lunaWord가 null이면 달토끼가 이어갈 단어가 없어 짐.
         * inDictionary가 false면 사전에 없는 단어(관대 모드에서 인정)라 빠른 정답 보너스는 주지 않습니다.
         */
        data class Accepted(val userWord: String, val lunaWord: String?, val inDictionary: Boolean = true) : Result
        data class Invalid(val reason: Reason, val word: String, val expected: Set<Char>) : Result
    }

    enum class Reason { NOT_HANGUL, TOO_SHORT, WRONG_START, ALREADY_USED, UNKNOWN_WORD }

    private val used = mutableSetOf<String>()

    var lastWord: String? = null
        private set

    var userTurns = 0
        private set

    fun start(): String {
        used.clear()
        userTurns = 0
        val w = dict.randomStarter(random)
        used += w
        lastWord = w
        return w
    }

    fun expectedStarts(): Set<Char> = lastWord?.let { Hangul.startOptions(it.last()) } ?: emptySet()

    /** 지금 낼 수 있는 단어 예시 (시연용 추천 대답, 도움말) */
    fun hints(count: Int = 3): List<String> =
        dict.startingWith(expectedStarts())
            .filter { it !in used }
            .sortedByDescending { dict.continuationCount(it.last()) }
            .take(count * 2)
            .shuffled(random)
            .take(count)

    fun submit(raw: String): Result {
        val expected = expectedStarts()
        val word = normalize(raw, expected) ?: return Result.Invalid(Reason.NOT_HANGUL, raw, expected)
        if (word.length < 2) return Result.Invalid(Reason.TOO_SHORT, word, expected)
        if (expected.isNotEmpty() && word.first() !in expected) return Result.Invalid(Reason.WRONG_START, word, expected)
        if (word in used) return Result.Invalid(Reason.ALREADY_USED, word, expected)
        val known = dict.contains(word)
        if (strict && !known) return Result.Invalid(Reason.UNKNOWN_WORD, word, expected)
        // 관대 모드라도 "가가", "나나나"처럼 같은 글자만 반복한 말은 단어로 보지 않음(점수 쌓기 방지)
        if (!known && word.toSet().size == 1) return Result.Invalid(Reason.UNKNOWN_WORD, word, expected)

        used += word
        lastWord = word
        userTurns++

        val reply = pickReply(word.last())
        if (reply != null) {
            used += reply
            lastWord = reply
        }
        return Result.Accepted(word, reply, inDictionary = known)
    }

    /**
     * 음성 인식 결과에서 단어만 뽑습니다.
     * - "기차요." → "기차" (사전에 있을 때)
     * - "음… 기차", "어 기차" → "기차" (앞에 붙은 군말은 건너뜀)
     * 여러 낱말이 있으면 이어야 할 글자로 시작하는 낱말을 먼저 고르고, 없으면 두 글자 이상인 첫 낱말을 씁니다.
     */
    private fun normalize(raw: String, expected: Set<Char>): String? {
        val tokens = raw.trim().split(Regex("\\s+"))
            .map { t -> t.filter { Hangul.isSyllable(it) } }
            .filter { it.isNotEmpty() && it !in FILLERS }
            .map { stripPoliteSuffix(it) }
        if (tokens.isEmpty()) {
            // 군말만 있었던 경우("음")는 짧은 말로 처리
            return raw.filter { Hangul.isSyllable(it) }.takeIf { it.isNotEmpty() }
        }
        return tokens.firstOrNull { it.length >= 2 && (expected.isEmpty() || it.first() in expected) }
            ?: tokens.firstOrNull { it.length >= 2 }
            ?: tokens.first()
    }

    private fun stripPoliteSuffix(word: String): String =
        if (!dict.contains(word) && word.length >= 3 && word.endsWith("요") && dict.contains(word.dropLast(1))) {
            word.dropLast(1)
        } else word

    private companion object {
        /** 단어 앞뒤에 흔히 붙는 군말 */
        val FILLERS = setOf("음", "어", "으음", "음음", "어어", "아", "그", "저", "에", "엄", "흠", "그러니까", "글쎄", "뭐지", "음냐")
    }

    /** 운전 중이니 쉽게: 다음 사람이 이어가기 쉬운 단어를 우선 고릅니다. */
    private fun pickReply(last: Char): String? {
        val candidates = dict.startingWith(Hangul.startOptions(last)).filter { it !in used }
        if (candidates.isEmpty()) return null
        val easy = candidates.filter { c -> dict.continuationCount(c.last()) >= 2 }
        return (easy.ifEmpty { candidates }).random(random)
    }
}
