package app.daltokki.drive.domain.dialog

/**
 * 기억하는 대화 (규칙 기반, 기기 안에만 저장, 운전자별).
 * 운전자가 한 말에서 정해진 칸만 뽑아 두었다가 다음 운행의 인사·휴게소 안내에 꺼내 씁니다.
 * 클라우드 AI 없이도 "나를 기억하는 동승자"가 되고, 아는 내용을 묻는 질문은 바로 대답할 수 있어
 * 대답 속도 측정도 더 정확해집니다.
 */
object DriverMemory {

    /** 기억하는 칸 */
    enum class Slot(val label: String) {
        DESTINATION("자주 가는 곳"),
        FOOD("좋아하는 음식"),
        DRINK("좋아하는 음료"),
        MUSIC("좋아하는 음악"),
    }

    private val DRINKS = listOf("아메리카노", "라떼", "커피", "녹차", "콜라", "사이다", "주스", "에너지 음료", "에너지드링크", "보리차", "물")
    private val FOOD_WORDS = listOf(
        "국밥", "호두과자", "우동", "라면", "김밥", "돈가스", "떡볶이", "핫바", "어묵", "소떡소떡", "감자", "통감자", "비빔밥",
        "짜장면", "짬뽕", "김치찌개", "된장찌개", "치킨", "피자", "햄버거", "국수", "냉면", "만두", "핫도그", "고기", "불고기",
    )
    private val PLACE_STOP = setOf(
        "회사", "집", "어디", "거기", "여기", "오늘", "지금", "잠깐", "같이", "그냥", "다른", "데",
        "휴게소", "졸음쉼터", "쉼터", "화장실", "주유소", "충전소", "경로", "빨리", "천천히",
    )

    /**
     * 운전자 말에서 기억할 것 뽑기.
     * - "부산 가는 길이야", "대전 가", "강릉으로 가" → 자주 가는 곳
     * - "국밥 좋아해", "우동 맛있어" → 좋아하는 음식 (음식 낱말 목록에 있는 것만. "김밥 먹었어"처럼 먹은 것만으로는 바꾸지 않음)
     * - "커피 좋아", "아메리카노 마셨어" → 음료
     * - "아이유 노래 들어", "발라드 좋아" → 음악
     */
    fun extract(text: String): Map<Slot, String> {
        val t = text.trim()
        val out = LinkedHashMap<Slot, String>()
        Regex("""([가-힣]{2,6})(?:으로|로|에)?\s*(?:가는|가요|가$|갈|가고|가는\s*길|간다|가자)""").find(t)?.let { m ->
            val place = m.groupValues[1].removeSuffix("으로").removeSuffix("로").removeSuffix("에")
            if (place.length >= 2 && place !in PLACE_STOP && !place.endsWith("러")) out[Slot.DESTINATION] = place
        }
        FOOD_WORDS.sortedByDescending { it.length }.firstOrNull { t.contains(it) }?.let { food ->
            if ((t.contains("좋아") || t.contains("맛있")) && !t.contains("안 좋아") && !t.contains("싫어")) out[Slot.FOOD] = food
        }
        DRINKS.firstOrNull { t.contains(it) }?.let { d ->
            if (t.contains("좋아") || t.contains("마셨") || t.contains("마실") || t.contains("마시")) out[Slot.DRINK] = d
        }
        Regex("""([가-힣A-Za-z]{2,10})\s*(?:노래|음악)""").find(t)?.let { m ->
            val music = m.groupValues[1]
            if (music !in setOf("신나는", "조용한", "그", "이", "무슨", "좋은")) out[Slot.MUSIC] = music
        }
        return out
    }

    /** 저장 글 "DESTINATION=부산;FOOD=국밥" */
    fun encode(m: Map<Slot, String>): String =
        m.entries.joinToString(";") { "${it.key.name}=${it.value.replace(";", " ").replace("=", " ")}" }

    fun decode(text: String?): Map<Slot, String> =
        text.orEmpty().split(';').mapNotNull { part ->
            val k = part.substringBefore('=', "")
            val v = part.substringAfter('=', "").trim()
            val slot = Slot.entries.firstOrNull { it.name == k } ?: return@mapNotNull null
            if (v.isEmpty()) null else slot to v
        }.toMap()

    /** 음식 기억이 이 휴게소 대표 메뉴와 겹치는지 ("국밥" ↔ "명품한우국밥") */
    fun foodMatches(memoryFood: String?, menu: String?): Boolean =
        memoryFood != null && menu != null && (menu.contains(memoryFood) || memoryFood.contains(menu))
}
