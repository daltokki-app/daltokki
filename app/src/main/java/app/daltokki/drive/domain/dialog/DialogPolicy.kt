package app.daltokki.drive.domain.dialog

import app.daltokki.drive.domain.model.FatigueLevel

/** 달토끼가 하는 개입의 종류 */
enum class Intervention(val label: String) {
    GREETING("출발 인사"),
    CHECK_IN("말 걸기"),
    OFFER_GAME("휴식·놀이 제안"),
    ALERT("위험 경고"),
    SLEEPY("졸림 응대"),
    REST_GUIDE("휴식 안내"),
    REST_SUGGEST("휴게소 안내"),
    TRIP_REPORT("운행 리포트"),
    GAME("놀이"),
    SMALL_TALK("대화"),
    ACK("응답"),
    FRESH_AIR("환기 제안"),
    BOREDOM_GAME("말놀이 제안"),
    NAP("커피냅"),
    COOL_DOWN("온도 낮추기"),
}

/**
 * 말 수 설정. 성가신 앱이 되지 않도록 기본은 "최소"입니다.
 * 어느 단계든 위험 경고는 꺼지지 않고, "달토끼야"로 부르면 언제든 대화·게임을 할 수 있습니다.
 */
enum class TalkLevel(val label: String, val description: String) {
    MIN("최소", "위험할 때와 경고 단계 제안 한 번만 말해요"),
    NORMAL("보통", "출발 인사와 가끔 가벼운 한마디를 더해요"),
    CHATTY("수다", "먼저 말 걸고 게임도 먼저 제안해요");

    fun down(): TalkLevel = entries[(ordinal - 1).coerceAtLeast(0)]
    fun up(): TalkLevel = entries[(ordinal + 1).coerceAtMost(entries.lastIndex)]
}

/**
 * 언제 먼저 말을 걸지 정하는 규칙.
 * - 주의: 최소=말 없음(화면만), 보통=30분에 한 번, 수다=8분에 한 번
 * - 경고: "쉬어갈까, 끝말잇기로 잠 깨울까?" 한 번 (최소·보통 15분, 수다 5분 간격)
 * - 위험: 경고 한 문장 + 휴게소 안내 (최소·보통 10분, 수다 5분 간격, 다른 대화를 끊고 끼어듦)
 * - 시간당 상한: 위험 경고를 뺀 먼저 말 걸기는 최소 2회, 보통 4회, 수다 6회까지
 * 시간은 차량 데이터 시각(시뮬레이션에서는 가상 시각) 기준입니다.
 */
