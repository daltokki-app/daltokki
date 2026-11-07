package app.daltokki.drive.domain.game

import app.daltokki.drive.domain.model.FatigueLevel
import kotlin.random.Random

/**
 * 운전 중 말로 하는 게임 종류.
 * 원칙: 눈을 감고도 할 수 있고, 한 번 대답이 5초 안팎이며, 대답 속도·정확도를 잴 수 있을 것.
 * (생각 부담이 큰 '거꾸로 세기' 같은 게임은 넣지 않음)
 */
enum class GameKind(val label: String) {
    WORD("끝말잇기"),
    CHOSUNG("초성 게임"),
    QUIZ("상식 퀴즈"),
    FOOD("휴게소 음식 퀴즈"),
    DIGITS("숫자 따라 말하기"),
    RIDDLE("수수께끼"),
    REACTION("반응 게임"),
}

/**
 * 어떤 게임을 할지 고름.
 * - '좋아요·주의': 끝말잇기·초성·퀴즈·휴게소 음식 퀴즈·수수께끼를 돌아가며 (최근 2번에 한 것은 피함)
 * - '경고': 생각할 게 적은 반응 게임·숫자 따라 말하기
 * - '위험': 게임하지 않음 (null → 휴식 안내)
 */
object GamePicker {
    private val RELAXED = listOf(GameKind.CHOSUNG, GameKind.WORD, GameKind.FOOD, GameKind.QUIZ, GameKind.RIDDLE)
    private val TIRED = listOf(GameKind.REACTION, GameKind.DIGITS)

    /** 이 피로 단계에서 고를 수 있는 게임 (게임 고르기 창: 나머지는 흐리게). 위험이면 없음 */
    fun allowed(level: FatigueLevel): List<GameKind> = when {
        level == FatigueLevel.DANGER -> emptyList()
        level >= FatigueLevel.WARNING -> TIRED
        else -> GameKind.entries.toList()
    }

    fun pick(level: FatigueLevel, recent: List<GameKind>, foodAvailable: Boolean, random: Random): GameKind? {
        if (level == FatigueLevel.DANGER) return null
        val pool = (if (level >= FatigueLevel.WARNING) TIRED else RELAXED).filter { it != GameKind.FOOD || foodAvailable }
        val fresh = pool.filter { it !in recent.takeLast(2) }
        return (fresh.ifEmpty { pool }).first().let { first ->
            // 같은 조건이면 목록 순서대로 돌아가되, 처음 한 번은 무작위 시작
            if (recent.isEmpty()) pool[random.nextInt(pool.size)] else first
        }
    }
}

/**
 * 초성 게임: "기역 치읓!" → "기차". 끝말잇기 내장 사전을 그대로 씁니다.
 * 사전에 없는 말이라도 초성이 맞는 두·세 글자 한글이면 정답으로 인정(확인이 안 되므로 빠른 정답 보너스는 없음).
 */
class ChosungGame(private val dictionary: WordDictionary, private val random: Random) {

    data class Question(val pattern: String, val spoken: String, val answers: List<String>)

    /** 두 글자 초성 문제. 정답이 2개 이상인 것을 먼저 (사전이 작으면 1개짜리도) */
    fun next(used: Set<String> = emptySet()): Question? {
        val all = dictionary.words.filter { it.length == 2 }.groupBy { pattern(it) }.filter { (p, _) -> p !in used }
        val byPattern = all.filter { (_, ws) -> ws.size >= 2 }.ifEmpty { all }
        if (byPattern.isEmpty()) return null
        val keys = byPattern.keys.sorted()
        val p = keys[random.nextInt(keys.size)]
        return Question(p, spokenOf(p), byPattern.getValue(p).sorted())
    }

    /** @return 맞으면 정답 단어, 아니면 null. 사전에 있는지는 [inDictionary]로 */
    fun check(q: Question, raw: String): String? {
        val words = raw.replace(Regex("[^가-힣 ]"), " ").split(' ').filter { it.isNotBlank() }
        return words.firstOrNull { w -> w.length >= q.pattern.length && pattern(w.take(q.pattern.length)) == q.pattern && w.length <= q.pattern.length + 1 }
            ?.take(q.pattern.length)
    }

    fun inDictionary(word: String): Boolean = dictionary.contains(word)

    companion object {
        private val CHO = listOf('ㄱ', 'ㄲ', 'ㄴ', 'ㄷ', 'ㄸ', 'ㄹ', 'ㅁ', 'ㅂ', 'ㅃ', 'ㅅ', 'ㅆ', 'ㅇ', 'ㅈ', 'ㅉ', 'ㅊ', 'ㅋ', 'ㅌ', 'ㅍ', 'ㅎ')
        private val NAMES = listOf(
            "기역", "쌍기역", "니은", "디귿", "쌍디귿", "리을", "미음", "비읍", "쌍비읍", "시옷",
            "쌍시옷", "이응", "지읒", "쌍지읒", "치읓", "키읔", "티읕", "피읖", "히읗",
        )

        fun pattern(word: String): String = word.map { c -> if (Hangul.isSyllable(c)) CHO[Hangul.cho(c)] else c }.joinToString("")

        /** "ㄱㅊ" → "기역 치읓" */
        fun spokenOf(pattern: String): String = pattern.map { c -> CHO.indexOf(c).takeIf { it >= 0 }?.let { NAMES[it] } ?: c.toString() }.joinToString(" ")
    }
}

/**
 * 숫자 따라 말하기: "3, 8, 5" → "삼 팔 오". 짧은 기억 검사라 '경고'에서도 부담이 적습니다.
 * 음성 인식이 숫자·한자어(삼, 팔)·고유어(셋, 여덟)로 줄 수 있어 모두 숫자로 바꿔 비교합니다.
 */
