package app.daltokki.drive.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.daltokki.drive.domain.dialog.TalkLevel
import app.daltokki.drive.domain.sim.DriveScenario
import app.daltokki.drive.domain.sim.Scenarios
import app.daltokki.drive.engine.CopilotUiState
import app.daltokki.drive.engine.SourceMode
import app.daltokki.drive.engine.Speaker
import app.daltokki.drive.engine.TranscriptLine
import app.daltokki.drive.ui.components.FatigueGraph
import app.daltokki.drive.ui.theme.LunaColors

/**
 * 데모 패널 (PT 시연·개발용).
 * 맨 위: 자동 시연(녹화용) · 수동 시연(새벽 졸음 / 오전 정상 / 2시간 연속) + 일시정지·처음부터·종료.
 * 아래: 시나리오 재생, 배속, 피로 점수 그래프, 내부 지표, 화면으로 운전자 대답 입력.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DemoPanel(
    state: CopilotUiState,
    transcript: List<TranscriptLine>,
    suggestions: List<String>,
    talkLevel: TalkLevel,
    onTalkLevel: (TalkLevel) -> Unit,
    ttsStatus: String,
    sttStatus: String,
    llmName: String,
    onSource: (SourceMode) -> Unit,
    onScenario: (DriveScenario) -> Unit,
    onSpeed: (Float) -> Unit,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onRestart: () -> Unit,
    onEndTrip: () -> Unit,
    onSend: (String) -> Unit,
    onLaneChange: () -> Unit,
    onBraking: () -> Unit,
    onGleo: (String) -> Unit,
    modifier: Modifier = Modifier,
    onDemo: (DriveScenario) -> Unit = {},
    onCallTest: () -> Unit = {},
    onAutoDemo: (DriveScenario) -> Unit = {},
    onRealNavi: (Boolean) -> Unit = {},
    onLightEffects: (Boolean) -> Unit = {},
) {
    var input by remember { mutableStateOf("") }
    val a = state.assessment

    Column(
        modifier
            .fillMaxHeight()
            .background(LunaColors.Surface)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("데모 패널", color = LunaColors.Moon, fontSize = 24.sp, fontWeight = FontWeight.Bold)

        // ── 시험 설정 ──
        // 가벼운 연출: 느린 노트북 에뮬레이터에서 입자 절반·빛 번짐 없음
        // 실제 내비 연동 시험: 켜면 시뮬레이션에서도 Pleos 내비에 경유지를 넣음 (내비 앱이 앞으로 나와 화면이 바뀔 수 있음, 자동 시연 중에는 항상 끔)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = state.lightEffects, onClick = { onLightEffects(!state.lightEffects) },
                label = { Text(if (state.lightEffects) "가벼운 연출 켜짐" else "가벼운 연출", fontSize = 15.sp) },
            )
            if (state.vehicleSupported && state.pleosIncluded) {
                FilterChip(
                    selected = state.realNaviInSim, onClick = { onRealNavi(!state.realNaviInSim) },
                    label = { Text(if (state.realNaviInSim) "실제 내비 연동 시험 켜짐" else "실제 내비 연동 시험", fontSize = 15.sp) },
                )
            }
        }

        // ── 자동 시연: 운전자 대답을 대본대로 자동으로 (녹화용, 데모 패널은 숨겨짐) ──
        Label("자동 시연 (녹화용 · 운전자 대답 자동 · 대사 큐시트 저장)")
        DemoButton("자동 시연 · 새벽 졸음", primary = true, modifier = Modifier.fillMaxWidth()) { onAutoDemo(Scenarios.nightHighway) }
        // ── 수동 시연: 누르면 시뮬레이션 · 30배속 · 말 수 '수다'(저장 안 함)로 바로 시작 ──
        Label("수동 시연 (마이크·추천 대답 버튼으로 대답)")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            DemoButton("새벽 졸음", primary = false, modifier = Modifier.weight(1f)) { onDemo(Scenarios.nightHighway) }
            DemoButton("오전 정상", primary = false, modifier = Modifier.weight(1f)) { onDemo(Scenarios.dayNormal) }
            DemoButton("2시간 연속", primary = false, modifier = Modifier.weight(1f)) { onDemo(Scenarios.longTrip) }
        }
        if (state.running && state.sourceMode == SourceMode.SIMULATION) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = onPause, modifier = Modifier.weight(1f).height(52.dp)) {
                    Text(if (state.paused) "▶ 계속" else "⏸ 일시정지", fontSize = 16.sp, maxLines = 1)
                }
                OutlinedButton(onClick = onRestart, modifier = Modifier.weight(1f).height(52.dp)) {
                    Text("↺ 처음부터", fontSize = 16.sp, maxLines = 1)
                }
                OutlinedButton(onClick = onEndTrip, modifier = Modifier.weight(1f).height(52.dp)) {
                    Text("■ 종료", fontSize = 16.sp, maxLines = 1)
                }
            }
            Text(
                "${state.scenario.title}${if (state.phaseLabel.isNotEmpty()) " · ${state.phaseLabel} · ${state.clock}" else ""}",
                color = LunaColors.TextSecondary, fontSize = 15.sp,
            )
            if (state.progress > 0f) LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
        }
        HorizontalDivider(color = LunaColors.SurfaceHigh)
        // 달토끼가 대답을 기다리는 동안에는 맨 위에 추천 대답을 보여줍니다 (대답 제한 시간 8~10초).
        if (suggestions.isNotEmpty()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(LunaColors.SurfaceHigh)
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Label("🎙 달토끼가 대답을 기다려요 · 누르면 운전자가 말한 것으로 처리")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    suggestions.forEach { q ->
                        Button(
                            onClick = { onSend(q) },
                            colors = ButtonDefaults.buttonColors(containerColor = LunaColors.Context),
                        ) { Text(q, fontSize = 16.sp, color = LunaColors.Background) }
                    }
                }
            }
        }

        Label("말 수 (설정)")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TalkLevel.entries.forEach { t ->
                FilterChip(selected = talkLevel == t, onClick = { onTalkLevel(t) }, label = { Text(t.label, fontSize = 16.sp) })
            }
        }
        Label("데이터")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // 실차는 차량(Android Automotive)에서만. 휴대폰에서는 시뮬레이션만 보여줍니다.
            SourceMode.entries.filter { it != SourceMode.VEHICLE || state.vehicleSupported }.forEach { m ->
                FilterChip(selected = state.sourceMode == m, onClick = { onSource(m) }, label = { Text(m.label, fontSize = 16.sp) })
            }
        }
        if (!state.vehicleSupported) {
            Text("이 기기는 차량이 아니라서 시뮬레이션으로 동작해요 (휴대폰 테스트 모드)", color = LunaColors.TextSecondary, fontSize = 15.sp)
        }
        Info("Pleos SDK", if (state.pleosIncluded) "빌드에 포함 (Vehicle 2.3.2 · NaviHelper 3.0.5)" else "미포함 빌드 (pleosSdk=false)")
        if (state.sourceMode == SourceMode.SIMULATION) {
            Label("시나리오")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Scenarios.all.forEach { sc ->
                    FilterChip(selected = state.scenario.id == sc.id, onClick = { onScenario(sc) }, label = { Text(sc.title, fontSize = 16.sp) })
                }
            }
            Text(state.scenario.description, color = LunaColors.TextSecondary, fontSize = 15.sp)
            Label("배속 (달토끼가 말할 땐 자동 1배속)")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1f, 10f, 30f, 60f, 120f).forEach { sp ->
                    FilterChip(selected = state.speed == sp, onClick = { onSpeed(sp) }, label = { Text("${sp.toInt()}x", fontSize = 16.sp) })
                }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!state.running) {
                Button(onClick = onStart) { Text("▶ 시작", fontSize = 16.sp) }
            } else if (state.sourceMode == SourceMode.SIMULATION) {
                Button(onClick = onPause) { Text(if (state.paused) "▶ 계속" else "⏸ 일시정지", fontSize = 16.sp) }
            }
            OutlinedButton(onClick = onRestart) { Text("↺ 처음부터", fontSize = 16.sp) }
            OutlinedButton(onClick = onEndTrip) { Text("■ 운행 종료", fontSize = 16.sp) }
        }
        if (state.sourceMode == SourceMode.SIMULATION && state.running) {
            Label("바쁜 순간·통화 시험 (이때는 달토끼가 말을 걸지 않음)")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onLaneChange) { Text("차선 변경 5초", fontSize = 15.sp) }
                OutlinedButton(onClick = onBraking) { Text("브레이크 3초", fontSize = 15.sp) }
                OutlinedButton(onClick = onCallTest) { Text("통화 중 15초", fontSize = 15.sp) }
            }
        }
        Label("Gleo 음성비서 호출 시험 (schema.json 함수)")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onGleo("START_WORD_GAME") }) { Text("끝말잇기", fontSize = 15.sp) }
            OutlinedButton(onClick = { onGleo("REPORT_DROWSINESS") }) { Text("졸려", fontSize = 15.sp) }
            OutlinedButton(onClick = { onGleo("ADD_REST_STOP") }) { Text("휴게소 경유지", fontSize = 15.sp) }
        }
        Info("상태", state.sourceStatus)
        if (state.phaseLabel.isNotEmpty()) Info("구간", "${state.phaseLabel} · ${state.clock} · ${"%.0f".format(state.effectiveSpeed)}x")

        Info("게임", "각성 점수 ${"%,d".format(state.game.tripPoints)} · ${state.game.combo} 콤보 · 누적 ${"%,d".format(state.game.lifetimePoints)}")
        Info("도장", "${state.game.stampPlaces}/${state.game.stampTotal}곳 (졸음쉼터 ${state.game.shelterStamps}) · 시나리오 휴게소: ${state.restArea?.name ?: "-"}")
        HorizontalDivider(color = LunaColors.SurfaceHigh)
        Label("피로 점수")
        FatigueGraph(state.history)
        if (a != null) {
            Info(
                "위험도",
                "${"%.2f".format(a.sensorScore)} (${a.level.label}) · 말 걸기 판단 ${"%.2f".format(a.score)} (${a.promptLevel.label})" +
                    " = 위험도 − 깨어 있음 확인 ${"%.2f".format(a.bonusApplied)} (쌓인 ${"%.2f".format(a.bonus)}${if (a.bonusBlocked) ", 잠김" else ""})",
            )
            if (a.reasons.isNotEmpty()) Info("판단 근거", a.reasons.joinToString(" · "))
            a.driverBusy?.let { Info("바쁜 순간", "${it.label} · 말 걸기 대기") }
            if (a.vehicleWarningActive) Info("차량", "운전자 주의 경고(DAW) 반영 중 → 위험")
            if (a.recirculationMin != null || a.co2Ppm != null) {
                Info(
                    "공조",
                    (a.recirculationMin?.let { "내기순환 ${"%.0f".format(it)}분째 · " } ?: "외기 · ") +
                        (a.co2Ppm?.let { "실내 CO₂ 추정 ${"%,d".format(it.toInt())}ppm" } ?: "CO₂ 추정 없음"),
                )
            }
            Info(
                "구성",
                "상황 ${"%.2f".format(a.contextRisk)} · " +
                    "핸들 ${a.steeringAnomaly?.let { "%.2f".format(it) } ?: "-"} · " +
                    "차선 ${a.laneRisk?.let { "%.2f".format(it) } ?: "-"} · " +
                    "대화 ${a.dialogImpairment?.let { "%.2f".format(it) } ?: "-"}",
            )
            Info(
                "컨디션",
                (a.sleepHours?.let { "수면 ${"%.1f".format(it)}시간 · " } ?: "수면 모름 · ") +
                    "권장 휴식 간격 ${a.restIntervalMin}분" +
                    (a.restDueInMin?.let { " · 남은 ${"%.0f".format(it)}분" } ?: ""),
            )
            Info("운행", "${a.driveState.label} · 연속 ${"%.0f".format(a.continuousDriveMin)}분 · 기준선 ${if (a.baselineReady) "완료" else "${(a.calibrationProgress * 100).toInt()}%"}")
            val f = a.features
            Info(
                "핸들",
                "반전 ${f.reversalRatePerMin?.let { "%.0f/분".format(it) } ?: "-"} · 급꺾음(5분) ${f.lapseEventsRecent}회 · " +
                    "속도편차 ${f.speedStdKmh?.let { "%.1f".format(it) } ?: "-"} · 유효 ${(f.validCoverage * 100).toInt()}%",
            )
            if (a.selfReportActive) Info("본인", "졸림 신고 반영 중")
        }
        HorizontalDivider(color = LunaColors.SurfaceHigh)
        VehicleSignalsCard(state)

        HorizontalDivider(color = LunaColors.SurfaceHigh)
        Label("운전자 대답 (음성 대신 입력)")
        Info("음성", "$ttsStatus / $sttStatus")
        Info("대화 AI", llmName)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = input, onValueChange = { input = it },
                singleLine = true, modifier = Modifier.weight(1f),
                placeholder = { Text("예: 기차, 응, 졸려", fontSize = 16.sp) },
            )
            Button(onClick = { onSend(input); input = "" }, modifier = Modifier.height(56.dp)) { Text("보내기", fontSize = 16.sp) }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(
                "응", "아니", "졸려", "안 졸려", "조용히 해", "끝말잇기 하자", "초성 게임 하자", "숫자 게임 하자",
                "휴게소 음식 퀴즈", "수수께끼 내줘", "반응 게임 하자", "퀴즈 내줘", "휴게소", "국밥 좋아해",
            ).forEach { q ->
                OutlinedButton(onClick = { onSend(q) }) { Text(q, fontSize = 15.sp) }
            }
        }

        HorizontalDivider(color = LunaColors.SurfaceHigh)
        Label("대화 기록")
        transcript.takeLast(14).forEach { line ->
            val (who, color) = when (line.speaker) {
                Speaker.LUNA -> "달토끼" to LunaColors.Moon
                Speaker.DRIVER -> "운전자" to LunaColors.Context
                Speaker.SYSTEM -> "시스템" to LunaColors.TextSecondary
            }
            Text("$who: ${line.text}", color = color, fontSize = 16.sp)
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Label(text: String) {
    Text(text, color = LunaColors.TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold)
}

@Composable
private fun Info(label: String, value: String) {
    Row {
        Text("$label  ", color = LunaColors.TextSecondary, fontSize = 15.sp)
        Text(value, color = LunaColors.TextPrimary, fontSize = 15.sp)
    }
}

/** 시연 프리셋 버튼 (크고 한 번에 누르기 쉽게) */
@Composable
private fun DemoButton(text: String, primary: Boolean, modifier: Modifier, onClick: () -> Unit) {
    if (primary) {
        Button(
            onClick = onClick,
            modifier = modifier.height(60.dp),
            colors = ButtonDefaults.buttonColors(containerColor = LunaColors.Moon, contentColor = LunaColors.Background),
        ) { Text("▶ $text", fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1) }
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier.height(60.dp)) {
            Text("▶ $text", fontSize = 17.sp, color = LunaColors.TextPrimary, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

/**
 * Pleos 차량 신호 확인 (심사·PT용): 앱이 실제로 받는 차량 신호와 그 출처(SDK API)를 한눈에.
 * 실차 모드면 신호별 연결 상태(OK/실패/미지원), 시뮬레이션이면 '시뮬레이션'으로 표시합니다.
 */
@Composable
private fun VehicleSignalsCard(state: CopilotUiState) {
    val s = state.snapshot
    val a = state.assessment
    val vehicle = state.sourceMode == SourceMode.VEHICLE
    fun src(key: String?): String = when {
        key == null -> "앱 계산"
        !vehicle -> "시뮬레이션"
        else -> state.signalStates[key] ?: "대기"
    }
    fun onOff(v: Boolean?) = when (v) { true -> "켜짐"; false -> "꺼짐"; null -> "모름" }
    Label("Pleos 차량 신호 (${if (vehicle) "실차" else "시뮬레이션"})")
    Column(
        Modifier.fillMaxWidth().background(LunaColors.SurfaceHigh.copy(alpha = 0.5f)).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        SignalRow("속도 · 기어", s?.let { "${"%.0f".format(it.speedKmh)}km/h · ${it.gear}" } ?: "-", "Car API 속도·기어", if (vehicle) "OK" else "시뮬레이션")
        SignalRow("핸들 각도", s?.steeringAngleDeg?.let { "%.1f°".format(it) } ?: "모름", "SteeringWheel", src("핸들"))
        SignalRow("방향지시등", onOff(s?.turnSignalOn), "TurnSignal", src("방향지시등"))
        SignalRow("브레이크", onOff(s?.brakePressed), "Brake", src("브레이크"))
        SignalRow(
            "경고등",
            listOfNotNull(
                "차로유지 ${if (s?.laneAssistActive == true) "켜짐" else "꺼짐"}",
                if (s?.laneDepartureWarning == true) "차선 개입!" else null,
                if (s?.driverAttentionWarning == true) "운전자 주의 경고!" else null,
            ).joinToString(" · "),
            "Safety.Telltales (LKA·LFA·ELK·DAW)", src("경고등"),
        )
        SignalRow("내기순환", onOff(s?.recirculationOn), "HVAC Recirculation", src("공조"))
        SignalRow("풍량", s?.fanSpeed?.let { "${it}단" } ?: "모름", "HVAC FanSpeed", src("풍량"))
        SignalRow("탑승 인원", s?.occupants?.let { "${it}명" } ?: "모름 (1명으로)", "Seat 착좌", src("착좌"))
        SignalRow("실내 CO₂", a?.co2Ppm?.let { "추정 ${"%,d".format(it.toInt())}ppm" } ?: "-", "내기순환·풍량·인원으로 추정", src(null))
        SignalRow("외기 전환", "운전자가 \"응\" 하면", "HVAC setRecirculation(false)", if (vehicle) state.signalStates["외기 전환"] ?: "대기" else "시뮬레이션")
        SignalRow("온도 낮추기", "운전자가 \"응\" 하면 2도", "HVAC setTemperature", if (vehicle) state.signalStates["온도 낮추기"] ?: "대기" else "시뮬레이션")
    }
}

@Composable
private fun SignalRow(name: String, value: String, api: String, status: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(name, color = LunaColors.TextSecondary, fontSize = 15.sp, modifier = Modifier.weight(0.9f))
        Text(value, color = LunaColors.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1.4f))
        Text(api, color = LunaColors.TextSecondary, fontSize = 13.sp, modifier = Modifier.weight(1.5f))
        val ok = status == "OK" || status == "시뮬레이션" || status == "앱 계산"
        Text(status, color = if (ok) LunaColors.Normal else LunaColors.TextSecondary, fontSize = 13.sp, modifier = Modifier.weight(0.7f))
    }
}
