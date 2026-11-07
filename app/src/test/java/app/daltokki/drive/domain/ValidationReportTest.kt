package app.daltokki.drive.domain

import app.daltokki.drive.domain.fatigue.FatigueEngine
import app.daltokki.drive.domain.fatigue.RestPlanner
import app.daltokki.drive.domain.model.DriveEvent
import app.daltokki.drive.domain.model.FatigueLevel
import app.daltokki.drive.domain.sim.DriveScenario
import app.daltokki.drive.domain.sim.ScenarioGenerator
import app.daltokki.drive.domain.sim.Scenarios
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale
import java.util.Random
import kotlin.math.exp

/**
 * 검증 리포트 자동 생성 (기획서·PT용 숫자).
 *
 * 시나리오를 운전자마다 다른 습관(핸들 미세 보정 진폭 ±20%, 주기 ±15%, 속도 ±5km/h)과
 * 다른 난수로 여러 번 돌려, 오탐률·감지 시점·차량 경고 대비 선행 시간을 셉니다.
 * 결과: app/build/reports/luna-validation/validation_report.md (+ 테스트 로그에 출력)
 *
 * 실행: gradlew.bat testDebugUnitTest --tests "*ValidationReportTest*"
 * 횟수 바꾸기: -Dluna.validation.runs=50 (기본 20)
 *
 * 주의: 실제 운전자 데이터가 아니라 시뮬레이션(가정한 운전 패턴)에서의 엔진 동작 확인입니다.
 */
class ValidationReportTest {

    private val runs = System.getProperty("luna.validation.runs")?.toIntOrNull() ?: 20

    private class RunResult(
        /** 단계별 처음 도달 시각(운행 시작부터 분). 도달 못 하면 없음 */
        val firstReach: Map<FatigueLevel, Float>,
        /** 구간별 (구간 시작 분, 구간 안에서 처음 도달한 단계별 분) */
        val phaseStartMin: Map<String, Float>,
        val phaseFirst: Map<String, Map<FatigueLevel, Float>>,
        val phaseMax: Map<String, FatigueLevel>,
        val phaseEnd: Map<String, FatigueLevel>,
        val dawMin: Float?,
        val events: List<DriveEvent>,
    )

    /** 운전자마다 다른 습관으로 바꾼 시나리오 */
    private fun variant(sc: DriveScenario, rnd: Random): DriveScenario {
        val amp = 0.8f + 0.4f * rnd.nextFloat()
        val freq = 0.85f + 0.3f * rnd.nextFloat()
        val dv = -5f + 10f * rnd.nextFloat()
        return sc.copy(
            phases = sc.phases.map { p ->
                p.copy(
                    steerAmpDeg = p.steerAmpDeg * amp,
                    steerFreqHz = p.steerFreqHz * freq,
                    speedKmh = if (p.speedKmh > 0f) p.speedKmh + dv else 0f,
                )
            },
        )
    }

    private fun simulate(sc: DriveScenario, seed: Long, sleep: Float? = null): RunResult {
        val start = LocalDate.of(2026, 11, 3).atTime(sc.startHour, sc.startMinute).toInstant(ZoneOffset.UTC).toEpochMilli()
        val gen = ScenarioGenerator(sc, start, seed = seed)
        val engine = FatigueEngine(zone = ZoneOffset.UTC).also { it.setSleepHours(sleep) }
        val first = HashMap<FatigueLevel, Float>()
        val phaseStart = LinkedHashMap<String, Float>()
        val phaseFirst = HashMap<String, HashMap<FatigueLevel, Float>>()
        val phaseMax = HashMap<String, FatigueLevel>()
        val phaseEnd = HashMap<String, FatigueLevel>()
        val events = ArrayList<DriveEvent>()
        var daw: Float? = null
        while (!gen.isFinished) {
            val label = gen.currentPhase().label
            val minute = gen.elapsedMs / 60_000f
            phaseStart.putIfAbsent(label, minute)
            val a = engine.onSnapshot(gen.next())
            events += a.events
            if (DriveEvent.VehicleAttentionWarning in a.events && daw == null) daw = minute
            if (a.driveState.isDriving) {
                for (lv in FatigueLevel.entries) {
                    if (lv == FatigueLevel.NORMAL || a.level < lv) continue
                    first.putIfAbsent(lv, minute)
                    phaseFirst.getOrPut(label) { HashMap() }.putIfAbsent(lv, minute)
                }
            }
            phaseMax[label] = maxOf(phaseMax[label] ?: FatigueLevel.NORMAL, a.level)
            phaseEnd[label] = a.level
        }
        return RunResult(first, phaseStart, phaseFirst, phaseMax, phaseEnd, daw, events)
    }

