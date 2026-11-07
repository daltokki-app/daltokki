package app.daltokki.drive.ui.screens

import androidx.compose.foundation.horizontalScroll
import app.daltokki.drive.domain.game.Cosmetics
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.daltokki.drive.domain.dialog.TalkLevel
import app.daltokki.drive.domain.driver.DriverProfile
import app.daltokki.drive.domain.driver.DriverRoster
import app.daltokki.drive.engine.DriverUi
import app.daltokki.drive.engine.HabitMismatchUi
import app.daltokki.drive.ui.components.LunaIcons
import app.daltokki.drive.ui.theme.LunaColors

/** 운전자 색 (정차 화면 칩·목록의 점) */
private val DRIVER_COLORS = listOf(
    Color(0xFFF6D77A), Color(0xFF8FB8F0), Color(0xFF7BD3A6), Color(0xFFF7B3B0), Color(0xFFC3A6F0), Color(0xFFF2A65A),
)

fun driverColor(index: Int): Color = if (index < 0) LunaColors.TextSecondary else DRIVER_COLORS[index % DRIVER_COLORS.size]

/** 운전자 습관 한 줄 ("습관 12회 기억" / "습관 익히는 중 1/3") */
fun habitText(d: DriverUi): String = when {
    d.guest -> "기록 안 함"
    d.habitReady -> "습관 ${d.habitTrips}회 기억"
    else -> "습관 익히는 중 ${d.habitTrips}/3"
}

/** 정차 화면의 지금 운전자 칩. 누르면 고르기·추가 */
/**
 * 정차 화면의 운전자 버튼: [● 운전자 1   변경 ▾]. 다른 큰 버튼과 같은 높이·테두리라 누를 수 있는 것이 바로 보임.
 * 습관이 얼마나 쌓였는지는 운전자 창 안에서 보여줍니다.
 */
