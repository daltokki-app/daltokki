package app.daltokki.drive.domain.driver

import app.daltokki.drive.domain.dialog.DriverMemory
import app.daltokki.drive.domain.dialog.TalkLevel
import java.util.Locale

/**
 * 운전자 한 명의 기록. 차 한 대를 여러 명이 쓸 때 섞이지 않게 운전자별로 따로 둡니다.
 * - 운전 습관(핸들) 누적 기준선, 대답 속도 기준
 * - 말 수, 어젯밤 잠
 * - 휴게소 도장, 누적 각성 점수(달 레벨), 배지
 */
data class DriverProfile(
    val id: String,
    val name: String,
    /** 화면 색 번호 (0~5) */
    val colorIndex: Int,
    val habit: HabitHistory = HabitHistory(),
    /** 평소 대답 속도 표본 (최근 [MAX_LATENCY]개) */
    val latencySamples: List<Long> = emptyList(),
    val talkLevel: TalkLevel = TalkLevel.MIN,
    val sleepHours: Float? = null,
    val sleepAnsweredAt: Long = 0L,
    /** StampBook.serialize 글 */
    val stamps: String = "",
    val lifetimePoints: Int = 0,
    val badges: Set<String> = emptySet(),
    val createdMs: Long = 0L,
    /** 시연용 임시 운전자 (저장하지 않음) */
    val demo: Boolean = false,
    /** 기억하는 대화: 자주 가는 곳·좋아하는 음식 등 (운전자별, 기기 안에만) */
    val memory: Map<DriverMemory.Slot, String> = emptyMap(),
    /** 최근 운행 기록 (안전 운행 연속 기록·주간 리포트) */
    val trips: List<TripSummary> = emptyList(),
    /** 달토끼 꾸미기: 모은 아이템, 지금 착용 중인 것 */
    val items: Set<String> = emptySet(),
    val wearing: String? = null,
    /** 아직 안 연 달빛 선물 상자 수 */
    val gifts: Int = 0,
    /** 개인 최고 기록 (운행 결과 '신기록') */
    val bestTripPoints: Int = 0,
    val bestCombo: Int = 0,
) {
    val isGuest: Boolean get() = id == GUEST_ID

    /** 기록을 남기는 운전자인지 (게스트·시연은 저장하지 않음) */
    val persistent: Boolean get() = !isGuest && !demo

    fun addLatency(samples: List<Long>): DriverProfile =
        if (samples.isEmpty()) this else copy(latencySamples = (latencySamples + samples).takeLast(MAX_LATENCY))

    fun addTrip(t: TripSummary): DriverProfile = copy(trips = TripLog.add(trips, t))

    fun remember(updates: Map<DriverMemory.Slot, String>): DriverProfile =
        if (updates.isEmpty()) this else copy(memory = memory + updates)

    /** 운전 습관·대답 속도 기준을 지우고 처음부터 다시 배움 */
    fun resetHabit(): DriverProfile = copy(habit = HabitHistory(), latencySamples = emptyList())

    companion object {
        const val GUEST_ID = "guest"
        const val MAX_LATENCY = 20
        const val COLORS = 6

        /** 게스트: 판단은 기본값으로, 습관·도장은 저장하지 않음 (렌터카·대리운전) */
        fun guest(talkLevel: TalkLevel = TalkLevel.MIN) = DriverProfile(GUEST_ID, "게스트", -1, talkLevel = talkLevel)
    }
}

/** 운전자 목록과 지금 운전자 */
data class DriverRoster(val drivers: List<DriverProfile>, val currentId: String) {

    val current: DriverProfile
        get() = drivers.firstOrNull { it.id == currentId } ?: if (currentId == DriverProfile.GUEST_ID) DriverProfile.guest() else drivers.first()

    fun select(id: String): DriverRoster =
        if (id == DriverProfile.GUEST_ID || drivers.any { it.id == id }) copy(currentId = id) else this

    val canAdd: Boolean get() = drivers.size < MAX_DRIVERS