object DigitSpan {

    fun make(length: Int, random: Random): List<Int> {
        val out = mutableListOf<Int>()
        while (out.size < length) {
            val d = random.nextInt(1, 10)
            if (out.lastOrNull() != d) out += d
        }
        return out
    }

    fun spoken(digits: List<Int>): String = digits.joinToString(", ")

    private val NATIVE = linkedMapOf(
        "아홉" to 9, "여덟" to 8, "일곱" to 7, "여섯" to 6, "다섯" to 5, "하나" to 1, "둘" to 2, "셋" to 3, "넷" to 4,
    )
    private val SINO = mapOf('공' to 0, '영' to 0, '일' to 1, '이' to 2, '삼' to 3, '사' to 4, '오' to 5, '육' to 6, '칠' to 7, '팔' to 8, '구' to 9)

    /** "삼 팔 오", "385", "셋 여덟 다섯" → [3, 8, 5]. 숫자가 아닌 글자는 무시 */
    fun parse(raw: String): List<Int> {
        var t = raw
        val out = mutableListOf<Int>()
        var i = 0
        while (i < t.length) {
            val c = t[i]
            if (c.isDigit()) {
                out += c.digitToInt(); i++; continue
            }
            val native = NATIVE.entries.firstOrNull { t.startsWith(it.key, i) }
            if (native != null) {
                out += native.value; i += native.key.length; continue
            }
            SINO[c]?.let { out += it }
            i++
        }
        return out
    }

    fun isCorrect(expected: List<Int>, raw: String): Boolean = parse(raw) == expected
}

/** 휴게소 음식 퀴즈: 공공데이터 '휴게소 대표 음식'으로 3지선다 */
data class FoodQuestion(val stopName: String, val answer: String, val choices: List<String>) {
    val question: String get() = "${stopName} 대표 메뉴는 뭘까? ${choices.joinToString(", ")} 중에 골라 봐!"

    /** 정답 메뉴 이름의 두 글자 이상이 들어 있고, 다른 보기와 더 가깝지 않으면 정답 */
    fun isCorrect(raw: String): Boolean {
        val said = raw.replace(" ", "")
        fun score(option: String): Int {
            val o = option.replace(" ", "")
            var best = 0
            for (len in o.length downTo 2) {
                for (start in 0..o.length - len) if (said.contains(o.substring(start, start + len))) { best = len; break }
                if (best > 0) break
            }
            return best
        }
        val mine = score(answer)
        return mine >= 2 && choices.filter { it != answer }.all { score(it) < mine }
    }

    companion object {
        /**
         * @param stops (부르는 이름, 대표 음식) 목록. 앞쪽에 있는 휴게소(가까운·같은 노선)를 먼저 씁니다.
         */
        fun make(stops: List<Pair<String, String>>, count: Int, random: Random): List<FoodQuestion> {
            val valid = stops.filter { it.second.isNotBlank() && it.second.length <= 12 }.distinctBy { it.first }
            val foods = valid.map { it.second }.distinct()
            if (foods.size < 3) return emptyList()
            return valid.take(count).map { (name, food) ->
                val others = foods.filter { it != food && !it.contains(food) && !food.contains(it) }.shuffled(random).take(2)
                FoodQuestion(name, food, (others + food).shuffled(random))
            }.filter { it.choices.size == 3 }
        }
    }
}

/** 수수께끼 (짧게, 대답은 한 단어). 직접 쓴 문제와 오래 전해 내려오는 수수께끼 */
data class Riddle(val question: String, val answers: List<String>, val reveal: String) {
    fun isCorrect(raw: String): Boolean {
        val said = raw.replace(" ", "")
        return answers.any { said.contains(it.replace(" ", "")) }
    }
}

object Riddles {
    val all = listOf(
        Riddle("다리는 넷인데 걷지 못하는 것은?", listOf("책상", "의자", "식탁"), "책상"),
        Riddle("세수를 하면 할수록 더러워지는 것은?", listOf("물", "세숫물", "세면대"), "세숫물"),
        Riddle("들어갈 때는 하나인데 나올 때는 둘이 되는 것은?", listOf("바지", "양말"), "바지"),
        Riddle("밤에도 낮에도 늘 같은 자리에서 길을 비춰 주는 것은? 운전할 때 많이 봐.", listOf("가로등", "신호등"), "가로등"),
        Riddle("달릴수록 뒤로만 가는 것은? 차창 밖을 봐.", listOf("나무", "풍경", "가로수", "길"), "창밖 풍경"),
        Riddle("자동차가 가장 좋아하는 음식은?", listOf("기름", "휘발유", "전기", "카레"), "기름이나 전기"),
        Riddle("달토끼가 사는 곳은?", listOf("달"), "달"),
        Riddle("눈이 있어도 못 보는 것은?", listOf("태풍", "바늘", "감자"), "태풍의 눈"),
        Riddle("먹으면 먹을수록 커지는 것은?", listOf("나이", "배"), "나이"),
        Riddle("세상에서 가장 빠른 닭은?", listOf("후다닥", "후닥닭"), "후다닭"),
        Riddle("머리는 하나인데 이빨이 아주 많은 것은?", listOf("빗", "머리빗", "톱"), "빗"),
        Riddle("휴게소에서 빠지면 안 되는 두 글자는? 잠깐 쉬는 거야.", listOf("휴식", "쉼표"), "휴식"),
    )

    fun pick(count: Int, random: Random): List<Riddle> = all.shuffled(random).take(count)
}
