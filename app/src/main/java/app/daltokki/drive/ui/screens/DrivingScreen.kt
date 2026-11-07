package app.daltokki.drive.ui.screens

import app.daltokki.drive.domain.fatigue.RiskDrop
import app.daltokki.drive.ui.components.RiskNumber
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import app.daltokki.drive.ui.components.ScoreHud
import app.daltokki.drive.ui.components.RingPulse
import app.daltokki.drive.ui.components.GameEndSummary
import app.daltokki.drive.ui.components.MoonGlow
import app.daltokki.drive.ui.components.ComboBurst
import app.daltokki.drive.engine.GainReason
import kotlinx.coroutines.delay
import androidx.compose.runtime.LaunchedEffect
import app.daltokki.drive.domain.game.GameKind
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.daltokki.drive.data.nav.RestArea
import app.daltokki.drive.domain.dialog.Intervention
import app.daltokki.drive.domain.dialog.LunaLines
import app.daltokki.drive.domain.fatigue.CabinAirModel
import app.daltokki.drive.domain.model.DriveState
import app.daltokki.drive.domain.model.FatigueAssessment
import app.daltokki.drive.domain.model.FatigueLevel
import app.daltokki.drive.engine.GameUi
import app.daltokki.drive.ui.components.LunaIcons
import app.daltokki.drive.ui.components.TokkiColors
import app.daltokki.drive.ui.components.TokkiMood
import app.daltokki.drive.ui.components.TokkiRing
import app.daltokki.drive.ui.theme.LunaColors
import kotlin.math.roundToInt

/** 환기 안내를 화면에 띄우는 내기순환 연속 시간(분) — FatigueConfig.recirculationSuggestMin과 같게 */
private const val RECIRC_CHIP_MIN = 20f

/**
 * 주행 중 화면 — 달토끼 캐릭터 + 편안하고 단순하게 (A안 · 고요한 밤).
 *
 * 한눈에 세 가지만: 달토끼를 감싼 링(졸음 위험도) → 큰 상태 글씨("좋아요/주의/경고/위험") → 큰 버튼 2개.
 * - 링 길이·색은 핸들·시간·상황·대화 반응으로 계산한 실제 졸음 위험도입니다. 게임으로 낮추지 않습니다.
 * - 달토끼 표정: 듣는 중 / 말하는 중 / 경고 이상이면 걱정 / 새 배지면 축하. 운전 중에는 움직임 없이 표정만 바뀝니다.
 * - 오른쪽(좁은 화면은 아래): 상태 한 줄 → (주의 이상) 근거 → 달토끼의 말 → (위험) 휴게소 카드 → 버튼
 * - 점수·배지·도장은 운전 중 화면을 보게 만들지 않도록 정차 화면에서만 보여줍니다.
 */
