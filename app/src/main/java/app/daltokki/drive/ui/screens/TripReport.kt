package app.daltokki.drive.ui.screens

import androidx.compose.foundation.Canvas
import app.daltokki.drive.ui.components.SafetyTeal
import app.daltokki.drive.domain.game.PointCategory
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.daltokki.drive.domain.model.TripStats
import app.daltokki.drive.engine.DriverUi
import app.daltokki.drive.engine.GameUi
import app.daltokki.drive.engine.GraphPoint
import app.daltokki.drive.ui.components.LunaIcons
import app.daltokki.drive.ui.theme.LunaColors
import java.util.Locale

private fun sec(ms: Long): String = String.format(Locale.US, "%.1f초", ms / 1000f)

/**
 * 운행 리포트 (운행이 끝나면 자동으로, 정차 화면 '지난 운행'을 눌러도).
 * 심사·운전자에게 한눈에: 얼마나 피곤했나(그래프) → 차량 경고보다 먼저 알아챘나 → 반응 속도·커피냅 효과
 * → 바쁜 순간에 기다린 횟수 → 이번 주 · 안전 운행 연속 기록.
 */
@Composable
fun TripReportDialog(
    stats: TripStats,
    history: List<GraphPoint>,
    driver: DriverUi?,
    onDismiss: () -> Unit,
    breakdown: Map<PointCategory, Int> = emptyMap(),
    /** 운행 결과 (별 3개·등급·신기록). 없으면 생략 */
    result: app.daltokki.drive.domain.game.TripResult? = null,
    onSound: (app.daltokki.drive.voice.SoundEffect, Int) -> Unit = { _, _ -> },
) {
    // 열릴 때 숫자가 0부터 올라가고 그래프가 왼쪽부터 그려짐 (1.2초)
    val p = remember { Animatable(0f) }
    LaunchedEffect(Unit) { p.animateTo(1f, tween(1_200, easing = FastOutSlowInEasing)) }
    val k = p.value
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = LunaColors.Surface,
        title = {
            Text(
                driver?.takeUnless { it.guest }?.let { "${it.name}의 운행 리포트" } ?: "운행 리포트",
                color = LunaColors.Moon, fontSize = 28.sp, fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(
                Modifier.widthIn(min = 640.dp).heightIn(max = 600.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // 운행 결과: 별 3개(안전 운행·안전 행동 2번·3콤보)가 하나씩 쾅 → 등급 → 신기록 띠
                if (result != null) {
                    app.daltokki.drive.ui.components.TripResultCard(result, onSound, Modifier.fillMaxWidth())
                }
                // 큰 숫자 셋
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ReportNumber("운전", "${(stats.drivingMs / 60_000L * k).toInt()}", "분", Modifier.weight(1f))
                    ReportNumber("휴식", "${(stats.rests * k).toInt()}", "번", Modifier.weight(1f))
                    ReportNumber("가장 피곤했던 때", stats.maxLevel.label, "", Modifier.weight(1.3f), LunaColors.level(stats.maxLevel))
                }
                // 먼저 알아챘는지 (가장 중요한 한 줄)
                val lead = stats.leadMin
                val leadText = when {
                    lead != null -> String.format(Locale.US, "차량 주의 경고보다 %.1f분 먼저 알아챘어요", lead)
                    stats.firstWarningMs >= 0 && stats.firstVehicleWarningMs < 0 -> "차량 경고가 켜지기 전에 졸음 신호를 먼저 알아챘어요"
                    else -> null
                }
                if (leadText != null) {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(LunaColors.Moon.copy(alpha = 0.14f)).padding(horizontal = 20.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(LunaIcons.Moon, contentDescription = null, tint = LunaColors.Moon, modifier = Modifier.size(28.dp))
                        Text(leadText, color = LunaColors.Moon, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    }
                }
                // 졸음 위험도 흐름
                if (history.size >= 2) {
                    Text("졸음 위험도 흐름", color = LunaColors.TextSecondary, fontSize = 20.sp)
                    RiskTimeline(history, k)
                }
                // 점수 내역: 쉬고 환기하는 것이 점수의 큰 몫
                val total = breakdown.values.sum()
                if (total > 0) {
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(LunaColors.Inner).padding(horizontal = 20.dp, vertical = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text("이번 운행 ${"%,d".format((total * k).toInt())}점", color = LunaColors.Moon, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                        Text(
                            PointCategory.entries.filter { (breakdown[it] ?: 0) > 0 }.joinToString(" · ") { "${it.label} ${"%,d".format(breakdown[it])}" },
                            color = LunaColors.TextPrimary, fontSize = 22.sp,
                        )
                        PointBar(breakdown, total)
                    }
                }
                HorizontalDivider(color = LunaColors.SurfaceHigh)
                // 반응 게임 · 커피냅
                if (stats.reactionMs.isNotEmpty() || stats.reactionMisses > 0) {
                    val parts = stats.reactionMs.map { sec(it) } + if (stats.reactionMisses > 0) listOf("놓침 ${stats.reactionMisses}번") else emptyList()
                    ReportLine("반응 게임", parts.joinToString(" · "))
                }
                val after = stats.napAfterMs
                if (after != null) {
                    ReportLine("커피냅 뒤 반응", stats.napBeforeMs?.let { "${sec(it)} → ${sec(after)}" } ?: sec(after))
                }
                if (stats.deferrals > 0) {
                    ReportLine("조용히 기다린 순간", "차선 변경·통화 중 ${stats.deferrals}번")
                }
                ReportLine("달토끼가 먼저 말 건 횟수", "${stats.interventions}번")
                if (driver != null && !driver.guest) {
                    HorizontalDivider(color = LunaColors.SurfaceHigh)
                    ReportLine("안전 운행", "${driver.streak}번 연속")
                    Text(driver.weekly, color = LunaColors.TextPrimary, fontSize = 22.sp, lineHeight = 30.sp)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("닫기", color = LunaColors.TextSecondary, fontSize = 20.sp) }
        },
    )
}

@Composable
private fun ReportNumber(label: String, value: String, unit: String, modifier: Modifier, color: androidx.compose.ui.graphics.Color = LunaColors.TextPrimary) {
    Column(
        modifier.clip(RoundedCornerShape(20.dp)).background(LunaColors.Inner).padding(horizontal = 18.dp, vertical = 14.dp),
    ) {
        Text(label, color = LunaColors.TextSecondary, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(value, color = color, fontSize = 36.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            if (unit.isNotEmpty()) Text(unit, color = LunaColors.TextSecondary, fontSize = 20.sp, modifier = Modifier.padding(bottom = 6.dp))
        }
    }
}

@Composable
private fun ReportLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = LunaColors.TextSecondary, fontSize = 20.sp, modifier = Modifier.weight(1f))
        Text(value, color = LunaColors.TextPrimary, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** 운전자용 단순 그래프: 졸음 위험도 한 줄(구간마다 단계 색) + 주의·경고·위험 기준 점선 */
@Composable
private fun RiskTimeline(points: List<GraphPoint>, reveal: Float = 1f) {
    Canvas(Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(16.dp)).background(LunaColors.Inner)) {
        val w = size.width
        val h = size.height
        val pad = 12f
        val maxMin = (points.last().minute).coerceAtLeast(10f)
        fun x(m: Float) = pad + (m / maxMin) * (w - pad * 2)
        fun y(v: Float) = h - pad - v.coerceIn(0f, 1f) * (h - pad * 2)
        val dash = PathEffect.dashPathEffect(floatArrayOf(10f, 10f))
        listOf(0.3f to LunaColors.Caution, 0.5f to LunaColors.Warning, 0.75f to LunaColors.Danger).forEach { (v, c) ->
            drawLine(c.copy(alpha = 0.45f), Offset(pad, y(v)), Offset(w - pad, y(v)), strokeWidth = 2f, pathEffect = dash)
        }
        val shown = (points.size * reveal).toInt().coerceIn(1, points.size)
        for (i in 1 until shown) {
            val a = points[i - 1]
            val b = points[i]
            drawLine(
                LunaColors.level(b.level),
                Offset(x(a.minute), y(a.sensor)), Offset(x(b.minute), y(b.sensor)),
                strokeWidth = 6f, cap = StrokeCap.Round,
            )
        }
    }
}

/** 점수 내역 막대: 게임(달빛) · 안전 행동(청록) · 휴식(초록) · 도장(산호) */
@Composable
private fun PointBar(breakdown: Map<PointCategory, Int>, total: Int) {
    Row(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(7.dp))) {
        PointCategory.entries.forEach { c ->
            val v = breakdown[c] ?: 0
            if (v > 0) Box(Modifier.weight(v.toFloat() / total).fillMaxHeight().background(categoryColor(c)))
        }
    }
}

private fun categoryColor(c: PointCategory) = when (c) {
    PointCategory.GAME -> LunaColors.Moon
    PointCategory.SAFETY -> SafetyTeal
    PointCategory.REST -> LunaColors.Normal
    PointCategory.STAMP -> androidx.compose.ui.graphics.Color(0xFFFF7A6B)
}

/** 휴게소 도장첩: 노선별 진행과 최근 도장 (정차 화면 기록 카드의 '휴게소 도장'을 누르면) */
@Composable
fun StampBookDialog(game: GameUi, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = LunaColors.Surface,
        title = { Text("휴게소 도장첩 · ${game.stampPlaces} / ${game.stampTotal}곳", color = LunaColors.Moon, fontSize = 28.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column(
                Modifier.widthIn(min = 600.dp).heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (game.stampPlaces == 0) {
                    Text("휴게소·졸음쉼터에서 10분 쉬면 그곳 도장을 받아요. 한 노선 5곳이면 '노선 탐방' 배지!", color = LunaColors.TextPrimary, fontSize = 22.sp, lineHeight = 30.sp)
                }
                if (game.routes.isNotEmpty()) {
                    Text("노선별", color = LunaColors.TextSecondary, fontSize = 20.sp)
                    game.routes.forEach { r ->
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(Modifier.fillMaxWidth()) {
                                Text(r.route, color = LunaColors.TextPrimary, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                Text("${r.have} / ${r.total}곳", color = LunaColors.TextSecondary, fontSize = 22.sp)
                            }
                            ProgressBar(if (r.total > 0) r.have.toFloat() / r.total else 0f, LunaColors.Moon, track = LunaColors.Track)
                        }
                    }
                }
                if (game.recentStamps.isNotEmpty()) {
                    HorizontalDivider(color = LunaColors.SurfaceHigh)
                    Text("최근 도장", color = LunaColors.TextSecondary, fontSize = 20.sp)
                    game.recentStamps.forEach { st ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(LunaIcons.Coffee, contentDescription = null, tint = if (st.isShelter) LunaColors.Context else LunaColors.Moon, modifier = Modifier.size(26.dp))
                            Text(st.name, color = LunaColors.TextPrimary, fontSize = 22.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(st.route ?: "", color = LunaColors.TextSecondary, fontSize = 18.sp)
                            if (st.visits > 1) Text("${st.visits}번", color = LunaColors.TextSecondary, fontSize = 18.sp)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("닫기", color = LunaColors.TextSecondary, fontSize = 20.sp) }
        },
    )
}