@Composable
fun DriverButton(driver: DriverUi?, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    if (driver == null) return
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(76.dp),
        shape = RoundedCornerShape(38.dp),
        border = BorderStroke(2.dp, if (enabled) LunaColors.Outline else LunaColors.SurfaceHigh),
        contentPadding = PaddingValues(horizontal = 20.dp),
    ) {
        Icon(LunaIcons.Person, contentDescription = null, tint = LunaColors.TextSecondary, modifier = Modifier.size(26.dp))
        Spacer(Modifier.width(10.dp))
        Box(Modifier.size(14.dp).clip(CircleShape).background(driverColor(driver.colorIndex)))
        Spacer(Modifier.width(10.dp))
        Text(
            driver.name, color = LunaColors.TextPrimary, fontSize = 28.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
        )
        if (enabled) {
            Spacer(Modifier.width(14.dp))
            Text("변경 ▾", color = LunaColors.Moon, fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

/** 운전자 이름 빨리 고르기 (에뮬레이터는 한글 입력이 불편해서) */
private val QUICK_NAMES = listOf("아빠", "엄마", "나", "형", "누나", "언니", "오빠", "동생")

/**
 * 운전자 고르기·추가·관리 창.
 * @param assignMode 습관이 평소와 달랐을 때 "누가 운전했나요?"로 열린 경우 (고르면 그 운전자 습관으로 넣음)
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DriverDialog(
    current: DriverUi?,
    drivers: List<DriverUi>,
    assignMode: Boolean,
    /** 달토끼 말 수 (지금 운전자 설정) */
    talkLevel: TalkLevel? = null,
    onTalkLevel: (TalkLevel) -> Unit = {},
    onSelect: (String) -> Unit,
    onAdd: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onRemove: (String) -> Unit,
    onResetHabit: (String) -> Unit,
    onDismiss: () -> Unit,
    /** 기억하는 대화: 그 운전자의 기억 지우기 */
    onClearMemory: (String) -> Unit = {},
    /** 달토끼 꾸미기: 아이템 id (null = 벗기) */
    onWear: (String?) -> Unit = {},
) {
    var adding by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var managing by remember { mutableStateOf<DriverUi?>(null) }
    var renameText by remember { mutableStateOf("") }
    // 지금 운전자 이름 바로 고치기 (처음 실행 때 "운전자 1"을 그대로 두거나 바로 고침)
    var currentName by remember(current?.id, current?.name) { mutableStateOf(current?.name.orEmpty()) }
    val canAdd = drivers.size < DriverRoster.MAX_DRIVERS

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = LunaColors.Surface,
        title = {
            Text(
                if (assignMode) "누가 운전했나요?" else "누가 운전하나요?",
                color = LunaColors.TextPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(
                Modifier.widthIn(min = 460.dp).heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    if (assignMode) "고른 운전자의 습관으로 이번 운행을 기억해요." else "운전자마다 운전 습관·도장·달 레벨을 따로 기억해요. 모든 기록은 이 기기 안에만 저장돼요.",
                    color = LunaColors.TextSecondary, fontSize = 17.sp,
                )
                // 지금 운전자 이름 (게스트·시연 운전자는 바꾸지 않음)
                if (!assignMode && current != null && !current.guest && !current.demo) {
                    Text("이름", color = LunaColors.Moon, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = currentName,
                            onValueChange = { currentName = it.take(DriverRoster.MAX_NAME) },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        Button(
                            onClick = { onRename(current.id, currentName) },
                            enabled = currentName.isNotBlank() && currentName.trim() != current.name,
                            modifier = Modifier.height(56.dp),
                        ) { Text("이름 바꾸기", fontSize = 16.sp) }
                    }
                    HorizontalDivider(color = LunaColors.SurfaceHigh)
                }
                // 운전 습관: 평소(누적) vs 지난 운행 — "졸린 날은 배우지 않아요"를 눈으로
                if (!assignMode && current != null && !current.guest && (current.habitRate != null || current.habitLatencyMs != null)) {
                    HabitCompare(current)
                    HorizontalDivider(color = LunaColors.SurfaceHigh)
                }
                // 달토끼 꾸미기: 달빛 선물로 모은 아이템 (지금 운전자)
                if (!assignMode && current != null && current.items.isNotEmpty()) {
                    Text("달토끼 꾸미기 · ${current.items.size}/${Cosmetics.all.size}", color = LunaColors.Moon, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                        (listOf<String?>(null) + current.items).forEach { id ->
                            val selected = id == current.wearing
                            OutlinedButton(
                                onClick = { onWear(id) },
                                border = BorderStroke(2.dp, if (selected) LunaColors.Moon else LunaColors.Outline),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    containerColor = if (selected) LunaColors.Moon.copy(alpha = 0.14f) else Color.Transparent,
                                ),
                            ) {
                                Text(
                                    id?.let { Cosmetics.byId(it)?.name ?: it } ?: "없음",
                                    color = if (selected) LunaColors.Moon else LunaColors.TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    }
                    HorizontalDivider(color = LunaColors.SurfaceHigh)
                }
                // 말 수: 지금 운전자 설정 (정차 화면에서 옮겨 옴)
                if (!assignMode && talkLevel != null) {
                    Text("달토끼 말 수 · ${current?.name.orEmpty()}", color = LunaColors.Moon, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        TalkLevel.entries.forEach { level ->
                            val selected = level == talkLevel
                            OutlinedButton(
                                onClick = { onTalkLevel(level) },
                                modifier = Modifier.weight(1f),
                                border = BorderStroke(2.dp, if (selected) LunaColors.Moon else LunaColors.Outline),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    containerColor = if (selected) LunaColors.Moon.copy(alpha = 0.14f) else Color.Transparent,
                                ),
                            ) {
                                Text(level.label, color = if (selected) LunaColors.Moon else LunaColors.TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    Text(talkLevel.description, color = LunaColors.TextSecondary, fontSize = 15.sp)
                    HorizontalDivider(color = LunaColors.SurfaceHigh)
                }
                drivers.forEach { d ->
                    DriverRow(
                        d = d,
                        selected = d.id == current?.id,
                        onClick = { onSelect(d.id) },
                        onManage = if (assignMode) null else ({ managing = d; renameText = d.name }),
                    )
                }
                if (!assignMode) {
                    DriverRow(
                        d = DriverUi(DriverProfile.GUEST_ID, "게스트", -1, 0, habitReady = false, guest = true),
                        selected = current?.guest == true,
                        onClick = { onSelect(DriverProfile.GUEST_ID) },
                        onManage = null,
                    )
                    HorizontalDivider(color = LunaColors.SurfaceHigh)
                    if (!adding) {
                        // 이름은 기본으로 운전자 번호 ("운전자 2") → [추가]만 눌러도 됨
                        TextButton(onClick = { adding = true; name = DriverRoster.nextDefaultName(drivers.map { it.name }) }, enabled = canAdd) {
                            Text(if (canAdd) "+ 운전자 추가" else "운전자는 ${DriverRoster.MAX_DRIVERS}명까지", color = LunaColors.Moon, fontSize = 17.sp)
                        }
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = name,
                                onValueChange = { name = it.take(DriverRoster.MAX_NAME) },
                                placeholder = { Text("이름") },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                            Button(onClick = { onAdd(name); adding = false }, enabled = name.isNotBlank(), modifier = Modifier.height(56.dp)) {
                                Text("추가", fontSize = 16.sp)
                            }
                        }
                        // 빠른 이름 (에뮬레이터는 한글 입력이 불편해서): 누르면 이름 칸에 들어감
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            QUICK_NAMES.filter { q -> drivers.none { it.name == q } }.forEach { q ->
                                TextButton(onClick = { name = q }) { Text(q, color = LunaColors.TextSecondary, fontSize = 15.sp) }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(if (assignMode) "학습 안 함" else "닫기", color = LunaColors.TextSecondary, fontSize = 16.sp) }
        },
    )

    // 운전자 관리 (이름 바꾸기 · 습관 다시 배우기 · 기억 지우기 · 삭제)
    val m = managing
    if (m != null) {
        AlertDialog(
            onDismissRequest = { managing = null },
            containerColor = LunaColors.Surface,
            title = { Text("${m.name} 관리", color = LunaColors.TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = renameText,
                            onValueChange = { renameText = it.take(DriverRoster.MAX_NAME) },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        Button(onClick = { onRename(m.id, renameText); managing = null }, enabled = renameText.isNotBlank()) { Text("이름 바꾸기") }
                    }
                    Text("${habitText(m)} · 습관은 이 기기에만 저장돼요", color = LunaColors.TextSecondary, fontSize = 15.sp)
                    OutlinedButton(onClick = { onResetHabit(m.id); managing = null }, modifier = Modifier.fillMaxWidth()) {
                        Text("운전 습관 다시 배우기", color = LunaColors.TextPrimary, fontSize = 16.sp)
                    }
                    // 기억하는 대화: 달토끼가 기억하는 것 (기기 안에만)
                    Text(
                        if (m.memory.isEmpty()) "달토끼가 기억하는 것: 아직 없음" else "달토끼가 기억하는 것: " + m.memory.joinToString(" · "),
                        color = LunaColors.TextSecondary, fontSize = 15.sp,
                    )
                    if (m.memory.isNotEmpty()) {
                        OutlinedButton(onClick = { onClearMemory(m.id); managing = null }, modifier = Modifier.fillMaxWidth()) {
                            Text("기억 지우기", color = LunaColors.TextPrimary, fontSize = 16.sp)
                        }
                    }
                    OutlinedButton(
                        onClick = { onRemove(m.id); managing = null },
                        enabled = drivers.size > 1,
                        modifier = Modifier.fillMaxWidth(),
                        border = BorderStroke(1.dp, LunaColors.Danger),
                    ) {
                        Text(if (drivers.size > 1) "운전자 삭제 (습관·도장 모두)" else "마지막 운전자는 삭제할 수 없어요", color = LunaColors.Danger, fontSize = 16.sp)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { managing = null }) { Text("닫기", color = LunaColors.TextSecondary) } },
        )
    }
}

@Composable
private fun DriverRow(d: DriverUi, selected: Boolean, onClick: () -> Unit, onManage: (() -> Unit)?) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) LunaColors.SurfaceHigh else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(14.dp).clip(CircleShape).background(driverColor(d.colorIndex)))
        Column(Modifier.weight(1f)) {
            Text(d.name, color = LunaColors.TextPrimary, fontSize = 21.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (d.guest) "판단은 기본값으로 · 습관·도장 기록 안 함" else habitText(d), color = LunaColors.TextSecondary, fontSize = 16.sp)
        }
        if (selected) Icon(LunaIcons.Check, contentDescription = "선택됨", tint = LunaColors.Moon, modifier = Modifier.size(22.dp))
        if (onManage != null) {
            TextButton(onClick = onManage) { Text("관리", color = LunaColors.TextSecondary, fontSize = 15.sp) }
        }
    }
}

/** 이번 운행 습관이 평소와 많이 달랐을 때 (정차 화면 2단 맨 위) */
@Composable
fun HabitMismatchCard(m: HabitMismatchUi, onConfirm: () -> Unit, onOther: () -> Unit, onSkip: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(LunaColors.Surface)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("혹시 다른 분이 운전하셨어요?", color = LunaColors.Moon, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(
            "오늘 운전 습관이 ${m.driverName}의 평소와 많이 달라요 (${m.reason}). 다른 사람의 습관이 섞이지 않게 확인할게요.",
            color = LunaColors.TextPrimary, fontSize = 16.sp,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = onConfirm,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = LunaColors.Moon, contentColor = LunaColors.Background),
            ) { Text("${m.driverName} 맞아요", fontSize = 15.sp, maxLines = 1) }
            OutlinedButton(onClick = onOther, modifier = Modifier.weight(1f)) { Text("다른 운전자", color = LunaColors.TextPrimary, fontSize = 15.sp, maxLines = 1) }
        }
        TextButton(onClick = onSkip) { Text("이번 운행은 학습 안 함", color = LunaColors.TextSecondary, fontSize = 15.sp) }
    }
}

/**
 * 운전 습관 비교: 평소(누적 기준) vs 지난 운행. 핸들 보정 횟수(/분)와 대답 속도(초)를 막대로.
 * 지난 운행 습관을 기준에 더했는지(졸린 날·평소와 다름이면 빼요)도 한 줄로.
 */
@Composable
private fun HabitCompare(d: DriverUi) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("운전 습관 · 평소 vs 지난 운행", color = LunaColors.Moon, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        val maxRate = maxOf(d.habitRate ?: 0f, d.lastRate ?: 0f, 1f)
        CompareBars("핸들 보정", d.habitRate?.let { it / maxRate }, d.lastRate?.let { it / maxRate },
            d.habitRate?.let { "${it.toInt()}회/분" }, d.lastRate?.let { "${it.toInt()}회/분" })
        val maxLat = maxOf(d.habitLatencyMs ?: 0L, d.lastLatencyMs ?: 0L, 1L).toFloat()
        CompareBars("대답 속도", d.habitLatencyMs?.let { it / maxLat }, d.lastLatencyMs?.let { it / maxLat },
            d.habitLatencyMs?.let { String.format(java.util.Locale.US, "%.1f초", it / 1000f) },
            d.lastLatencyMs?.let { String.format(java.util.Locale.US, "%.1f초", it / 1000f) })
        d.lastHabitNote?.let { Text(it, color = LunaColors.TextSecondary, fontSize = 15.sp) }
    }
}

@Composable
private fun CompareBars(label: String, usual: Float?, last: Float?, usualText: String?, lastText: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, color = LunaColors.TextSecondary, fontSize = 15.sp)
        BarLine("평소", usual, usualText, LunaColors.Moon)
        BarLine("지난 운행", last, lastText, LunaColors.Context)
    }
}

@Composable
private fun BarLine(name: String, ratio: Float?, text: String?, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(name, color = LunaColors.TextSecondary, fontSize = 15.sp, modifier = Modifier.widthIn(min = 72.dp))
        Box(Modifier.weight(1f).height(12.dp).clip(RoundedCornerShape(6.dp)).background(LunaColors.Track)) {
            if (ratio != null) Box(Modifier.fillMaxWidth(ratio.coerceIn(0.02f, 1f)).height(12.dp).clip(RoundedCornerShape(6.dp)).background(color))
        }
        Text(text ?: "아직 없음", color = LunaColors.TextPrimary, fontSize = 16.sp, modifier = Modifier.widthIn(min = 80.dp))
    }
}
