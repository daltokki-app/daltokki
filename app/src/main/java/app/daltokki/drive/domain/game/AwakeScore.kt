package app.daltokki.drive.domain.game

/** 배지. pointsRequired가 있으면 누적 각성 점수로 받는 배지입니다. */
data class Badge(
    val id: String,
    val name: String,
    val description: String,
    val pointsRequired: Int? = null,
)

object Badges {
    val FIRST_WORD = Badge("first_word", "첫 끝말잇기", "끝말잇기 첫 정답")
    val COMBO5 = Badge("combo5", "5연속 정답", "끝말잇기·퀴즈 5연속 정답")
    val DAWN_GUARD = Badge("dawn_guard", "새벽 지킴이", "누적 각성 점수 1,500점", pointsRequired = 1_500)
    val REST_KEEPER = Badge("rest_keeper", "휴식 지킴이", "휴게소에서 10분 휴식 완료")
    val SAFE_DRIVER = Badge("safe_driver", "안전 드라이버", "누적 각성 점수 5,000점", pointsRequired = 5_000)

    // 반응 게임
    val LIGHTNING = Badge("lightning", "번개 반응", "한 운행에서 반응 게임 1.2초 안에 3번")

    // 휴게소 도장 깨기 (실제 휴게소·졸음쉼터에서 10분 쉬면 도장)
    val FIRST_STAMP = Badge("first_stamp", "첫 도장", "휴게소·졸음쉼터에서 쉬고 첫 도장")
    val STAMP_10 = Badge("stamp10", "도장 수집가", "서로 다른 10곳 도장")
    val SHELTER_3 = Badge("shelter3", "쉼터 탐험가", "졸음쉼터 3곳 도장")
    val ROUTE_5 = Badge("route5", "노선 탐방", "한 노선에서 5곳 도장")

    // 게임 다양화 (14차)
    val CHOSUNG = Badge("chosung", "초성 박사", "한 운행에서 초성 게임 3번 정답")
    val FOODIE = Badge("foodie", "휴게소 미식가", "휴게소 음식 퀴즈 첫 정답")
    val MEMORY = Badge("memory", "기억력 대장", "숫자 네 자리 따라 말하기 정답")
    val RIDDLE = Badge("riddle", "수수께끼 왕", "한 운행에서 수수께끼 2번 정답")
    val NAP = Badge("nap", "개운한 커피냅", "커피냅 뒤 반응이 빨라짐")

    // 안전 행동 (21차)
    val FRESH_DRIVE = Badge("fresh_drive", "상쾌한 운전", "한 운행에서 외기 전환과 온도 낮추기를 모두")
    val ON_TIME = Badge("on_time", "제때 쉬기", "권장 휴식 시간 전에 쉬어 감")

    val all = listOf(
        FIRST_WORD, COMBO5, LIGHTNING, REST_KEEPER, DAWN_GUARD, SAFE_DRIVER,
        FIRST_STAMP, STAMP_10, SHELTER_3, ROUTE_5,
        CHOSUNG, FOODIE, MEMORY, RIDDLE, NAP,
        FRESH_DRIVE, ON_TIME,
    )

    fun byId(id: String): Badge? = all.firstOrNull { it.id == id }
}

/** 점수 종류 (운행 리포트 내역: 게임 · 안전 행동 · 휴식 · 도장) */
enum class PointCategory(val label: String) { GAME("게임"), SAFETY("안전 행동"), REST("휴식"), STAMP("도장") }

/**
 * 졸음을 줄이는 행동 점수. 같은 행동은 [AwakeScore.SAFETY_COOLDOWN_MS](30분)에 한 번,
 * [oncePerTrip]이면 운행마다 한 번만 (버튼을 연타해 점수를 모을 수 없게).
 */
