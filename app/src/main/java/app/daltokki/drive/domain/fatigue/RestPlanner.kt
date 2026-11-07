package app.daltokki.drive.domain.fatigue

import kotlin.math.min

/**
 * 출발 전 컨디션(어젯밤 잠)과 시간대로 "몇 분마다 쉬어야 하는지"를 정합니다. (졸음 예보)
 *
 * - 기본: 2시간마다 휴식 (한국도로공사·교통안전 캠페인의 "2시간마다 15분 휴식" 권고)
 * - 새벽(0~5시)에 달리는 중이면 최대 90분
 * - 어젯밤 잠: 6시간 미만 90분, 5시간 미만 75분, 4시간 미만 60분
 * 권장 간격이 지나면 엔진이 '주의' 하한을 걸어(기존 2시간 하한과 같은 방식) 달토끼가 휴식을 권합니다.
 *
 * 안드로이드에 의존하지 않는 순수 Kotlin.
 */
object RestPlanner {
    const val DEFAULT_INTERVAL_MIN = 120
    const val NIGHT_INTERVAL_MIN = 90
    /** 이보다 오래된 수면 답은 쓰지 않음 (다음 날로 넘어감) */
    const val SLEEP_ANSWER_VALID_MS = 16 * 60 * 60_000L

    /** 권장 휴식 간격(분) */
    fun intervalMin(hourOfDay: Int, sleepHours: Float?): Int {
        var m = DEFAULT_INTERVAL_MIN
        if (hourOfDay in 0..5) m = min(m, NIGHT_INTERVAL_MIN)
        if (sleepHours != null) {
            m = when {
                sleepHours < 4f -> min(m, 60)
                sleepHours < 5f -> min(m, 75)
                sleepHours < 6f -> min(m, 90)
                else -> m
            }
        }
        return m
    }

    /** 수면 부족을 상황 위험도(0~1)에 더할 값 */
    fun sleepDebtRisk(sleepHours: Float?): Float = when {
        sleepHours == null -> 0f
        sleepHours < 4f -> 0.25f
        sleepHours < 5f -> 0.15f
        sleepHours < 6f -> 0.07f
        else -> 0f
    }

    /** 이유 설명용: "수면 5시간"처럼 짧게 (6시간 이상이면 null) */
    fun sleepReason(sleepHours: Float?): String? {
        if (sleepHours == null || sleepHours >= 6f) return null
        val h = sleepHours.toInt()
        return if (sleepHours - h >= 0.5f) "수면 ${h}시간 반" else "수면 ${h}시간"
    }

    private val KOREAN_NUMBERS = linkedMapOf(
        "열두" to 12, "열한" to 11, "열" to 10, "아홉" to 9, "여덟" to 8, "일곱" to 7, "여섯" to 6,
        "다섯" to 5, "네" to 4, "넷" to 4, "세" to 3, "셋" to 3, "두" to 2, "둘" to 2, "한" to 1, "하나" to 1,
    )

    /**
     * "5시간", "다섯 시간 반", "6.5", "한 네 시간쯤", "거의 못 잤어" → 시간. 못 알아들으면 null.
     * 0~14시간으로 제한합니다.
     */
    fun parseSleepHours(text: String): Float? {
        val t = text.replace(" ", "")
        if (t.contains("밤샜") || t.contains("밤새웠") || t.contains("한숨도")) return 0f
        // "4시간밖에 못 잤어"처럼 숫자가 있으면 숫자를 먼저 씀
        val vague = if (t.contains("못잤") || t.contains("거의안잤") || t.contains("조금잤")) 3f else null
        val half = t.contains("반")
        Regex("""(\d+(?:\.\d+)?)""").find(t)?.let { m ->
            val v = m.groupValues[1].toFloatOrNull() ?: return null
            return (v + if (half && v % 1f == 0f) 0.5f else 0f).coerceIn(0f, 14f)
        }
        // 한글 숫자는 "시간"이 있을 때만 ("네"는 대답 '네'일 수 있음). "시간" 바로 앞 숫자를 씀 ("한 네 시간" → 네)
        val source = t.substringBefore("시간", missingDelimiterValue = "")
        if (source.isEmpty()) return vague
        val found = KOREAN_NUMBERS.entries
            .mapNotNull { (word, n) -> source.lastIndexOf(word).takeIf { it >= 0 }?.let { idx -> Triple(idx + word.length, word.length, n) } }
            .maxWithOrNull(compareBy<Triple<Int, Int, Int>> { it.first }.thenBy { it.second })
            ?: return vague
        return (found.third + if (half) 0.5f else 0f).coerceIn(0f, 14f)
    }
}