    /** 새 운전자 추가 후 그 운전자를 고름. 이름이 비었거나 꽉 찼으면 그대로 */
    fun add(name: String, id: String, now: Long): DriverRoster {
        val n = name.trim().take(MAX_NAME)
        if (n.isEmpty() || !canAdd) return this
        val used = drivers.map { it.colorIndex }.toSet()
        val color = (0 until DriverProfile.COLORS).firstOrNull { it !in used } ?: (drivers.size % DriverProfile.COLORS)
        val talk = current.talkLevel
        return DriverRoster(drivers + DriverProfile(id, n, color, talkLevel = talk, createdMs = now), id)
    }

    fun rename(id: String, name: String): DriverRoster {
        val n = name.trim().take(MAX_NAME)
        if (n.isEmpty()) return this
        return update(id) { it.copy(name = n) }
    }

    /** 삭제 (마지막 한 명은 지울 수 없음). 지금 운전자를 지우면 첫 번째로 */
    fun remove(id: String): DriverRoster {
        if (drivers.size <= 1 || drivers.none { it.id == id }) return this
        val left = drivers.filter { it.id != id }
        return DriverRoster(left, if (currentId == id) left.first().id else currentId)
    }

    fun update(id: String, change: (DriverProfile) -> DriverProfile): DriverRoster =
        copy(drivers = drivers.map { if (it.id == id) change(it) else it })

    companion object {
        const val MAX_DRIVERS = 6
        const val MAX_NAME = 8

        private val DEFAULT_NAME = Regex("운전자 \\d+")

        /** 기본 이름: "운전자 1", "운전자 2" … */
        fun defaultName(n: Int) = "운전자 $n"

        /** 기본 이름인지 (기본 이름이면 달토끼가 말할 때 이름을 부르지 않음: "운전자 일, …"이 어색해서) */
        fun isDefaultName(name: String): Boolean = DEFAULT_NAME.matches(name.trim())

        /** 아직 안 쓴 가장 작은 번호의 기본 이름 (운전자 1이 있으면 "운전자 2") */
        fun nextDefaultName(used: Collection<String>): String =
            generateSequence(1) { it + 1 }.map { defaultName(it) }.first { it !in used }

        /** 처음 쓸 때(또는 이전 버전 기록에서 옮길 때) 운전자 한 명 */
        fun initial(first: DriverProfile) = DriverRoster(listOf(first), first.id)
    }
}

/**
 * 운전자 목록 저장 글 (SharedPreferences에 문자열 하나로). JSON 라이브러리 없이 테스트할 수 있게 직접 씁니다.
 * 한 줄에 "키<TAB>값", 운전자마다 driver … end. 값의 \, 줄바꿈, 탭은 이스케이프.
 */
object DriverCodec {

    private const val VERSION = "drivers-v1"

    fun encode(roster: DriverRoster): String = buildString {
        line(VERSION)
        kv("current", roster.currentId)
        for (d in roster.drivers) {
            line("driver")
            kv("id", d.id)
            kv("name", d.name)
            kv("color", d.colorIndex.toString())
            kv("talk", d.talkLevel.name)
            kv("sleep", d.sleepHours?.let { String.format(Locale.ROOT, "%.2f", it) }.orEmpty())
            kv("sleepAt", d.sleepAnsweredAt.toString())
            kv("points", d.lifetimePoints.toString())
            kv("badges", d.badges.sorted().joinToString(","))
            kv("stamps", d.stamps)
            kv("latency", d.latencySamples.joinToString(","))
            kv("habitTrips", d.habit.trips.toString())
            kv(
                "habit",
                d.habit.samples.joinToString(";") {
                    "${it.band.name},${fmt(it.reversalRatePerMin)},${fmt(it.speedStdKmh)},${it.timeMs}"
                },
            )
            kv("created", d.createdMs.toString())
            kv("memory", DriverMemory.encode(d.memory))
            kv("trips", TripLog.encode(d.trips))
            kv("items", d.items.sorted().joinToString(","))
            kv("wearing", d.wearing.orEmpty())
            kv("gifts", d.gifts.toString())
            kv("bestPts", d.bestTripPoints.toString())
            kv("bestCombo", d.bestCombo.toString())
            line("end")
        }
    }

