package app.daltokki.drive.data.settings

import android.content.Context
import app.daltokki.drive.domain.dialog.TalkLevel

/**
 * 앱 설정과 누적 기록 저장 (SharedPreferences).
 * - 말 수(최소/보통/수다)
 * - 데이터 공급원(시뮬레이션/실차) 마지막 선택
 * - 누적 각성 점수, 받은 배지
 * - 휴게소 도장 (StampBook.serialize 글)
 * - 출발 전 컨디션: 어젯밤 잔 시간과 답한 시각
 *
 * 12차부터 말 수·잠·점수·배지·도장은 운전자별([drivers], DriverCodec 글)로 저장합니다.
 * 위의 예전 값들은 처음 한 번 '운전자 1'로 옮길 때만 읽습니다.
 */
class SettingsStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("luna_drive", Context.MODE_PRIVATE)

    var talkLevel: TalkLevel
        get() = TalkLevel.entries.getOrNull(prefs.getInt(KEY_TALK, TalkLevel.MIN.ordinal)) ?: TalkLevel.MIN
        set(value) = prefs.edit().putInt(KEY_TALK, value.ordinal).apply()

    /** 마지막으로 고른 데이터 공급원 이름(SourceMode.name). 처음이면 null. */
    var sourceModeName: String?
        get() = prefs.getString(KEY_SOURCE, null)
        set(value) = prefs.edit().putString(KEY_SOURCE, value).apply()

    var lifetimePoints: Int
        get() = prefs.getInt(KEY_POINTS, 0)
        set(value) = prefs.edit().putInt(KEY_POINTS, value).apply()

    var badges: Set<String>
        get() = prefs.getStringSet(KEY_BADGES, emptySet())?.toSet() ?: emptySet()
        set(value) = prefs.edit().putStringSet(KEY_BADGES, value.toSet()).apply()

    var stamps: String
        get() = prefs.getString(KEY_STAMPS, "") ?: ""
        set(value) = prefs.edit().putString(KEY_STAMPS, value).apply()

    /** 어젯밤 잔 시간. 답한 적 없으면 null */
    var sleepHours: Float?
        get() = prefs.getFloat(KEY_SLEEP, -1f).takeIf { it >= 0f }
        set(value) = prefs.edit().putFloat(KEY_SLEEP, value ?: -1f).apply()

    /** 수면 시간을 답한 시각(System.currentTimeMillis) */
    var sleepAnsweredAt: Long
        get() = prefs.getLong(KEY_SLEEP_AT, 0L)
        set(value) = prefs.edit().putLong(KEY_SLEEP_AT, value).apply()

    /** 운전자 목록 (DriverCodec 글). 처음이면 빈 글 */
    var drivers: String
        get() = prefs.getString(KEY_DRIVERS, "") ?: ""
        set(value) = prefs.edit().putString(KEY_DRIVERS, value).apply()

    /** 처음 실행 때 운전자 고르기 창을 이미 한 번 띄웠는지 */
    var driverPrompted: Boolean
        get() = prefs.getBoolean(KEY_DRIVER_PROMPTED, false)
        set(value) = prefs.edit().putBoolean(KEY_DRIVER_PROMPTED, value).apply()

    /** 시뮬레이션에서도 실제 Pleos 내비에 경유지를 넣어 볼지 (기본 끔: 넣으면 내비 앱이 앞으로 나와 녹화 화면이 바뀜) */
    var realNaviInSim: Boolean
        get() = prefs.getBoolean(KEY_REAL_NAVI_SIM, false)
        set(value) = prefs.edit().putBoolean(KEY_REAL_NAVI_SIM, value).apply()

    /** 가벼운 연출 (느린 노트북 에뮬레이터·저사양 기기: 입자 절반, 빛 번짐 없음) */
    var lightEffects: Boolean
        get() = prefs.getBoolean(KEY_LIGHT_FX, false)
        set(value) = prefs.edit().putBoolean(KEY_LIGHT_FX, value).apply()

    private companion object {
        const val KEY_REAL_NAVI_SIM = "real_navi_in_sim"
        const val KEY_LIGHT_FX = "light_effects"
        const val KEY_DRIVER_PROMPTED = "driver_prompted"
        const val KEY_DRIVERS = "drivers_v1"
        const val KEY_TALK = "talk_level"
        const val KEY_SOURCE = "source_mode"
        const val KEY_POINTS = "lifetime_points"
        const val KEY_BADGES = "badges"
        const val KEY_STAMPS = "stamps"
        const val KEY_SLEEP = "sleep_hours"
        const val KEY_SLEEP_AT = "sleep_answered_at"
    }
}