@Composable
fun DrivingScreen(
    assessment: FatigueAssessment?,
    game: GameUi,
    restArea: RestArea?,
    busy: Intervention?,
    askingRest: Boolean,
    lastLunaLine: String,
    listening: Boolean,
    onTalk: () -> Unit,
    /** 게임 고르기 창에서 고른 게임 (null = 알아서 골라 줘) */
    onPickGame: (GameKind?) -> Unit,
    onRest: () -> Unit,
    onAcceptRest: () -> Unit,
    onDeclineRest: () -> Unit,
    modifier: Modifier = Modifier,
    speaking: Boolean = false,
    /** 운전자가 방금 한 말 (말풍선으로 잠깐 표시) */
    driverLine: String? = null,
    /** 통화 중 (달토끼는 먼저 말을 걸지 않음) */
    inCall: Boolean = false,
    /** 지금 운전자 이름 (게스트면 null) */
    driverName: String? = null,
    /** 운전자의 누적 습관으로 바로 판단 중이면 그 운행 수 (아니면 0) */
    habitTrips: Int = 0,
    /** 왼쪽 위에 겹쳐 놓인 '데모' 버튼만큼 머리글을 오른쪽으로 */
    headerStartInset: Dp = 0.dp,
    /** 게임 중이면 게임 버튼이 [게임 그만] */
    inGame: Boolean = false,
    onStopGame: () -> Unit = {},
    /** 실제 주행 중 차량 화면 제한 (게임 고르기 창은 [알아서 골라 줘]만) */
    restricted: Boolean = false,
    /** 달토끼 꾸미기 (착용 중인 아이템 id) */
    accessory: String? = null,
    /** 행동 뒤 위험도가 실제로 내려감 ("−7 · 빠른 대답") */
    riskDrop: RiskDrop? = null,
) {
    var picking by remember { mutableStateOf(false) }
    if (picking) {
        GamePickerDialog(
            level = assessment?.level ?: FatigueLevel.NORMAL,
            onPick = { picking = false; onPickGame(it) },
            onDismiss = { picking = false },
            restricted = restricted,
        )
    }
    val level = assessment?.level ?: FatigueLevel.NORMAL
    val danger = level == FatigueLevel.DANGER
    // 정상 단계에서 3연속 이상이면 고리가 따뜻한 달빛으로 살짝 (주의·경고·위험 색은 그대로 — 위험 신호를 가리지 않게)
    val levelColor = LunaColors.level(level)
    val ringTint = if (level == FatigueLevel.NORMAL && game.combo >= 3) {
        androidx.compose.ui.graphics.lerp(levelColor, LunaColors.Moon, if (game.combo >= 5) 0.6f else 0.35f)
    } else levelColor
    val risk = ((assessment?.sensorScore ?: 0f) * 100).roundToInt()
    val calibrating = assessment?.driveState == DriveState.CALIBRATING
    val levelText = when {
        assessment == null -> "준비"
        calibrating -> "익히는 중"
        else -> level.label
    }
    val subText = when {
        assessment == null -> "출발하면 달토끼가 함께해요"
        calibrating -> "${driverName?.let { "${it}의 " }.orEmpty()}운전 습관 익히는 중 ${((assessment.calibrationProgress) * 100).toInt()}%"
        else -> "졸음 위험도"
    }
    // 숫자는 판단 중일 때만 크게 (준비·습관 익히는 중에는 설명 글만)
    val riskValue = if (assessment != null && !calibrating) risk else null
    val mood = when {
        listening -> TokkiMood.LISTENING
        speaking -> TokkiMood.SPEAKING
        level >= FatigueLevel.WARNING -> TokkiMood.WORRIED
        game.recentBadge != null -> TokkiMood.HAPPY
        else -> TokkiMood.DEFAULT
    }
    val lens = if (level >= FatigueLevel.WARNING) TokkiColors.LensWarn else TokkiColors.Lens

    // 근거('주의' 이상, 알약 모양으로 하나씩)와 달토끼의 말 (대화 중이 아니면 단계별 짧은 한마디)
    // 근거는 운전 중에 한눈에 읽히게 중요한 것 2개만
    val reasons = if (level >= FatigueLevel.CAUTION) assessment?.reasons.orEmpty().take(2) else emptyList()
    val message = if (busy != null && lastLunaLine.isNotEmpty()) lastLunaLine else idleLine(level, assessment == null)
    val restButtons = danger || askingRest

    // 점수 연출: 정답(CORRECT)마다 링 빛 퍼짐, 점수판은 게임 중 + 점수를 얻은 뒤 3초
    // 화면이 처음 그려질 때 이미 있던 점수는 다시 연출하지 않음 (화면 전환 때 되풀이 방지)
    val initialSeq = remember { game.lastGain?.seq }
    val gain = game.lastGain?.takeIf { it.seq != initialSeq }
    val correctSeq = gain?.takeIf { it.reason == GainReason.CORRECT }?.seq
    var recentGain by remember { mutableStateOf(false) }
    LaunchedEffect(gain?.seq) {
        if (gain != null) {
            recentGain = true
            delay(3_000)
            recentGain = false
        }
    }
    val hudVisible = inGame || recentGain

    BoxWithConstraints(modifier.fillMaxSize().background(LunaColors.Background)) {
        // Row/Column 안에서는 maxWidth·maxHeight를 바로 못 쓰므로(DSL 범위 제한) 먼저 꺼내 둠
        val maxW = maxWidth
        val maxH = maxHeight
        val wide = maxW >= 860.dp && maxW > maxH * 1.25f
        val tight = maxH < 560.dp || maxW < 440.dp
        val hPad = if (tight) 16.dp else 40.dp
        val vPad = if (tight) 10.dp else 28.dp
        val headerH = if (tight) 52.dp else 76.dp
        val buttonH = if (tight) 64.dp else 120.dp
        val gap = if (tight) 10.dp else 16.dp
        val restCardH = if (tight) 64.dp else 96.dp
        // 근거가 2개면 달토끼 말은 2줄 (오른쪽 칸 높이를 넘지 않게. 에뮬레이터 녹화 화면에서 아래 상태 줄이 잘리던 문제)
        val msgLines = if (tight || restButtons || reasons.size >= 2) 2 else 3
        // 점수판 자리: 늘 같은 높이로 비워 둠 → 점수판·"게임 끝!"이 근거·말과 겹치지 않고, 나타나도 화면이 움직이지 않음
        // '위험' 단계는 휴게소 카드 자리가 필요하고 휴식 안내에 집중하도록 점수판 자리를 없앰
        val hudSlotH = if (danger) 0.dp else if (tight) 52.dp else 68.dp
        val hudSlot: @Composable (Alignment) -> Unit = { align ->
            Box(Modifier.fillMaxWidth().height(hudSlotH).alpha(if (danger) 0f else 1f), contentAlignment = align) {
                if (hudVisible && !danger) {
                    ScoreHud(
                        points = game.tripPoints, combo = game.combo,
                        gain = gain?.takeIf { it.reason == GainReason.CORRECT || it.reason == GainReason.SAFETY },
                        animate = !restricted,
                        shards = game.shards,
                    )
                }
                // 게임이 끝나면 "게임 끝! +420점 · 최고 4연속" (점수판 자리 위에 덮어 2초). 실제 주행 화면 제한이면 말로만
                if (!restricted) GameEndSummary(inGame, gain, animate = true)
            }
        }

        val buttons: @Composable () -> Unit = {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
                // 넓은 화면: 마이크를 버튼 줄 맨 앞에 (손이 한곳으로 가게, 머리글 줄은 없앰)
                if (wide && !restButtons) MicButton(listening, buttonH, onTalk)
                if (restButtons) {
                    PrimaryButton("경로에 추가", if (danger) LunaColors.Warning else LunaColors.Moon, Modifier.weight(1.4f), buttonH, tight, onClick = onAcceptRest)
                    SecondaryButton("괜찮아요", LunaColors.Outline, Modifier.weight(1f), buttonH, tight, onClick = onDeclineRest)
                } else {
                    PrimaryButton(
                        // 게임 중이면 [게임 그만], 아니면 게임 고르기 창 ('경고'면 짧은 게임만 고를 수 있음)
                        if (inGame) "게임 그만" else if (level >= FatigueLevel.WARNING) "짧은 게임" else "말놀이 게임",
                        LunaColors.Moon,
                        Modifier.weight(1.4f), buttonH, tight,
                        onClick = { if (inGame) onStopGame() else picking = true },
                    )
                    SecondaryButton("휴게소", LunaColors.Outline, Modifier.weight(1f), buttonH, tight, onClick = onRest)
                }
            }
        }
        val messageBlock: @Composable (Boolean) -> Unit = { big ->
            // A안(달빛 미니멀): 근거 → 달토끼 말(큰 글씨, 이름표 없음) → 상태 한 줄 → (위험) 휴게소 카드
            // 넓은 화면(big): 칸 높이를 꽉 채우고 아래에 붙임. 근거·상태 줄·휴게소 카드는 제 높이를 먼저 받고,
            // 달토끼 말 카드만 남는 높이 안에서 줄어듦 → 어떤 조합에서도 상태 줄이 눌려 잘리지 않음
            Column(
                verticalArrangement = Arrangement.spacedBy(gap, Alignment.Bottom),
                modifier = if (big) Modifier.fillMaxWidth().fillMaxHeight() else Modifier.fillMaxWidth(),
            ) {
                if (reasons.isNotEmpty()) ReasonPills(reasons, levelColor, tight)
                Column(
                    Modifier
                        .fillMaxWidth()
                        .then(if (big) Modifier.weight(1f, fill = false) else Modifier)
                        .clip(RoundedCornerShape(if (tight) 24.dp else 32.dp))
                        .background(LunaColors.Surface)
                        .padding(horizontal = if (tight) 16.dp else 36.dp, vertical = if (tight) 12.dp else 24.dp),
                    verticalArrangement = Arrangement.spacedBy(if (tight) 4.dp else 12.dp),
                ) {
                    Text(
                        message,
                        color = LunaColors.TextPrimary,
                        fontSize = if (tight) 19.sp else if (big) 36.sp else 30.sp,
                        fontWeight = FontWeight.Medium,
                        lineHeight = if (tight) 25.sp else if (big) 52.sp else 43.sp,
                        // 위험 단계는 휴게소 카드·버튼 자리를 위해 2줄.
                        // 말이 짧아도 같은 줄 수 자리를 차지 → 말 길이에 따라 화면이 흔들리지 않음
                        maxLines = msgLines,
                        minLines = msgLines,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // 운전자 대답은 같은 카드 안 오른쪽에. 말풍선이 없을 때도 자리는 비워 둠(흔들림 방지)
                    Box(Modifier.alpha(if (driverLine != null) 1f else 0f)) { DriverBubble(driverLine ?: " ", tight) }
                }
                StatusChip(
                    assessment = assessment, game = game, restArea = restArea, danger = danger, height = if (tight) 42.dp else 52.dp, tight = tight,
                    inCall = inCall,
                    // 출발 직후 3분: 누적 습관으로 바로 판단 중임을 알려 줌
                    habitNote = if (habitTrips > 0 && (assessment?.continuousDriveMin ?: 99f) < 3f) {
                        "${driverName ?: "운전자"}의 습관 ${habitTrips}회 기억 · 바로 판단해요"
                    } else null,
                )
                if (danger) RestCard(restArea, height = restCardH, tight = tight)
            }
        }

        Column(Modifier.fillMaxSize().padding(horizontal = hPad, vertical = vPad)) {
            // 좁은 화면만 머리글 줄(이름·마이크). 넓은 화면은 이름을 왼쪽 위에 작게, 마이크는 버튼 줄로
            if (!wide) {
                Header(listening = listening, onTalk = onTalk, height = headerH, tight = tight, driverName = driverName, startInset = headerStartInset)
                Spacer(Modifier.height(gap / 2))
            }
            if (wide) {
                val ringSize = minOf(maxH - vPad * 2 - 230.dp, (maxW - hPad * 2) * 0.36f, 400.dp).coerceAtLeast(180.dp)
                Row(
                    Modifier.fillMaxWidth().weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(56.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        if (driverName != null) {
                            Row(
                                Modifier.align(Alignment.TopStart).padding(start = headerStartInset),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Icon(LunaIcons.Moon, contentDescription = null, tint = LunaColors.Moon, modifier = Modifier.size(28.dp))
                                Text(driverName, color = LunaColors.TextSecondary, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                            }
                        }
                        Column(
                            Modifier.align(Alignment.Center),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(20.dp),
                        ) {
                            // 정답이면 링 둘레에 달빛이 한 번 퍼짐
                            Box(contentAlignment = Alignment.Center) {
                                MoonGlow(game.combo >= 5, ringSize * 1.12f)
                                RingPulse(correctSeq, ringSize * 1.15f, LunaColors.Moon, animate = !restricted, rings = if ((gain?.combo ?: 0) >= 3) 2 else 1)
                                TokkiRing(mood = mood, ringColor = ringTint, progress = if (assessment == null) null else risk / 100f, size = ringSize, listening = listening, lens = lens, accessory = accessory)
                                ComboBurst(gain, animate = !restricted, modifier = Modifier.align(Alignment.TopCenter).offset(y = (-30).dp))
                            }
                            LevelBlock(levelText, levelColor, riskValue, subText, big = true, centered = true, drop = riskDrop, animate = !restricted)
                        }
                    }
                    // 오른쪽: 버튼은 맨 아래 제자리에 고정, 상태·근거·말은 그 위에서 아래쪽으로 붙임
                    // (말 길이·근거 개수가 바뀌어도 버튼이 오르내리지 않음)
                    Column(Modifier.weight(1.15f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(gap)) {
                        hudSlot(Alignment.CenterEnd)
                        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.BottomCenter) {
                            messageBlock(true)
                        }
                        Spacer(Modifier.height(4.dp))
                        buttons()
                    }
                }
            } else {
                // 좁은 화면(휴대폰·분할 화면): 위에서 아래로
                val fixed = headerH + vPad * 2 + buttonH + hudSlotH + (if (danger) restCardH else 0.dp) + (if (tight) 170.dp else 260.dp)
                hudSlot(Alignment.Center)
                val ringSize = minOf(maxH - fixed, (maxW - hPad * 2) * 0.55f, 340.dp).coerceAtLeast(120.dp)
                Column(
                    Modifier.fillMaxWidth().weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        Box(contentAlignment = Alignment.Center) {
                            MoonGlow(game.combo >= 5, ringSize * 1.12f)
                            RingPulse(correctSeq, ringSize * 1.15f, LunaColors.Moon, animate = !restricted, rings = if ((gain?.combo ?: 0) >= 3) 2 else 1)
                            TokkiRing(mood = mood, ringColor = ringTint, progress = if (assessment == null) null else risk / 100f, size = ringSize, listening = listening, lens = lens, accessory = accessory)
                            ComboBurst(gain, animate = !restricted, modifier = Modifier.align(Alignment.TopCenter).offset(y = (-30).dp))
                        }
                        LevelBlock(levelText, levelColor, riskValue, subText, big = false, centered = false, drop = riskDrop, animate = !restricted)
                    }
                    messageBlock(false)
                    buttons()
                }
            }
        }
    }
}

