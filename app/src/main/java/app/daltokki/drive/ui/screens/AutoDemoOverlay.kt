package app.daltokki.drive.ui.screens

import androidx.compose.material3.TextButton
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.daltokki.drive.domain.model.TripStats
import app.daltokki.drive.domain.game.TripResult
import app.daltokki.drive.ui.components.TripResultCard
import app.daltokki.drive.voice.SoundEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import app.daltokki.drive.engine.AutoDemoUi
import java.util.Locale
import app.daltokki.drive.ui.components.LunaIcons
import app.daltokki.drive.ui.components.TokkiAvatar
import app.daltokki.drive.ui.components.TokkiMood
import app.daltokki.drive.ui.theme.LunaColors

/**
 * 자동 시연 중 화면 맨 위 줄 (데모 패널 버튼 자리):
 * 왼쪽 장면 자막("② 멀쩡할 때는 조용히 — …"), 오른쪽 "자동 시연 · 운전자 응답 자동" 표시와 ■ 중지.
 * 자동이라는 것을 숨기지 않고 보여줍니다(재현 가능한 검증 장면).
 */
@Composable
fun AutoDemoTopBar(ui: AutoDemoUi, onStop: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        // 녹화 영상에서 읽히게 크게 (장면 번호 40sp · 제목 32sp · 설명 24sp, 두 줄)
        modifier.fillMaxWidth().height(TOP_BAR_H).padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.CenterStart) {
            val title = ui.sceneTitle
            if (title != null) {
                Row(
                    Modifier
                        .clip(RoundedCornerShape(28.dp))
                        .background(LunaColors.Moon)
                        .padding(start = 18.dp, end = 26.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Text(circled(ui.sceneNo), color = LunaColors.Background, fontSize = 40.sp, fontWeight = FontWeight.ExtraBold)
                    Column {
                        Text(title, color = LunaColors.Background, fontSize = 32.sp, lineHeight = 36.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        ui.sceneDetail?.let {
                            Text(it, color = LunaColors.Background.copy(alpha = 0.85f), fontSize = 24.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
        Row(
            Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(LunaColors.SurfaceHigh)
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(LunaColors.Danger))
            Text("자동 시연 · 운전자 응답 자동", color = LunaColors.TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
        if (!ui.finished) {
            OutlinedButton(onClick = onStop, modifier = Modifier.height(40.dp)) {
                Text("■ 중지", color = LunaColors.TextPrimary, fontSize = 18.sp)
            }
        }
    }
}

/** 시작 카운트다운(3·2·1)과 종료 카드. 해당 없으면 아무것도 그리지 않음 */
@Composable
fun AutoDemoOverlay(
    ui: AutoDemoUi,
    onDismiss: () -> Unit,
    report: TripStats? = null,
    /** 운행 결과 (별 3개·등급·신기록): 종료 카드 전에 5초 */
    result: TripResult? = null,
    onSound: (SoundEffect, Int) -> Unit = { _, _ -> },
) {
    val countdown = ui.countdown
    // 끝나면 먼저 '운행 결과'를 게임 결과 화면처럼 5초 → 종료 카드
    var resultPhase by remember(ui.finished) { mutableStateOf(ui.finished && result != null) }
    LaunchedEffect(ui.finished) {
        if (ui.finished && result != null) {
            delay(RESULT_SHOW_MS)
            resultPhase = false
        }
    }
    when {
        ui.finished && resultPhase && result != null -> Box(
            Modifier.fillMaxSize().background(LunaColors.Background.copy(alpha = 0.9f)),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Text("운행 결과", color = LunaColors.Moon, fontSize = 40.sp, fontWeight = FontWeight.ExtraBold)
                Text("+${"%,d".format(result.points)}점 · 최고 ${result.bestCombo}연속", color = LunaColors.TextPrimary, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                TripResultCard(result, onSound)
            }
        }
        countdown != null -> Box(
            Modifier.fillMaxSize().background(LunaColors.Background.copy(alpha = 0.82f)),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("자동 시연 · 새벽 고속도로", color = LunaColors.TextSecondary, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text("$countdown", color = LunaColors.Moon, fontSize = 140.sp, fontWeight = FontWeight.ExtraBold)
                Text("운전자 대답은 대본대로 자동 · 졸음 판단은 실제 엔진", color = LunaColors.TextSecondary, fontSize = 18.sp)
            }
        }
        ui.finished -> Box(
            Modifier.fillMaxSize().background(LunaColors.Background.copy(alpha = 0.92f)),
            contentAlignment = Alignment.Center,
        ) {
            // 종료 카드 (녹화 영상의 마지막 장면): 제목 "자동 시연 종료" → 이번 운행 큰 숫자 3칸 → 검증 → 핵심 4줄 → SDK → 밝힘 한 줄
            // 가장 작은 글씨도 18sp 이상, 녹화 화면(약 1120×720dp)에 스크롤 없이 들어가는 높이(약 600dp)
            Column(
                Modifier
                    .widthIn(max = 1000.dp)
                    .clip(RoundedCornerShape(32.dp))
                    .background(LunaColors.Surface)
                    .padding(horizontal = 44.dp, vertical = 26.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("자동 시연 종료", color = LunaColors.Moon, fontSize = 48.sp, lineHeight = 54.sp, fontWeight = FontWeight.ExtraBold)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    TokkiAvatar(mood = TokkiMood.HAPPY, width = 64.dp)
                    Text(
                        "달토끼 · 졸음이 오기 전에, 달토끼가 먼저 알아채요",
                        color = LunaColors.TextPrimary, fontSize = 28.sp, fontWeight = FontWeight.Bold,
                    )
                }
                // 이번 운행에서 실제로 잰 값 (연출이 아니라 엔진이 계산한 숫자)
                val cells = buildList {
                    report?.leadMin?.let { add(String.format(Locale.US, "%.1f분 먼저", it) to "차량 경고보다") }
                    val after = report?.napAfterMs
                    val before = report?.napBeforeMs
                    if (after != null && before != null) {
                        add(String.format(Locale.US, "%.1f초 → %.1f초", before / 1000f, after / 1000f) to "커피냅 뒤 반응")
                    }
                    result?.let { r ->
                        add("${"★".repeat(r.stars.count { it })}${"☆".repeat(3 - r.stars.count { it })} ${r.grade}등급" to "운행 결과 · ${"%,d".format(r.points)}점")
                    }
                }
                if (cells.isNotEmpty()) {
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(24.dp))
                            .background(LunaColors.Inner)
                            .padding(horizontal = 18.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(28.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        cells.forEach { (big, label) ->
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(big, color = LunaColors.Moon, fontSize = 38.sp, lineHeight = 44.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
                                Text(label, color = LunaColors.TextSecondary, fontSize = 20.sp, maxLines = 1)
                            }
                        }
                    }
                }
                Text(VALIDATION_LINE, color = LunaColors.TextPrimary, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    EndLine("말 걸지 않아도 핸들 패턴으로 먼저 감지")
                    EndLine("말로 하는 게임 7가지 · 대답 속도도 판단 근거")
                    EndLine("CO₂ 추정 → 외기 전환 · 커피냅 뒤 반응 재측정")
                    EndLine("운전자별 습관·기억 · 모든 데이터는 기기 안에만")
                }
                Text(
                    "사용 Pleos SDK: Vehicle(공조·좌석) · NaviHelper · TTS/STT · Gleo",
                    color = LunaColors.TextSecondary, fontSize = 22.sp, textAlign = TextAlign.Center,
                )
                Box(Modifier.fillMaxWidth()) {
                    Text(
                        "차량 데이터는 시뮬레이션 재생 · 운전자 대답은 자동",
                        color = LunaColors.TextSecondary.copy(alpha = 0.8f), fontSize = 18.sp,
                        modifier = Modifier.align(Alignment.Center),
                    )
                    // 닫기: 녹화에 방해되지 않게 작게 오른쪽 아래
                    TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.CenterEnd)) {
                        Text("닫기", color = LunaColors.TextSecondary, fontSize = 18.sp)
                    }
                }
            }
        }
    }
}

private const val RESULT_SHOW_MS = 5_000L

/** 자동 시연 맨 위 줄 높이 (장면 자막 두 줄) */
private val TOP_BAR_H = 88.dp

@Composable
private fun EndLine(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(LunaIcons.Check, contentDescription = null, tint = LunaColors.Normal, modifier = Modifier.size(26.dp))
        Text(text, color = LunaColors.TextPrimary, fontSize = 24.sp)
    }
}

/**
 * 검증 리포트(ValidationReportTest, 시나리오별 운전 습관·난수를 바꿔 20회) 결과 — 2026-11-06 실행값.
 * 시나리오·판정 기준을 바꾸면 리포트를 다시 돌려 숫자를 고칩니다.
 */
private const val VALIDATION_LINE = "검증 20회 · 차량 경고보다 먼저 100% (중앙값 5.2분) · 정상 주행 오탐 0%"

private fun circled(n: Int): String = if (n in 1..20) (0x2460 + n - 1).toChar().toString() else "$n"