enum class SafetyAction(val points: Int, val label: String, val oncePerTrip: Boolean = false) {
    FRESH_AIR(30, "외기 전환"),
    /** 차가 못 바꿔서 음성 안내로 대신 (운전자는 승낙함) */
    FRESH_AIR_VOICE(10, "환기 승낙"),
    COOL_DOWN(30, "온도 낮추기"),
    COOL_DOWN_VOICE(10, "시원하게 승낙"),
    REST_ACCEPT(100, "휴게소 가기"),
    REST_ON_TIME(200, "제때 쉬기"),
    SELF_REPORT(50, "졸리다고 말해 줬어요", oncePerTrip = true),
    SLEEP_CHECK(20, "컨디션 체크", oncePerTrip = true),
}

/**
 * 게임 점수: 각성 점수, 콤보, 배지.
 *
 * 게임의 목표가 "졸음을 버티기"가 아니라 "깨어 있다가 제때 쉬기"가 되도록
 * 가장 큰 점수(+500)는 휴식 완료에 주고, 처음 가 본 휴게소·쉼터면 도장 +200을 더 줍니다.
 * 이번 운행 점수(tripPoints)와 누적 점수(lifetimePoints)를 따로 셉니다.
 */
class AwakeScore(
    lifetimePoints: Int = 0,
    earnedBadgeIds: Set<String> = emptySet(),
) {
    /**
     * @param combo 이번 정답까지 연속 정답 수 (게임 정답일 때만)
     * @param multiplier 콤보 배율 (2연속 ×1.5, 3연속 ×2, 5연속 ×3)
     * @param fast 빨리 맞혀서 번개 보너스를 받았는지
     */
    data class Award(
        val points: Int,
        val newBadges: List<Badge>,
        val combo: Int = 0,
        val multiplier: Float = 1f,
        val fast: Boolean = false,
    )

    var tripPoints = 0
        private set
    var combo = 0
        private set
    var bestCombo = 0
        private set
    var lifetimePoints = lifetimePoints
        private set
    /** 이번 운행에서 반응 게임을 기준(1.2초)보다 빨리 받은 횟수 */
    var fastReactions = 0
        private set

    private val earned = earnedBadgeIds.toMutableSet()
    val earnedIds: Set<String> get() = earned.toSet()

    /** 이번 운행 게임별 정답 수 (게임 배지용) */
    private val tripCorrect = HashMap<GameKind, Int>()

    /** 이번 운행 점수 내역 (운행 리포트: "게임 420 · 안전 행동 290 · 휴식 500 · 도장 200") */
    private val tripByCategory = HashMap<PointCategory, Int>()
    val tripBreakdown: Map<PointCategory, Int> get() = PointCategory.entries.associateWith { tripByCategory[it] ?: 0 }

    /** 달빛 조각: 운전 중 점수를 얻을 때마다 하나씩 모았다가 휴식을 마치면 한꺼번에 터짐(+10점씩) */
    var shards = 0
        private set
    /** 이번 운행 안전 행동 횟수 (운행 결과 ★2) */
    var tripSafetyActions = 0
        private set

    /** 휴식 완료: 모아 둔 달빛 조각을 터뜨림 (+[SHARD_POINTS]점씩). 조각이 없으면 points 0 */
    fun releaseShards(): Pair<Int, Award> {
        val n = shards
        shards = 0
        if (n == 0) return 0 to Award(0, emptyList())
        val pts = n * SHARD_POINTS
        return n to Award(pts, addPoints(pts, PointCategory.REST))
    }

    /** 달빛 선물 상자의 보너스 점수 */
    fun onGiftPoints(points: Int): Award = Award(points, addPoints(points, PointCategory.REST))

    /** 안전 행동: 종류별 마지막 시각, 이번 운행에 받은 종류 */
    private val safetyAt = HashMap<SafetyAction, Long>()
    private val safetyThisTrip = HashSet<SafetyAction>()

    fun startTrip() {
        tripPoints = 0
        combo = 0
        bestCombo = 0
        fastReactions = 0
        tripCorrect.clear()
        tripByCategory.clear()
        safetyThisTrip.clear()
        tripSafetyActions = 0
    }

    /**
     * 졸음을 줄이는 행동(외기 전환·온도 낮추기·휴게소 가기·제때 쉬기·졸림 신고·컨디션 체크) 점수.
     * 같은 행동은 30분에 한 번, 졸림 신고·컨디션 체크는 운행마다 한 번. 못 받으면 points 0.
     */
    fun onSafetyAction(action: SafetyAction, now: Long): Award {
        if (action.oncePerTrip && action in safetyThisTrip) return Award(0, emptyList())
        safetyAt[action]?.let { if (now - it < SAFETY_COOLDOWN_MS) return Award(0, emptyList()) }
        safetyAt[action] = now
        safetyThisTrip += action
        tripSafetyActions++
        shards++
        val badges = mutableListOf<Badge>()
        val fresh = SafetyAction.FRESH_AIR in safetyThisTrip || SafetyAction.FRESH_AIR_VOICE in safetyThisTrip
        val cool = SafetyAction.COOL_DOWN in safetyThisTrip || SafetyAction.COOL_DOWN_VOICE in safetyThisTrip
        if (fresh && cool) earn(Badges.FRESH_DRIVE)?.let { badges += it }
        if (action == SafetyAction.REST_ON_TIME) earn(Badges.ON_TIME)?.let { badges += it }
        badges += addPoints(action.points, PointCategory.SAFETY)
        return Award(action.points, badges)
    }

    /**
     * 게임별 정답 (점수는 [onCorrect]에서 따로). 게임 배지만 확인합니다.
     * @param detail 숫자 따라 말하기의 자릿수 등
     */
    fun onGameCorrect(game: GameKind, detail: Int = 0): List<Badge> {
        val n = (tripCorrect[game] ?: 0) + 1
        tripCorrect[game] = n
        val badge = when (game) {
            GameKind.CHOSUNG -> if (n >= 3) Badges.CHOSUNG else null
            GameKind.FOOD -> Badges.FOODIE
            GameKind.DIGITS -> if (detail >= 4) Badges.MEMORY else null
            GameKind.RIDDLE -> if (n >= 2) Badges.RIDDLE else null
            else -> null
        }
        return listOfNotNull(badge?.let { earn(it) })
    }

    /** 커피냅 뒤 반응 재측정: 빨라졌으면 +100점과 배지 */
    fun onNapRetest(faster: Boolean): Award {
        if (!faster) return Award(0, emptyList())
        val badges = mutableListOf<Badge>()
        earn(Badges.NAP)?.let { badges += it }
        badges += addPoints(NAP_POINTS, PointCategory.SAFETY)
        return Award(NAP_POINTS, badges)
    }

    /**
     * 게임 정답·반응 게임 성공. 연속으로 맞힐수록 콤보 배율로 점수가 커집니다
     * (1번 ×1 → 2연속 ×1.5 → 3연속 ×2 → 5연속부터 ×3). 빠르게 맞히면 번개 보너스 +30.
     * 틀리거나 시간이 지나면 콤보만 끊깁니다(점수는 깎지 않음).
     */
    fun onCorrect(fast: Boolean, isWordGame: Boolean, isReaction: Boolean = false): Award {
        combo++
        bestCombo = maxOf(bestCombo, combo)
        // 반응 게임은 한 판이 짧고 쉬워서 기본 점수를 낮게
        val base = if (isReaction) REACTION_BASE else CORRECT_BASE
        val mult = comboMultiplier(combo)
        shards++
        val points = Math.round(base * mult) + if (fast) FAST_BONUS else 0
        val badges = mutableListOf<Badge>()
        if (isWordGame) earn(Badges.FIRST_WORD)?.let { badges += it }
        if (combo >= 5) earn(Badges.COMBO5)?.let { badges += it }
        if (isReaction && fast) {
            fastReactions++
            if (fastReactions >= 3) earn(Badges.LIGHTNING)?.let { badges += it }
        }
        badges += addPoints(points, PointCategory.GAME)
        return Award(points, badges, combo = combo, multiplier = mult, fast = fast)
    }

    /**
     * 휴게소 도장. 처음 가 본 곳이면 +200점(휴식 +500점과 별도). 다시 간 곳은 점수 없이 방문 횟수만.
     * @param places 서로 다른 도장 수, @param shelters 그중 졸음쉼터 수, @param bestRoute 한 노선에서 받은 최대 도장 수
     */
    fun onStamp(isNew: Boolean, places: Int, shelters: Int, bestRoute: Int): Award {
        val badges = mutableListOf<Badge>()
        if (places >= 1) earn(Badges.FIRST_STAMP)?.let { badges += it }
        if (places >= 10) earn(Badges.STAMP_10)?.let { badges += it }
        if (shelters >= 3) earn(Badges.SHELTER_3)?.let { badges += it }
        if (bestRoute >= 5) earn(Badges.ROUTE_5)?.let { badges += it }
        val points = if (isNew) NEW_STAMP_POINTS else 0
        if (points > 0) badges += addPoints(points, PointCategory.STAMP)
        return Award(points, badges)
    }

    /** 오답·시간 초과: 콤보만 끊깁니다(점수는 깎지 않음). */
    fun onWrong() {
        combo = 0
    }

    /** 휴게소에서 10분 휴식 완료 */
    fun onRestCompleted(): Award {
        val badges = mutableListOf<Badge>()
        earn(Badges.REST_KEEPER)?.let { badges += it }
        badges += addPoints(REST_POINTS, PointCategory.REST)
        return Award(REST_POINTS, badges)
    }

    /** 아직 못 받은 점수 배지 중 가장 가까운 것과 남은 점수 */
    fun nextPointBadge(): Pair<Badge, Int>? =
        Badges.all
            .filter { it.pointsRequired != null && it.id !in earned }
            .minByOrNull { it.pointsRequired!! }
            ?.let { it to (it.pointsRequired!! - lifetimePoints).coerceAtLeast(0) }

    /** 다음 점수 배지까지 진행률(0~1). 모두 받았으면 1. */
    fun nextBadgeProgress(): Float {
        val next = nextPointBadge() ?: return 1f
        val required = next.first.pointsRequired!!
        val prev = Badges.all.mapNotNull { it.pointsRequired }.filter { it < required }.maxOrNull() ?: 0
        return ((lifetimePoints - prev).toFloat() / (required - prev)).coerceIn(0f, 1f)
    }

    fun isEarned(badge: Badge): Boolean = badge.id in earned

    private fun addPoints(points: Int, category: PointCategory): List<Badge> {
        tripByCategory[category] = (tripByCategory[category] ?: 0) + points
        tripPoints += points
        lifetimePoints += points
        return Badges.all
            .filter { b -> b.pointsRequired != null && lifetimePoints >= b.pointsRequired }
            .mapNotNull { earn(it) }
    }

    private fun earn(badge: Badge): Badge? = if (earned.add(badge.id)) badge else null

    companion object {
        const val CORRECT_BASE = 50
        const val REACTION_BASE = 30
        const val NEW_STAMP_POINTS = 200
        /** 콤보 배율: 1번 ×1, 2연속 ×1.5, 3~4연속 ×2, 5연속부터 ×3 */
        fun comboMultiplier(combo: Int): Float = when {
            combo >= 5 -> 3f
            combo >= 3 -> 2f
            combo == 2 -> 1.5f
            else -> 1f
        }
        const val FAST_BONUS = 30
        const val REST_POINTS = 500
        const val NAP_POINTS = 100
        const val SAFETY_COOLDOWN_MS = 30 * 60_000L
        const val SHARD_POINTS = 10
    }
}
