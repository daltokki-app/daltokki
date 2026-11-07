package app.daltokki.drive.domain.fatigue

import kotlin.math.pow

/** 대답의 종류. 짧은 질문과 게임은 정상 응답 시간이 달라 따로 판정합니다. */
enum class ResponseKind {
    /** "응/아니", 가벼운 질문 */
    SIMPLE,
    /** 끝말잇기·퀴즈 (단어를 떠올리는 시간이 필요함) */
    GAME,
    /** 반응 게임 ("달!" → "빛!") — 생각 없이 바로 대답하는 단순 반응 */
    REACTION,
}

/**
 * 달토끼의 질문에 대한 운전자의 반응(응답 지연, 무응답)을 기록하고 "대화 반응 저하" 점수를 냅니다.
 *
 * - 짧은 질문: 개인 기준 응답 시간(또렷할 때 받은 대답)과 비교합니다.
 * - 게임: 게임 기준 응답 시간(기본 4초)과 비교하고, 시간 초과도 무응답보다 약하게 봅니다.
 *   단어를 떠올리느라 걸린 시간을 졸음으로 오해하지 않기 위함입니다.
 * - 반응 게임: 반응 기준 시간(기본 1.2초)과 비교합니다. 개인 기준(짧은 질문)에는 넣지 않습니다.
 *
 * 기준 응답 속도는 또렷할 때(출발 인사, 또는 '좋아요' 단계에서 받은 짧은 대답) 정합니다.
 * 졸음이 의심될 때의 응답으로 기준을 잡으면 기준이 느려지는 문제가 있기 때문입니다.
 */
class ResponseTracker(private val cfg: FatigueConfig) {

    private class Event(val t: Long, val latencyMs: Long?, val kind: ResponseKind) // latencyMs null = 무응답

    private val events = ArrayDeque<Event>()
    private val baselineSamples = mutableListOf<Long>()
    /** 운전자별로 쌓아 둔 평소 대답 속도 (운행 시작 때 받음) */
    private val storedSamples = mutableListOf<Long>()

    val baselineLatencyMs: Long
        get() {
            val all = storedSamples + baselineSamples
            if (all.isEmpty()) return cfg.defaultBaselineLatencyMs
            val sorted = all.sorted()
            return sorted[sorted.size / 2].coerceIn(800L, 3_000L)
        }

    val hasPersonalBaseline: Boolean get() = storedSamples.isNotEmpty() || baselineSamples.isNotEmpty()

    /** 운행 시작: 운전자의 누적 대답 속도를 받고, 이번 운행 표본은 비움 */
    fun seed(samples: List<Long>) {
        storedSamples.clear()
        storedSamples += samples.takeLast(20)
        baselineSamples.clear()
    }

    /** 이번 운행에서 새로 잰 평소 대답 속도 (운행이 끝나면 운전자 기록에 더함) */
    val tripBaselineSamples: List<Long> get() = baselineSamples.toList()

    /** 이 대답이 "또렷할 때만큼 빠른" 대답인지 */
    fun isFast(latencyMs: Long, kind: ResponseKind): Boolean = when (kind) {
        ResponseKind.SIMPLE -> latencyMs <= baselineLatencyMs * 1.2
        ResponseKind.GAME -> latencyMs <= cfg.gameBaselineLatencyMs
        ResponseKind.REACTION -> latencyMs <= cfg.reactionBaselineLatencyMs
    }

    fun recordBaseline(latencyMs: Long) {
        if (baselineSamples.size < 5) baselineSamples += latencyMs
    }

    fun recordResponse(t: Long, latencyMs: Long, kind: ResponseKind = ResponseKind.SIMPLE) {
        events.addLast(Event(t, latencyMs, kind))
    }

    fun recordMiss(t: Long, kind: ResponseKind = ResponseKind.SIMPLE) {
        events.addLast(Event(t, null, kind))
    }

    fun clearRecent() = events.clear()

    private fun prune(now: Long) {
        while (events.isNotEmpty() && now - events.first().t > cfg.dialogWindowMs) events.removeFirst()
    }

    /** 최근(기본 20분) 짧은 질문에 대한 마지막 대답의 응답 시간. 없으면 null. (판단 근거 표시용) */
    fun lastSimpleLatency(now: Long): Long? {
        prune(now)
        return events.lastOrNull { it.kind == ResponseKind.SIMPLE && it.latencyMs != null }?.latencyMs
    }

    /** 최근(기본 20분) 무응답 횟수 (판단 근거 표시용) */
    fun recentMisses(now: Long): Int {
        prune(now)
        return events.count { it.latencyMs == null }
    }

    /**
     * @return (반응 저하 0~1, 신뢰도 0~1). 최근 기록이 없으면 null.
     */
    fun impairment(now: Long): Pair<Float, Float>? {
        prune(now)
        if (events.isEmpty()) return null
        val simpleBase = baselineLatencyMs.toFloat()
        val gameBase = cfg.gameBaselineLatencyMs.toFloat()
        val reactionBase = cfg.reactionBaselineLatencyMs.toFloat()
        var wSum = 0f
        var vSum = 0f
        for (e in events) {
            val age = (now - e.t).coerceAtLeast(0L)
            val w = 0.5.pow(age.toDouble() / cfg.dialogHalfLifeMs).toFloat()
            val base = when (e.kind) {
                ResponseKind.GAME -> gameBase
                ResponseKind.REACTION -> reactionBase
                ResponseKind.SIMPLE -> simpleBase
            }
            val score = when {
                e.latencyMs == null && e.kind == ResponseKind.GAME -> cfg.gameMissImpairment
                e.latencyMs == null && e.kind == ResponseKind.REACTION -> cfg.reactionMissImpairment
                e.latencyMs == null -> 1f
                else -> ((e.latencyMs - 1.3f * base) / (2f * base)).coerceIn(0f, 1f)
            }
            wSum += w
            vSum += w * score
        }
        if (wSum <= 0f) return null
        return (vSum / wSum) to wSum.coerceIn(0f, 1f)
    }
}