    private fun median(xs: List<Float>): Float? {
        if (xs.isEmpty()) return null
        val s = xs.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2f
    }

    private fun pct(n: Int, total: Int) = if (total == 0) "-" else String.format(Locale.ROOT, "%.0f%% (%d/%d)", n * 100.0 / total, n, total)
    private fun min1(v: Float?) = v?.let { String.format(Locale.ROOT, "%.1f분", it) } ?: "-"

    @Test
    fun generateValidationReport() {
        val sb = StringBuilder()
        sb.appendLine("# 졸음 판단 검증 리포트 (시뮬레이션)")
        sb.appendLine()
        sb.appendLine("- 시나리오마다 운전자 습관(핸들 미세 보정 진폭 ±20%, 주기 ±15%, 속도 ±5km/h)과 난수를 바꿔 **${runs}회씩** 실행")
        sb.appendLine("- 대화·게임 없이 **차량 데이터만** 사용 (핸들 각도·속도·기어·차량 경고)")
        sb.appendLine("- 실제 운전자 데이터가 아니라 가정한 운전 패턴에서의 엔진 동작 확인입니다. 실차 검증은 다음 단계입니다.")
        sb.appendLine()

        // ── 1. 정상 주행 오탐 ──
        val day = (1..runs).map { i -> simulate(variant(Scenarios.dayNormal, Random(1_000L + i)), 1_000L + i) }
        val dayCaution = day.count { FatigueLevel.CAUTION in it.firstReach }
        val dayWarning = day.count { FatigueLevel.WARNING in it.firstReach }
        sb.appendLine("## 1. 정상 주행에서 괜히 경고하지 않는가 (오전 국도 40분, 커브·차선 변경 포함)")
        sb.appendLine()
        sb.appendLine("| 항목 | 결과 |")
        sb.appendLine("|---|---|")
        sb.appendLine("| '주의' 이상이 한 번이라도 뜬 비율 | ${pct(dayCaution, runs)} |")
        sb.appendLine("| '경고' 이상이 한 번이라도 뜬 비율 (오탐) | ${pct(dayWarning, runs)} |")
        sb.appendLine("| 오탐 없이 끝난 비율 | ${pct(runs - dayWarning, runs)} |")
        sb.appendLine()

        // ── 2. 새벽 졸음 감지 ──
        val night = (1..runs).map { i -> simulate(variant(Scenarios.nightHighway, Random(2_000L + i)), 2_000L + i) }
        val drowsy = "졸음 운전"
        val fatigue = "피로 누적"
        val warnedInDrowsy = night.count { (it.phaseMax[drowsy] ?: FatigueLevel.NORMAL) >= FatigueLevel.WARNING }
        // 차량 운전자 주의 경고(DAW)는 '졸음 운전' 구간 시작 6분 뒤. 그보다 먼저 '경고'를 띄웠는지
        val leads = night.mapNotNull { r ->
            val daw = r.dawMin ?: return@mapNotNull null
            val firstWarn = (r.phaseFirst[drowsy]?.get(FatigueLevel.WARNING)) ?: (r.phaseFirst[fatigue]?.get(FatigueLevel.WARNING))
            firstWarn?.let { daw - it }
        }
        val beforeDaw = leads.count { it > 0f }
        val cautionInFatigue = night.mapNotNull { r ->
            val s = r.phaseStartMin[fatigue] ?: return@mapNotNull null
            r.phaseFirst[fatigue]?.get(FatigueLevel.CAUTION)?.let { it - s }
        }
        val resetAfterRest = night.count { it.phaseEnd["휴식 후 주행"] == FatigueLevel.NORMAL }
        val stayedCalmEarly = night.count { (it.phaseMax["고속도로 · 또렷함"] ?: FatigueLevel.NORMAL) <= FatigueLevel.CAUTION }
        sb.appendLine("## 2. 졸음을 알아채는가 (새벽 2시 40분 고속도로)")
        sb.appendLine()
        sb.appendLine("| 항목 | 결과 |")
        sb.appendLine("|---|---|")
        sb.appendLine("| 또렷한 구간에서 '경고' 없음 | ${pct(stayedCalmEarly, runs)} |")
        sb.appendLine("| '피로 누적' 구간 시작 후 '주의'까지 (중앙값) | ${min1(median(cautionInFatigue))} (${cautionInFatigue.size}/$runs 회 감지) |")
        sb.appendLine("| '졸음 운전' 구간에서 '경고' 이상 감지 | ${pct(warnedInDrowsy, runs)} |")
        sb.appendLine("| **차량 주의 경고(DAW)보다 먼저 '경고'** | ${pct(beforeDaw, leads.size.coerceAtLeast(1))} |")
        sb.appendLine("| 차량 경고보다 앞선 시간 (중앙값) | ${min1(median(leads.filter { it > 0f }))} |")
        sb.appendLine("| 10분 휴식 후 다시 '좋아요'로 돌아옴 | ${pct(resetAfterRest, runs)} |")
        sb.appendLine()

        // ── 3. 장거리 · 졸음 예보 ──
        val longRuns = (runs / 2).coerceAtLeast(3)
        fun firstCautionDriveMin(sleep: Float?): List<Float> = (1..longRuns).mapNotNull { i ->
            simulate(variant(Scenarios.longTrip, Random(3_000L + i)), 3_000L + i, sleep).firstReach[FatigueLevel.CAUTION]
        }
        val longNormal = firstCautionDriveMin(null)
        val longShort = firstCautionDriveMin(4.5f)
        sb.appendLine("## 3. 오래 달리면 쉬라고 하는가 (오후 1시, 2시간 넘게 쉬지 않음 · 운전 습관은 정상)")
        sb.appendLine()
        sb.appendLine("| 출발 전 컨디션 | 권장 휴식 간격 | '주의'(휴식 권유)까지 (중앙값) |")
        sb.appendLine("|---|---|---|")
        sb.appendLine("| 수면 모름 | ${RestPlanner.intervalMin(13, null)}분 | ${min1(median(longNormal))} |")
        sb.appendLine("| 어젯밤 4시간 반 | ${RestPlanner.intervalMin(13, 4.5f)}분 | ${min1(median(longShort))} |")
        sb.appendLine()

        // ── 4. 반응 게임 판정 (가정 분포) ──
        val sessions = 2_000
        val rnd = Random(4_242L)
        fun session(medianMs: Double, sigma: Double, lapseProb: Double): Boolean {
            // 3번 중 놓침이 있거나 평균이 1.8초보다 느리면 '쉬어가자' (DialogCoordinator와 같은 규칙)
            var hits = 0
            var total = 0.0
            repeat(3) {
                if (rnd.nextDouble() < lapseProb) return@repeat
                hits++
                total += medianMs * exp(sigma * rnd.nextGaussian())
            }
            return hits < 3 || total / hits > 1_800.0
        }
        val alertFlag = (1..sessions).count { session(900.0, 0.25, 0.01) }
        val drowsyFlag = (1..sessions).count { session(1_500.0, 0.35, 0.15) }
        sb.appendLine("## 4. 반응 게임 판정 (가정한 반응 시간 분포로 규칙 동작 확인)")
        sb.appendLine()
        sb.appendLine("- 규칙: 3번 중 하나라도 놓치거나 평균이 1.8초보다 느리면 휴게소를 권함")
        sb.appendLine("- 가정: 또렷함 = 음성 반응 중앙값 0.9초·놓침 1%, 졸림 = 중앙값 1.5초·놓침 15% (PVT 연구의 '반응 지연·놓침 증가' 경향을 음성 반응으로 옮긴 값)")
        sb.appendLine()
        sb.appendLine("| 운전자 상태 | 휴식 권유 비율 |")
        sb.appendLine("|---|---|")
        sb.appendLine("| 또렷함 (권유하면 오탐) | ${pct(alertFlag, sessions)} |")
        sb.appendLine("| 졸림 (권유해야 맞음) | ${pct(drowsyFlag, sessions)} |")
        sb.appendLine()

        // ── 5. 권장 휴식 간격 표 ──
        sb.appendLine("## 5. 졸음 예보: 권장 휴식 간격 (RestPlanner)")
        sb.appendLine()
        sb.appendLine("| 어젯밤 수면 | 낮 출발 | 새벽(0~5시) |")
        sb.appendLine("|---|---|---|")
        for ((label, h) in listOf("모름" to null, "7시간 이상" to 7.5f, "5~6시간" to 5.5f, "4~5시간" to 4.5f, "4시간 미만" to 3f)) {
            sb.appendLine("| $label | ${RestPlanner.intervalMin(13, h)}분 | ${RestPlanner.intervalMin(3, h)}분 |")
        }
        sb.appendLine()

        val text = sb.toString()
        println(text)
        val dir = File("build/reports/luna-validation").apply { mkdirs() }
        val out = File(dir, "validation_report.md")
        out.writeText(text)
        println("검증 리포트: ${out.absolutePath}")
        assertTrue(out.exists() && out.length() > 0)
    }
}
