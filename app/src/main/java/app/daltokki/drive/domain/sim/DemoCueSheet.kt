package app.daltokki.drive.domain.sim

/**
 * 자동 시연 대사 큐시트: 녹화 영상에 음성·자막을 입힐 때 쓰는 "몇 초에 누가 무슨 말" 목록.
 * 시각 0은 자동 시연 버튼을 누른 순간(카운트다운 "3"이 화면에 뜬 순간)입니다.
 *
 * 만드는 파일 (adb pull로 꺼냄)
 * - 대사 SRT: "달토끼: …", "운전자: …" (영상 편집기에 자막으로 넣거나, 음성을 입힐 위치로 사용)
 * - 장면 자막 SRT: "② 멀쩡할 때는 조용히 — 차선 변경·제동 중에는 말 걸기를 미뤄요"
 * - TSV: 시작초·끝초·화자·문장 (엑셀로 열어 TTS 대본으로)
 *
 * 안드로이드에 의존하지 않는 순수 Kotlin.
 */
class DemoCueSheet(
    /** 문장을 읽는 데 걸리는 대략의 시간(ms). 다음 문장이 더 빨리 시작하면 거기서 끝냄 */
    private val durationOf: (String) -> Long = { paceMs(it) },
) {
    enum class Kind(val label: String) { TOKKI("달토끼"), DRIVER("운전자"), CAPTION("장면"), NOTE("기록") }

    data class Cue(val startMs: Long, val kind: Kind, val text: String, val endMs: Long? = null)

    private val cues = ArrayList<Cue>()

    val size: Int get() = synchronized(cues) { cues.size }

    fun clear() = synchronized(cues) { cues.clear() }

    fun add(startMs: Long, kind: Kind, text: String, endMs: Long? = null) = synchronized(cues) {
        cues += Cue(startMs.coerceAtLeast(0L), kind, text.trim(), endMs)
    }

    fun all(): List<Cue> = synchronized(cues) { cues.sortedBy { it.startMs } }

    /** 대사(달토끼·운전자)의 끝 시각: 읽는 시간, 다음 대사가 먼저 시작하면 그 직전까지 */
    private fun spoken(): List<Cue> {
        val lines = all().filter { it.kind == Kind.TOKKI || it.kind == Kind.DRIVER }
        return lines.mapIndexed { i, c ->
            val natural = c.endMs ?: (c.startMs + durationOf(c.text))
            val next = lines.getOrNull(i + 1)?.startMs
            val end = if (next != null && next - 50 < natural) maxOf(c.startMs + 300, next - 50) else natural
            c.copy(endMs = end)
        }
    }

    /** 대사 자막 (SRT) */
    fun dialogSrt(): String = srt(spoken().map { it.copy(text = "${it.kind.label}: ${it.text}") })

    /** 장면 자막 (SRT) */
    fun captionSrt(): String = srt(all().filter { it.kind == Kind.CAPTION }.map { it.copy(endMs = it.endMs ?: (it.startMs + CAPTION_MS)) })

    /** 표 (TSV): 시작초 · 끝초 · 화자 · 문장. 기록(효과음·시스템)도 포함 */
    fun tsv(): String {
        val spokenEnds = spoken().associateBy { it.startMs to it.text }
        val rows = all().map { c ->
            val end = c.endMs ?: spokenEnds[c.startMs to c.text]?.endMs ?: (c.startMs + if (c.kind == Kind.CAPTION) CAPTION_MS else 0L)
            listOf(sec(c.startMs), sec(end), c.kind.label, c.text.replace('\t', ' ').replace('\n', ' ')).joinToString("\t")
        }
        return (listOf("시작(초)\t끝(초)\t화자\t문장") + rows).joinToString("\n") + "\n"
    }

    companion object {
        /** 장면 자막 표시 시간 */
        const val CAPTION_MS = 4_000L

        /** 자동 시연에서 한 문장을 보여주는 최소 시간 = 0.5초 + 글자당 0.115초 (한국어 TTS 말 속도와 비슷), 최대 9초 */
        fun paceMs(text: String): Long = (500L + text.length * 115L).coerceAtMost(9_000L)

        private fun sec(ms: Long): String = String.format(java.util.Locale.ROOT, "%.1f", ms / 1000.0)

        fun srtTime(ms: Long): String {
            val t = ms.coerceAtLeast(0L)
            val h = t / 3_600_000
            val m = t / 60_000 % 60
            val s = t / 1_000 % 60
            val milli = t % 1_000
            return String.format(java.util.Locale.ROOT, "%02d:%02d:%02d,%03d", h, m, s, milli)
        }

        private fun srt(cues: List<Cue>): String = buildString {
            cues.forEachIndexed { i, c ->
                append(i + 1).append('\n')
                append(srtTime(c.startMs)).append(" --> ").append(srtTime(c.endMs ?: (c.startMs + 2_000))).append('\n')
                append(c.text).append("\n\n")
            }
        }
    }
}