/**
 * 상태 글씨: 단계("좋아요/주의/경고/위험")를 가장 크게, 그 아래 "졸음 위험도 44"는 숫자만 굵게.
 */
@Composable
private fun LevelBlock(
    levelText: String, levelColor: Color, risk: Int?, subText: String, big: Boolean, centered: Boolean,
    drop: RiskDrop? = null, animate: Boolean = true,
) {
    Column(horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start) {
        // A안: 상태 글씨를 가장 크게(104sp), 위험도 숫자도 크게(48sp)
        // "익히는 중"처럼 긴 글은 한 줄에 들어가게 조금 작게
        val longText = levelText.length > 3
        Text(
            levelText, color = levelColor,
            fontSize = if (big) (if (longText) 80.sp else 104.sp) else 44.sp,
            lineHeight = if (big) 108.sp else 50.sp,
            fontWeight = FontWeight.Bold, letterSpacing = if (big) (-2).sp else 0.sp, maxLines = 1,
        )
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(subText, color = LunaColors.TextSecondary, fontSize = if (big) 26.sp else 18.sp, modifier = Modifier.padding(bottom = if (big) 6.dp else 2.dp))
            if (risk != null) {
                // 행동 덕분에 실제로 내려가면 숫자 옆에 "−7 · 빠른 대답"
                RiskNumber(risk, drop, fontSize = if (big) 48.sp else 26.sp, animate = animate)
            }
        }
    }
}