class DialogPolicy(
    /** 단계가 막 올라갔을 때, 이 시간 안에 같은 개입이 없었다면 쿨다운을 무시하고 바로 개입 */
    private val escalationGraceMs: Long = 60_000L,
) {
    data class Rules(
        val checkInCooldownMs: Long?,
        val offerCooldownMs: Long,
        val alertCooldownMs: Long,
        val hourlyCap: Int,
    )

    private val lastDone = mutableMapOf<Intervention, Long>()
    private val proactiveTimes = ArrayDeque<Long>()
    private var lastLevel = FatigueLevel.NORMAL

    fun reset() {
        lastDone.clear()
        proactiveTimes.clear()
        lastLevel = FatigueLevel.NORMAL
    }

    /**
     * @param busyWith 현재 진행 중인 개입(없으면 null)
     * @return 지금 시작할 개입. 없으면 null.
     */
    fun decide(now: Long, level: FatigueLevel, talk: TalkLevel, busyWith: Intervention?): Intervention? {
        val escalated = level.ordinal > lastLevel.ordinal
        lastLevel = level
        val r = rules(talk)

        val candidate = when (level) {
            FatigueLevel.NORMAL -> null
            FatigueLevel.CAUTION -> if (r.checkInCooldownMs != null) Intervention.CHECK_IN else null
            FatigueLevel.WARNING -> Intervention.OFFER_GAME
            FatigueLevel.DANGER -> Intervention.ALERT
        } ?: return null

        // 진행 중인 대화가 있으면, 위험 경고만 끼어들 수 있습니다(경고·졸림 응대 중에는 끼어들지 않음).
        if (busyWith != null) {
            val canPreempt = candidate == Intervention.ALERT &&
                busyWith != Intervention.ALERT && busyWith != Intervention.SLEEPY
            if (!canPreempt) return null
        }

        val cooldown = when (candidate) {
            Intervention.CHECK_IN -> r.checkInCooldownMs ?: return null
            Intervention.OFFER_GAME -> r.offerCooldownMs
            else -> r.alertCooldownMs
        }
        val last = lastDone[candidate]
        val ready = last == null ||
            now - last >= cooldown ||
            (escalated && now - last >= escalationGraceMs)
        if (!ready) return null

        // 시간당 상한 (위험 경고는 제외)
        if (candidate != Intervention.ALERT) {
            while (proactiveTimes.isNotEmpty() && now - proactiveTimes.first() >= HOUR_MS) proactiveTimes.removeFirst()
            if (proactiveTimes.size >= r.hourlyCap) return null
        }
        return candidate
    }

    /**
     * 환기 제안: 주행 중 실내 CO₂ 추정이 높거나(기본 1,200ppm) 내기순환이 오래(기본 20분) 이어지고 '주의' 이상일 때.
     * 최소는 화면 안내만(말하지 않음), 보통·수다는 30분에 한 번 묻습니다("외기로 바꿔 줄까?"). 시간당 상한에 포함됩니다.
     */
    fun decideFreshAir(
        now: Long,
        level: FatigueLevel,
        talk: TalkLevel,
        recirculationMin: Float?,
        busyWith: Intervention?,
        thresholdMin: Float = 20f,
        co2Ppm: Float? = null,
        co2Threshold: Float = 1_200f,
    ): Boolean {
        if (talk == TalkLevel.MIN || busyWith != null) return false
        val stuffy = (co2Ppm != null && co2Ppm >= co2Threshold) || (recirculationMin != null && recirculationMin >= thresholdMin)
        if (!stuffy) return false
        if (level < FatigueLevel.CAUTION || level == FatigueLevel.DANGER) return false
        val last = lastDone[Intervention.FRESH_AIR]
        if (last != null && now - last < FRESH_AIR_COOLDOWN_MS) return false
        while (proactiveTimes.isNotEmpty() && now - proactiveTimes.first() >= HOUR_MS) proactiveTimes.removeFirst()
        return proactiveTimes.size < rules(talk).hourlyCap
    }

    /**
     * 지루함 예방 말놀이: 고속도로를 단조롭게 오래(기본 60분) 달리면, 졸리기 전에 짧은 말놀이 한 판을 먼저 제안.
     * (단조로운 운전에서 가벼운 말 과제가 각성 유지에 도움이 된다는 연구: Atchley 외, 2014)
     * 보통·수다에서만, '좋아요'·'주의'일 때, 최근 게임 뒤 30분 이상 지났을 때. 시간당 상한에 포함됩니다.
     */
    fun decideBoredom(
        now: Long,
        level: FatigueLevel,
        talk: TalkLevel,
        continuousDriveMin: Float,
        highway: Boolean,
        lastGameAt: Long?,
        busyWith: Intervention?,
        thresholdMin: Float = 60f,
    ): Boolean {
        if (talk == TalkLevel.MIN || busyWith != null || !highway) return false
        if (level > FatigueLevel.CAUTION || continuousDriveMin < thresholdMin) return false
        if (lastGameAt != null && now - lastGameAt < BOREDOM_COOLDOWN_MS) return false
        val last = lastDone[Intervention.BOREDOM_GAME]
        if (last != null && now - last < BOREDOM_COOLDOWN_MS) return false
        while (proactiveTimes.isNotEmpty() && now - proactiveTimes.first() >= HOUR_MS) proactiveTimes.removeFirst()
        return proactiveTimes.size < rules(talk).hourlyCap
    }

    /**
     * 실내 온도 2도 낮추기 제안 (최소 차량 제어, 운전자 승낙 뒤에만).
     * '주의'·'경고'에서 먼저 말을 건 뒤에, 환기 제안 직후(2분)는 피하고, 한 번 하면 60분 쉼. 시간당 상한에 포함.
     * (시원한 공기가 잠깐 각성을 돕는다는 운전자 경험 연구가 있으나 효과는 짧아서 휴식 권유와 함께 씀)
     */
    fun decideCoolDown(now: Long, level: FatigueLevel, talk: TalkLevel, busyWith: Intervention?): Boolean {
        if (talk == TalkLevel.MIN || busyWith != null) return false
        if (level < FatigueLevel.CAUTION || level == FatigueLevel.DANGER) return false
        if (lastDone[Intervention.CHECK_IN] == null && lastDone[Intervention.OFFER_GAME] == null) return false
        lastDone[Intervention.COOL_DOWN]?.let { if (now - it < COOL_DOWN_COOLDOWN_MS) return false }
        lastDone[Intervention.FRESH_AIR]?.let { if (now - it < AFTER_FRESH_AIR_MS) return false }
        lastDone[Intervention.CHECK_IN]?.let { if (now - it < AFTER_FRESH_AIR_MS) return false }
        while (proactiveTimes.isNotEmpty() && now - proactiveTimes.first() >= HOUR_MS) proactiveTimes.removeFirst()
        return proactiveTimes.size < rules(talk).hourlyCap
    }

    fun markDone(kind: Intervention, now: Long) {
        lastDone[kind] = now
        if (kind == Intervention.CHECK_IN || kind == Intervention.OFFER_GAME || kind == Intervention.FRESH_AIR ||
            kind == Intervention.BOREDOM_GAME || kind == Intervention.COOL_DOWN
        ) {
            proactiveTimes.addLast(now)
        }
    }

    companion object {
        private const val MIN_MS = 60_000L
        private const val HOUR_MS = 60 * MIN_MS
        private const val FRESH_AIR_COOLDOWN_MS = 30 * MIN_MS
        private const val BOREDOM_COOLDOWN_MS = 30 * MIN_MS
        private const val COOL_DOWN_COOLDOWN_MS = 60 * MIN_MS
        private const val AFTER_FRESH_AIR_MS = 2 * MIN_MS

        fun rules(talk: TalkLevel): Rules = when (talk) {
            TalkLevel.MIN -> Rules(checkInCooldownMs = null, offerCooldownMs = 15 * MIN_MS, alertCooldownMs = 10 * MIN_MS, hourlyCap = 2)
            TalkLevel.NORMAL -> Rules(checkInCooldownMs = 30 * MIN_MS, offerCooldownMs = 15 * MIN_MS, alertCooldownMs = 10 * MIN_MS, hourlyCap = 4)
            TalkLevel.CHATTY -> Rules(checkInCooldownMs = 8 * MIN_MS, offerCooldownMs = 5 * MIN_MS, alertCooldownMs = 5 * MIN_MS, hourlyCap = 6)
        }
    }
}
