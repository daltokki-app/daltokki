package app.daltokki.drive.domain.fatigue

import app.daltokki.drive.domain.driver.HabitHistory
import app.daltokki.drive.domain.driver.TripHabit
import app.daltokki.drive.domain.model.BusyReason
import app.daltokki.drive.domain.model.DriveEvent
import app.daltokki.drive.domain.model.DriveState
import app.daltokki.drive.domain.model.DrivingFeatures
import app.daltokki.drive.domain.model.FatigueAssessment
import app.daltokki.drive.domain.model.FatigueLevel
import app.daltokki.drive.domain.model.Gear
import app.daltokki.drive.domain.model.VehicleSnapshot
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/**
 * 피로 추정의 중심. 차량 데이터와 대화 반응을 받아 FatigueAssessment를 만듭니다.
 * 안드로이드에 의존하지 않는 순수 Kotlin이라 단위 테스트로 검증할 수 있습니다.
 * 스레드 안전하지 않으므로 한 스레드에서만 호출하세요.
 */
class FatigueEngine(
    val cfg: FatigueConfig = FatigueConfig(),
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    private val extractor = SteeringFeatureExtractor(cfg)
    private val calibrator = BaselineCalibrator(cfg)
    private val contextCalc = ContextRiskCalculator()
    private val tracker = ResponseTracker(cfg)
    private val estimator = FatigueEstimator(cfg)
    private val stateMachine = DriveStateMachine(cfg)
    private val workload = WorkloadGate(cfg)
    /** 실내 CO₂ 추정 (보조 지표) */
    private val air = CabinAirModel()

    private val laneWarnings = ArrayDeque<Long>()
    private var laneSeen = false
    private var lastLaneFlag = false
    private var selfReportUntil = -1L
    private var lastT = -1L
    /** 충분히 쉬어서 피로가 초기화된 상태(다시 달리기 전까지) */
    private var freshAfterRest = false

    // 차량 운전자 주의 경고(DAW)
    private var lastDawFlag = false
    private var vehicleWarningUntil = -1L
    var totalVehicleWarnings = 0
        private set

    // 내기순환 연속 시간 (주행 중에만 셈)
    private var recircSince = -1L
    private var recircKnown = false

    // 각성 확인 보너스 (대화·게임으로 쌓고 시간이 지나면 사라짐)
    private var bonus = 0f
    private var bonusT = -1L

    var last: FatigueAssessment? = null
        private set

    /** 출발 전 컨디션: 어젯밤 잔 시간. 모르면 null (기본 2시간 간격) */
    var sleepHours: Float? = null
        private set

    fun setSleepHours(hours: Float?) {
        sleepHours = hours?.coerceIn(0f, 14f)
    }

    /** 운전자별 누적 습관 (다음 운행부터 적용) */
    class HabitSeed(val history: HabitHistory, val latencySamples: List<Long>)

    /** 지금 운전자의 누적 습관. 운행이 시작될 때(정차 → 출발) 기준선과 대답 속도 기준에 넣습니다. */
    var habitSeed: HabitSeed? = null

    /** 이번 운행 습관을 배우는 동안 졸음 신호가 없었는지 (졸린 날의 습관은 배우지 않음) */
    private var learningClean = true
    private var tripStartHour = -1
    private var tripHabitTaken = false

    /** 누적 습관으로 바로 판단하는 중인지 */
    val usingStoredHabit: Boolean get() = calibrator.usingStored

    /** 이번 운행에 쓰는 누적 습관의 운행 수 */
    val storedHabitTrips: Int get() = calibrator.storedTrips

    /** 이번 운행에서 새로 잰 평소 대답 속도 */
    val tripLatencySamples: List<Long> get() = tracker.tripBaselineSamples

    /** 이번 운행 출발 시각(시). 출발 전이면 -1 */
    val startHour: Int get() = tripStartHour

    /**
     * 이번 운행 습관이 완성됐으면 한 번만 돌려줌 (운전자 기록에 더할지는 HabitLearning이 판단).
     * 아직이거나 이미 가져갔으면 null.
     */
    fun takeTripHabit(): TripHabit? {
        if (tripHabitTaken) return null
        val overall = calibrator.tripBaseline ?: return null
        tripHabitTaken = true
        return TripHabit(calibrator.tripBands, overall, learningClean, tripStartHour, sleepHours, lastT)
    }

    private fun beginTrip(t: Long) {
        val seed = habitSeed
        calibrator.startTrip(seed?.history)
        tracker.seed(seed?.latencySamples.orEmpty())
        learningClean = true
        tripHabitTaken = false
        tripStartHour = Instant.ofEpochMilli(t).atZone(zone).hour
    }

    val totalLapses: Int get() = extractor.totalLapses
    val tripDrivingMs: Long get() = stateMachine.tripDrivingMs
    val parkStartMs: Long get() = stateMachine.parkStartMs
    val baselineLatencyMs: Long get() = tracker.baselineLatencyMs
    val baseline: BaselineCalibrator.Baseline? get() = calibrator.baseline

    fun onSnapshot(s: VehicleSnapshot): FatigueAssessment {
        val t = s.timestampMs
        lastT = t
        // 출발(정차 → D, 시속 3km 넘음): 운전자의 누적 습관으로 이번 운행 시작
        if (stateMachine.state == DriveState.PARKED && s.gear == Gear.DRIVE && s.speedKmh > 3f) beginTrip(t)
        extractor.add(s)
        val f = extractor.features()
        calibrator.update(t, extractor.lastValidDtMs, f)
        val busy = workload.update(s)

        val events = stateMachine.update(t, s.gear, s.speedKmh, calibrator.ready).toMutableList()
        if (events.any { it is DriveEvent.RestCompleted }) onRestReset()
        if (stateMachine.state.isDriving) freshAfterRest = false

        // 차선 이탈 경고·차로 유지 개입 (발생 순간만 셈)
        s.laneDepartureWarning?.let { flag ->
            laneSeen = true
            if (flag && !lastLaneFlag) laneWarnings.addLast(t)
            lastLaneFlag = flag
        }
        while (laneWarnings.isNotEmpty() && t - laneWarnings.first() > cfg.lapseWindowMs) laneWarnings.removeFirst()

        // 차량 운전자 주의 경고(DAW): 켜지는 순간 '위험' 하한을 일정 시간 유지
        s.driverAttentionWarning?.let { daw ->
            if (daw && !lastDawFlag && stateMachine.state.isDriving) {
                vehicleWarningUntil = t + cfg.vehicleWarningDurationMs
                totalVehicleWarnings++
                events += DriveEvent.VehicleAttentionWarning
            }
            lastDawFlag = daw
        }

        // 실내 CO₂ 추정 (주행 중·정차 중 모두 계산, 휴식 완료 때 초기화)
        air.update(t, s.recirculationOn, s.fanSpeed, s.occupants, stateMachine.state.isDriving)

        // 내기순환 연속 시간
        val recirc = s.recirculationOn
        if (recirc != null) recircKnown = true
        if (recirc == true && stateMachine.state.isDriving) {
            if (recircSince < 0) recircSince = t
        } else if (recirc == false || !stateMachine.state.isDriving) {
            recircSince = -1L
        }

        return assess(t, s.speedKmh, f, events, busy)
    }

    /** 현재 쌓여 있는 각성 확인 보너스(0~0.15) */
    val currentBonus: Float get() = bonus

    /**
     * 각성 확인 보너스를 더합니다. 빠르고 정확한 대답·게임 정답일 때만 호출하세요.
     * 최대치(기본 0.15)를 넘지 않고, 1분마다 일정량씩 사라집니다.
     * 화면의 졸음 위험도는 낮추지 않고, 말 걸기 판단에만 씁니다.
     */
    fun addBonus(amount: Float) {
        decayBonus(lastT)
        bonus = (bonus + amount).coerceIn(0f, cfg.maxAlertnessBonus)
    }

    private fun decayBonus(t: Long) {
        if (t < 0) return
        if (bonusT >= 0 && t > bonusT) {
            val minutes = (t - bonusT) / 60_000f
            bonus = (bonus - minutes * cfg.bonusDecayPerMin).coerceAtLeast(0f)
        }
        bonusT = t
    }

    // ── 대화 신호 ──
    fun recordBaselineResponse(latencyMs: Long) = tracker.recordBaseline(latencyMs)

    /**
     * 달토끼 질문에 대한 대답.
     * 짧은 질문에 '좋아요' 단계에서 답한 경우는 개인 기준 응답 시간으로도 씁니다(말 수 '최소·보통'에서도 기준이 잡히게).
     */
    fun recordResponse(latencyMs: Long, kind: ResponseKind = ResponseKind.SIMPLE) {
        // 단, 이미 개인 기준이 있으면 그보다 1.5배 넘게 느린 대답은 기준에 넣지 않음(기준이 느려지지 않게)
        val alert = (last?.level ?: FatigueLevel.NORMAL) == FatigueLevel.NORMAL
        val typical = !tracker.hasPersonalBaseline || latencyMs <= tracker.baselineLatencyMs * 1.5
        if (kind == ResponseKind.SIMPLE && alert && typical) {
            tracker.recordBaseline(latencyMs)
        }
        tracker.recordResponse(lastT, latencyMs, kind)
    }

    fun recordMiss(kind: ResponseKind = ResponseKind.SIMPLE) = tracker.recordMiss(lastT, kind)

    /** 또렷할 때만큼 빠른 대답인지 (짧은 질문: 개인 기준 × 1.2, 게임: 4초) */
    fun isFast(latencyMs: Long, kind: ResponseKind): Boolean = tracker.isFast(latencyMs, kind)

    /** 운전자가 "졸려"라고 말함 */
    fun reportSleepy() {
        selfReportUntil = lastT + cfg.selfReportDurationMs
    }

    /** 사용자가 직접 운행 종료 */
    fun endTrip(): FatigueAssessment? {
        val events = stateMachine.endTrip()
        if (events.isEmpty()) return null
        val prev = last ?: return null
        return prev.copy(driveState = stateMachine.state, events = events).also { last = it }
    }

    private fun assess(
        t: Long,
        speed: Float,
        f: DrivingFeatures,
        events: List<DriveEvent>,
        busy: BusyReason?,
    ): FatigueAssessment {
        val state = stateMachine.state
        val hour = Instant.ofEpochMilli(t).atZone(zone).hour
        val co2 = air.ppm
        val context = contextCalc.compute(
            t, hour, stateMachine.continuousDriveMs, f, speed, RestPlanner.sleepDebtRisk(sleepHours),
            airRisk = if (state.isDriving) CabinAirModel.risk(co2) else 0f,
        )
        val restInterval = RestPlanner.intervalMin(hour, sleepHours)
        val steering = steeringAnomaly(f)
        val lane = if (laneSeen) {
            when (laneWarnings.size) {
                0 -> 0f
                1 -> 0.5f
                else -> 1f
            }
        } else null
        val dialog = tracker.impairment(t)
        val selfReport = t < selfReportUntil
        val vehicleWarning = t < vehicleWarningUntil
        val driveMin = stateMachine.continuousDriveMs / 60_000f
        decayBonus(t)
        val strongSignal = f.lapseEventsRecent >= cfg.lockLapseCount

        val prev = last
        val result0 = if (state.isDriving || prev == null || freshAfterRest) {
            estimator.update(
                t, context, steering, lane, dialog, driveMin, selfReport, bonus, strongSignal, vehicleWarning,
                longDriveFloorMin = minOf(cfg.longDriveFloorMin, restInterval.toFloat()),
            )
        } else {
            FatigueEstimator.Result(
                prev.rawScore, prev.sensorScore, prev.score, prev.level, prev.bonusApplied, prev.bonusBlocked, prev.promptLevel,
            )
        }

        val result = result0
        // 습관을 배우는 동안 '좋아요'가 아니거나 급꺾음이 있었으면 이번 운행 습관은 누적에 넣지 않음
        if (state.isDriving && !calibrator.tripLearned && (result.level > FatigueLevel.NORMAL || f.lapseEventsRecent > 0)) {
            learningClean = false
        }

        return FatigueAssessment(
            timestampMs = t,
            driveState = state,
            score = result.score,
            sensorScore = result.sensor,
            bonus = bonus,
            bonusApplied = result.bonusApplied,
            bonusBlocked = result.bonusBlocked,
            rawScore = result.raw,
            level = result.level,
            contextRisk = context,
            steeringAnomaly = steering,
            laneRisk = lane,
            dialogImpairment = dialog?.first,
            continuousDriveMin = driveMin,
            baselineReady = calibrator.ready,
            calibrationProgress = calibrator.progress,
            features = f,
            selfReportActive = selfReport,
            events = events,
            promptLevel = result.promptLevel,
            vehicleWarningActive = vehicleWarning,
            recirculationMin = if (recircKnown && recircSince >= 0) (t - recircSince) / 60_000f else null,
            driverBusy = if (state.isDriving) busy else null,
            reasons = reasons(t, hour, f, driveMin, selfReport, vehicleWarning, restInterval),
            restIntervalMin = restInterval,
            restDueInMin = if (state.isDriving) restInterval - driveMin else null,
            sleepHours = sleepHours,
            co2Ppm = co2,
        ).also { last = it }
    }

    /**
     * 판단 근거 (설명 가능한 판단). 중요한 것부터 최대 3개.
     * 화면에는 '주의' 이상일 때 "근거 · 핸들 무조작 후 급꺾음 2회(5분) · 대답이 평소보다 2.1배 느림"처럼 보여줍니다.
     */
    private fun reasons(
        t: Long,
        hour: Int,
        f: DrivingFeatures,
        driveMin: Float,
        selfReport: Boolean,
        vehicleWarning: Boolean,
        restInterval: Int,
    ): List<String> {
        val list = ArrayList<Pair<Int, String>>()
        if (vehicleWarning) list += 100 to "차량 주의 경고"
        if (selfReport) list += 90 to "졸리다고 말함"
        if (f.lapseEventsRecent > 0) list += (80 + f.lapseEventsRecent) to "핸들 무조작 후 급꺾음 ${f.lapseEventsRecent}회(5분)"
        val misses = tracker.recentMisses(t)
        if (misses > 0) list += (75 + misses) to "무응답 ${misses}회"
        tracker.lastSimpleLatency(t)?.let { latency ->
            val ratio = latency.toFloat() / tracker.baselineLatencyMs
            if (ratio >= 1.5f) {
                val text = if (tracker.hasPersonalBaseline) {
                    "대답이 평소보다 ${"%.1f".format(Locale.US, ratio)}배 느림"
                } else {
                    "대답 ${"%.1f".format(Locale.US, latency / 1000f)}초"
                }
                list += 70 to text
            }
        }
        if (laneWarnings.isNotEmpty()) list += 60 to "차로 이탈 ${laneWarnings.size}회(5분)"
        val base = calibrator.baselineFor(f.meanSpeedKmh)
        val rate = f.reversalRatePerMin
        if (base != null && rate != null) {
            val pct = (rate / base.reversalRatePerMin * 100).toInt()
            if (pct < 75) list += 50 to "핸들 미세 보정 평소의 $pct%"
        }
        if (driveMin >= 60f) {
            val h = (driveMin / 60).toInt()
            val m = (driveMin % 60).toInt()
            list += (40 + (driveMin / 10).toInt()) to (if (h > 0) "연속 운전 ${h}시간 ${m}분" else "연속 운전 ${m}분")
        }
        // 권장 휴식 간격이 기본(2시간)보다 짧은데 지났으면 (새벽·수면 부족)
        if (restInterval < RestPlanner.DEFAULT_INTERVAL_MIN && driveMin >= restInterval) {
            list += 58 to "권장 휴식 간격 ${restInterval}분 지남"
        }
        RestPlanner.sleepReason(sleepHours)?.let { list += 45 to it }
        air.ppm?.takeIf { it >= CabinAirModel.REASON_PPM }?.let { list += 48 to "실내 CO₂ 추정 ${"%,d".format(Locale.US, it.toInt() / 100 * 100)}ppm" }
        when (hour) {
            in 2..5 -> list += 35 to "새벽 졸음 시간대"
            14, 15 -> list += 25 to "오후 졸음 시간대"
        }
        return list.sortedByDescending { it.first }.take(3).map { it.second }
    }

    /**
     * 핸들 조작 이상도.
     * - 기준선 전: 급꺾음만 반영(절반 비중)
     * - 기준선 후: 반전 빈도 감소 35% + 급꺾음 50% + 속도 편차 증가 15%
     */
    private fun steeringAnomaly(f: DrivingFeatures): Float? {
        if (!f.steeringAvailable) return null
        val lapse = when (f.lapseEventsRecent) {
            0 -> 0f
            1 -> 0.4f
            2 -> 0.7f
            else -> 1f
        }
        val rate = f.reversalRatePerMin
        if (rate == null && lapse == 0f) return null // 고속 직진 구간이 아니라 판단 불가

        val b = calibrator.baselineFor(f.meanSpeedKmh) ?: return lapse * 0.5f
        val reversalDrop = if (rate != null) ((1f - rate / b.reversalRatePerMin) / 0.6f).coerceIn(0f, 1f) else 0f
        val speedVar = f.speedStdKmh?.let { ((it / b.speedStdKmh - 1f) / 1.5f).coerceIn(0f, 1f) } ?: 0f
        return (0.35f * reversalDrop + 0.5f * lapse + 0.15f * speedVar).coerceIn(0f, 1f)
    }

    private fun onRestReset() {
        freshAfterRest = true
        air.reset()
        bonus = 0f
        tracker.clearRecent()
        laneWarnings.clear()
        extractor.clearRecentLapses()
        estimator.reset()
        contextCalc.reset()
        selfReportUntil = -1L
        vehicleWarningUntil = -1L
    }

    /** 현재 운행 상태 */
    val driveState: DriveState get() = stateMachine.state
}