/** 판단 근거를 알약 모양으로 하나씩 (한눈에 읽히게) */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReasonPills(reasons: List<String>, levelColor: Color, tight: Boolean) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        reasons.forEach { r ->
            Row(
                Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(LunaColors.SurfaceHigh)
                    .padding(horizontal = if (tight) 12.dp else 22.dp, vertical = if (tight) 6.dp else 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(Modifier.size(if (tight) 10.dp else 12.dp).clip(CircleShape).background(levelColor))
                Text(r, color = LunaColors.TextPrimary, fontSize = if (tight) 16.sp else 26.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** 운전자 말풍선: 달토끼 카드 안 오른쪽, 다른 색 (소리가 없어도 대화가 읽히게) */
@Composable
private fun DriverBubble(text: String, tight: Boolean) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Row(
            Modifier
                .clip(RoundedCornerShape(topStart = 22.dp, topEnd = 6.dp, bottomStart = 22.dp, bottomEnd = 22.dp))
                .background(LunaColors.Context.copy(alpha = 0.18f))
                .padding(horizontal = if (tight) 12.dp else 16.dp, vertical = if (tight) 5.dp else 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(LunaIcons.Person, contentDescription = null, tint = LunaColors.Context, modifier = Modifier.size(if (tight) 18.dp else 22.dp))
            Text("운전자", color = LunaColors.Context, fontSize = if (tight) 15.sp else 17.sp, fontWeight = FontWeight.Bold)
            Text(text, color = LunaColors.TextPrimary, fontSize = if (tight) 17.sp else 22.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** 대화 중이 아닐 때 달토끼의 한마디 (단계별, 짧게) */
private fun idleLine(level: FatigueLevel, notStarted: Boolean): String = when {
    notStarted -> "출발하면 옆자리에서 함께할게."
    level == FatigueLevel.DANGER -> "지금은 꼭 쉬어야 해. 가까운 휴게소로 가자."
    level == FatigueLevel.WARNING -> "졸음 신호가 보여. 쉬어가거나 반응 게임으로 잠 깨우자."
    level == FatigueLevel.CAUTION -> "조금 피곤해 보여. 졸리면 언제든 불러줘."
    else -> "오늘도 편안하게 가자. 졸리면 언제든 불러줘."
}

@Composable
private fun Header(listening: Boolean, onTalk: () -> Unit, height: Dp, tight: Boolean, driverName: String? = null, startInset: Dp = 0.dp) {
    Row(Modifier.fillMaxWidth().height(height).padding(start = startInset), verticalAlignment = Alignment.CenterVertically) {
        Icon(LunaIcons.Moon, contentDescription = null, tint = LunaColors.Moon, modifier = Modifier.size(if (tight) 24.dp else 30.dp))
        Spacer(Modifier.width(10.dp))
        // 앱 이름은 빼고 지금 운전자만 (게스트면 비움)
        Text(
            driverName.orEmpty(),
            color = LunaColors.TextPrimary,
            fontSize = if (tight) 22.sp else 28.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        IconButton(
            onClick = onTalk,
            modifier = Modifier
                .size(height)
                .then(if (listening) Modifier.border(2.dp, LunaColors.Moon, CircleShape) else Modifier),
            colors = IconButtonDefaults.iconButtonColors(containerColor = LunaColors.SurfaceHigh),
        ) {
            Icon(LunaIcons.Mic, contentDescription = if (listening) "듣고 있어요" else "달토끼 부르기", tint = LunaColors.Moon, modifier = Modifier.size(if (tight) 26.dp else 36.dp))
        }
    }
}

/**
 * 상태 한 줄. 우선순위:
 * 새 배지 → 통화 중 → 바쁜 순간(말 걸기 대기) → (출발 직후) 누적 습관으로 판단 중 → 잠김(졸음 신호 강함) → 깨어 있음 확인 → 환기 안내 → 연속 운전·다음 휴게소
 */
@Composable
private fun StatusChip(
    assessment: FatigueAssessment?,
    game: GameUi,
    restArea: RestArea?,
    danger: Boolean,
    height: Dp,
    tight: Boolean,
    inCall: Boolean = false,
    habitNote: String? = null,
) {
    val confirmed = ((assessment?.bonus ?: 0f) * 100).roundToInt()
    val blocked = assessment != null && assessment.bonusBlocked && assessment.level >= FatigueLevel.WARNING
    val recircMin = assessment?.recirculationMin
    val driverBusy = assessment?.driverBusy
    val co2 = assessment?.co2Ppm
    val stuffy = (recircMin != null && recircMin >= RECIRC_CHIP_MIN) || (co2 != null && co2 >= CabinAirModel.SUGGEST_PPM)
    val showFreshAir = stuffy && assessment?.recirculationMin != null &&
        (assessment?.level ?: FatigueLevel.NORMAL) >= FatigueLevel.CAUTION
    when {
        game.recentBadge != null -> Chip(
            icon = LunaIcons.Medal,
            text = LunaLines.newBadge(game.recentBadge),
            color = LunaColors.Moon,
            background = LunaColors.Moon.copy(alpha = 0.14f),
            height = height, tight = tight,
        )
        inCall -> Chip(
            icon = LunaIcons.Phone,
            text = "통화 중 · 달토끼는 기다려요",
            color = LunaColors.TextPrimary,
            background = LunaColors.SurfaceHigh,
            height = height, tight = tight,
        )
        driverBusy != null -> Chip(
            icon = LunaIcons.Clock,
            text = "${driverBusy.label} 중 · 달토끼는 잠깐 기다려요",
            color = LunaColors.TextPrimary,
            background = LunaColors.SurfaceHigh,
            height = height, tight = tight,
        )
        habitNote != null -> Chip(
            icon = LunaIcons.Person,
            text = habitNote,
            color = LunaColors.Normal,
            background = LunaColors.Normal.copy(alpha = 0.14f),
            height = height, tight = tight,
        )
        blocked -> Chip(
            icon = LunaIcons.Lock,
            text = "졸음 신호가 강해요 · 게임보다 휴식이 먼저",
            color = LunaColors.TextPrimary,
            background = if (danger) LunaColors.DangerChip else LunaColors.SurfaceHigh,
            height = height, tight = tight,
        )
        confirmed >= 1 -> Chip(
            icon = LunaIcons.Check,
            text = "깨어 있음 확인 +$confirmed" + if (game.combo > 0) " · ${game.combo}연속 정답" else "",
            color = LunaColors.Normal,
            background = LunaColors.Normal.copy(alpha = 0.14f),
            height = height, tight = tight,
        )
        showFreshAir -> Chip(
            icon = LunaIcons.Wind,
            text = if (co2 != null) LunaLines.freshAirChipCo2(co2.toInt()) else LunaLines.freshAirChip(recircMin!!.toInt()),
            color = LunaColors.Context,
            background = LunaColors.Context.copy(alpha = 0.14f),
            height = height, tight = tight,
        )
        else -> {
            val min = assessment?.continuousDriveMin ?: 0f
            val h = (min / 60).toInt()
            val m = (min % 60).toInt()
            val drive = if (h > 0) "연속 운전 ${h}시간 ${m}분" else "연속 운전 ${m}분"
            // 졸음 예보: 권장 휴식까지 남은 시간 (새벽·수면 부족이면 간격이 짧아짐)
            val due = assessment?.restDueInMin
            val plan = when {
                due == null -> ""
                due <= 0f -> " · 쉬어갈 시간이에요"
                due <= 60f -> " · 휴식 권장까지 ${due.roundToInt()}분"
                else -> ""
            }
            val next = if (tight && plan.isNotEmpty()) "" else restArea?.let { " · 휴게소 ${it.distanceKm}km" }.orEmpty()
            // A안: 평소 상태는 배경 없이 흐린 글씨 한 줄 (중요한 알림만 색 칩)
            Chip(icon = LunaIcons.Clock, text = drive + plan + next, color = LunaColors.TextSecondary, background = Color.Transparent, height = height, tight = tight)
        }
    }
}

@Composable
private fun Chip(icon: ImageVector, text: String, color: Color, background: Color, height: Dp, tight: Boolean) {
    Row(
        Modifier
            .height(height)
            .clip(RoundedCornerShape(999.dp))
            .background(background)
            .padding(horizontal = if (tight) 14.dp else 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(if (tight) 20.dp else 26.dp))
        Text(text, color = color, fontSize = if (tight) 18.sp else 24.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun RestCard(restArea: RestArea?, height: Dp, tight: Boolean) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(if (tight) 20.dp else 28.dp))
            .background(LunaColors.DangerInner)
            .padding(horizontal = if (tight) 14.dp else 26.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(LunaIcons.Coffee, contentDescription = null, tint = LunaColors.TextPrimary, modifier = Modifier.size(if (tight) 30.dp else 40.dp))
        Column(Modifier.weight(1f)) {
            Text(
                restArea?.let { "${it.name} · ${it.distanceKm} km" } ?: "가까운 휴게소·졸음쉼터",
                color = LunaColors.TextPrimary,
                fontSize = if (tight) 19.sp else 30.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // 10분 쉬면 받는 각성 점수(가장 큰 보상)를 단위와 함께
            Text("10분 쉬면 위험도 0 · +500점", color = LunaColors.TextOnDanger, fontSize = if (tight) 16.sp else 24.sp, maxLines = 1)
        }
    }
}

@Composable
internal fun ProgressBar(progress: Float, color: Color, track: Color = LunaColors.SurfaceHigh) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(10.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(track),
    ) {
        Box(
            Modifier
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .height(10.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(color),
        )
    }
}

/** 넓은 화면 버튼 줄 맨 앞의 둥근 마이크 버튼 (듣는 중이면 달빛 테두리) */
@Composable
private fun MicButton(listening: Boolean, size: Dp, onTalk: () -> Unit) {
    IconButton(
        onClick = onTalk,
        modifier = Modifier
            .size(size)
            .then(if (listening) Modifier.border(3.dp, LunaColors.Moon, CircleShape) else Modifier),
        colors = IconButtonDefaults.iconButtonColors(containerColor = LunaColors.SurfaceHigh),
    ) {
        Icon(LunaIcons.Mic, contentDescription = if (listening) "듣고 있어요" else "달토끼 부르기", tint = LunaColors.Moon, modifier = Modifier.size(44.dp))
    }
}

@Composable
private fun PrimaryButton(
    text: String,
    color: Color,
    modifier: Modifier,
    height: Dp,
    tight: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(height),
        shape = RoundedCornerShape(height / 2),
        contentPadding = PaddingValues(horizontal = 8.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = color,
            contentColor = LunaColors.Background,
            disabledContainerColor = color.copy(alpha = 0.45f),
            disabledContentColor = LunaColors.Background,
        ),
    ) {
        Text(text, fontSize = if (tight) 21.sp else 34.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
private fun SecondaryButton(text: String, outline: Color, modifier: Modifier, height: Dp, tight: Boolean, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(height),
        shape = RoundedCornerShape(height / 2),
        border = BorderStroke(3.dp, outline),
        contentPadding = PaddingValues(horizontal = 8.dp),
    ) {
        Text(text, color = LunaColors.TextPrimary, fontSize = if (tight) 20.sp else 34.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}