    /** 읽기. 형식이 틀리면 null (처음 쓰는 것으로 봄) */
    fun decode(text: String?): DriverRoster? {
        if (text.isNullOrBlank()) return null
        val lines = text.split('\n')
        if (lines.firstOrNull() != VERSION) return null
        var current: String? = null
        val drivers = mutableListOf<DriverProfile>()
        var fields: MutableMap<String, String>? = null
        for (raw in lines.drop(1)) {
            when {
                raw == "driver" -> fields = mutableMapOf()
                raw == "end" -> {
                    fields?.let { f -> profile(f)?.let { drivers += it } }
                    fields = null
                }
                raw.contains('\t') -> {
                    val key = raw.substringBefore('\t')
                    val value = unescape(raw.substringAfter('\t'))
                    val f = fields
                    if (f != null) f[key] = value else if (key == "current") current = value
                }
            }
        }
        if (drivers.isEmpty()) return null
        val cur = current ?: drivers.first().id
        return DriverRoster(drivers, if (cur == DriverProfile.GUEST_ID || drivers.any { it.id == cur }) cur else drivers.first().id)
    }

    private fun profile(f: Map<String, String>): DriverProfile? {
        val id = f["id"]?.takeIf { it.isNotEmpty() } ?: return null
        val habitSamples = f["habit"].orEmpty().split(';').mapNotNull { part ->
            val c = part.split(',')
            if (c.size < 4) return@mapNotNull null
            val band = SpeedBand.entries.firstOrNull { it.name == c[0] } ?: return@mapNotNull null
            HabitSample(band, c[1].toFloatOrNull() ?: return@mapNotNull null, c[2].toFloatOrNull() ?: return@mapNotNull null, c[3].toLongOrNull() ?: 0L)
        }
        return DriverProfile(
            id = id,
            name = f["name"].orEmpty().ifEmpty { "운전자" },
            colorIndex = f["color"]?.toIntOrNull() ?: 0,
            habit = HabitHistory(habitSamples, f["habitTrips"]?.toIntOrNull() ?: 0),
            latencySamples = f["latency"].orEmpty().split(',').mapNotNull { it.toLongOrNull() },
            talkLevel = TalkLevel.entries.firstOrNull { it.name == f["talk"] } ?: TalkLevel.MIN,
            sleepHours = f["sleep"]?.toFloatOrNull(),
            sleepAnsweredAt = f["sleepAt"]?.toLongOrNull() ?: 0L,
            stamps = f["stamps"].orEmpty(),
            lifetimePoints = f["points"]?.toIntOrNull() ?: 0,
            badges = f["badges"].orEmpty().split(',').filter { it.isNotEmpty() }.toSet(),
            createdMs = f["created"]?.toLongOrNull() ?: 0L,
            memory = DriverMemory.decode(f["memory"]),
            trips = TripLog.decode(f["trips"]),
            items = f["items"].orEmpty().split(',').filter { it.isNotEmpty() }.toSet(),
            wearing = f["wearing"]?.takeIf { it.isNotEmpty() },
            gifts = f["gifts"]?.toIntOrNull() ?: 0,
            bestTripPoints = f["bestPts"]?.toIntOrNull() ?: 0,
            bestCombo = f["bestCombo"]?.toIntOrNull() ?: 0,
        )
    }

    private fun fmt(v: Float) = String.format(Locale.ROOT, "%.3f", v)

    private fun StringBuilder.line(s: String) = append(s).append('\n')

    private fun StringBuilder.kv(k: String, v: String) = append(k).append('\t').append(escape(v)).append('\n')

    fun escape(s: String): String = buildString {
        for (ch in s) {
            when (ch) {
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\t' -> append("\\t")
                '\r' -> append("\\r")
                else -> append(ch)
            }
        }
    }

    fun unescape(s: String): String = buildString {
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            if (ch == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    'n' -> append('\n')
                    't' -> append('\t')
                    'r' -> append('\r')
                    '\\' -> append('\\')
                    else -> append(s[i + 1])
                }
                i += 2
            } else {
                append(ch)
                i++
            }
        }
    }
}
