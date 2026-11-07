package app.daltokki.drive.ui.screens

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.offset
import app.daltokki.drive.ui.components.RewardJackpot
import app.daltokki.drive.voice.SoundEffect
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import app.daltokki.drive.engine.GainReason
import app.daltokki.drive.ui.components.Celebration
import app.daltokki.drive.ui.components.ScoreHud
import app.daltokki.drive.ui.components.gainTitle
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.graphics.graphicsLayer
import app.daltokki.drive.ui.components.ComboBurst
import app.daltokki.drive.ui.components.GameEndSummary
import app.daltokki.drive.ui.components.MoonGlow
import app.daltokki.drive.ui.components.RingPulse
import app.daltokki.drive.ui.components.gainColor
import kotlin.math.sin
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.daltokki.drive.domain.dialog.LunaLines
import app.daltokki.drive.domain.dialog.TalkLevel
import app.daltokki.drive.domain.game.GameKind
import app.daltokki.drive.domain.model.DriveState
import app.daltokki.drive.domain.model.FatigueLevel
import app.daltokki.drive.domain.model.FatigueAssessment
import app.daltokki.drive.domain.model.TripStats
import app.daltokki.drive.engine.DriverUi
import app.daltokki.drive.engine.GameUi
import app.daltokki.drive.engine.HabitMismatchUi
import app.daltokki.drive.ui.components.LunaIcons
import app.daltokki.drive.ui.components.TokkiMood
import app.daltokki.drive.ui.components.TokkiRing
import app.daltokki.drive.ui.theme.LunaColors
import kotlinx.coroutines.delay

/**
 * 정차 화면 — 차에서 매일 보는 일상 화면 (시연 버튼 없음. 시연은 왼쪽 위 '데모' → 데모 패널에서).
 *
 * 세 덩어리만, 글자는 크게(본문 20sp 이상), 화면 세로 가운데에 세 칸 높이를 맞춰서:
 * 1. 달토끼 + [운전자 변경] + [달토끼야] [말놀이 게임 → 게임 고르기 창 / 게임 중이면 게임 그만]  (달토끼가 방금 한 말은 20초만 보임)
 *    (휴식 중이면 꾸벅 달토끼 + 10분 진행 + [커피냅 15분] [운행 종료])
 * 2. 오늘 컨디션(어젯밤 잠 → 오늘의 휴식 간격)  (휴식 중이면 스트레칭)
 * 3. 나의 기록: 달 레벨 · 휴게소 도장 · 배지 · 지난 운행을 큰 숫자로 한 카드에
 * 말 수 설정은 운전자 칩 창 안으로 옮겼습니다(운전자별 설정).
 * 폭이 좁으면 2단 → 1단으로 바뀌고 세로로 스크롤됩니다.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ParkedScreen(
    assessment: FatigueAssessment?,
    stats: TripStats,
    lastTrip: TripStats?,
    game: GameUi,
    talkLevel: TalkLevel,
    restElapsedMin: Float,
    lastLunaLine: String,
    speaking: Boolean,
    listening: Boolean,
    /** 게임 고르기 창에서 고른 게임 (null = 알아서 골라 줘) */
    onPickGame: (GameKind?) -> Unit,
    onTalk: () -> Unit,
    onEndTrip: () -> Unit,
    onTalkLevel: (TalkLevel) -> Unit,
    modifier: Modifier = Modifier,
    sleepHours: Float? = null,
    restIntervalMin: Int = 120,
    onSleep: (Float) -> Unit = {},
    /** 운전자 (차 한 대를 여러 명이) */
    driver: DriverUi? = null,
    drivers: List<DriverUi> = emptyList(),
    habitMismatch: HabitMismatchUi? = null,
    /** 운전자 고르기·관리를 할 수 있는지 (시연 중·운행 중에는 못 바꿈) */
    driverEditable: Boolean = true,
    onSelectDriver: (String) -> Unit = {},
    onAddDriver: (String) -> Unit = {},
    onRenameDriver: (String, String) -> Unit = { _, _ -> },
    onRemoveDriver: (String) -> Unit = {},
    onResetHabit: (String) -> Unit = {},
    /** 습관이 평소와 달랐을 때: 운전자 id(그 사람 습관으로) 또는 null(학습 안 함) */
    onResolveMismatch: (String?) -> Unit = {},
    onClearMemory: (String) -> Unit = {},
    /** 커피냅 남은 시간(분). 커피냅 중이 아니면 null */
    napRemainingMin: Float? = null,
    /** 휴식 중 '커피냅 15분' 버튼 */
    onNap: () -> Unit = {},
    /** 처음 실행: 운전자 고르기 창을 한 번 띄움 */
    askDriverOnStart: Boolean = false,
    onDriverPrompted: () -> Unit = {},
    /** 게임 중이면 [게임 그만] */
    inGame: Boolean = false,
    onStopGame: () -> Unit = {},
    /** 기록 카드 '지난 운행'을 누르면 운행 리포트 */
    onOpenReport: () -> Unit = {},
    /** 달빛 선물 상자 열기 (쉬고 받은 상자가 있을 때) */
    onOpenGift: () -> Unit = {},
    /** 달토끼 꾸미기 바꾸기 (null = 벗기) */
    onWear: (String?) -> Unit = {},
    /** 보상 연출에 맞춘 효과음 (조각 '띵', 팡파르 등) */
    onSound: (SoundEffect, Int) -> Unit = { _, _ -> },
) {
    var stampBookOpen by remember { mutableStateOf(false) }
    if (stampBookOpen) StampBookDialog(game, onDismiss = { stampBookOpen = false })
    // 점수 연출: 정답은 점수판(위 가운데), 휴식 완료·도장·커피냅·달 레벨 상승은 별가루 축하
    // 화면이 처음 그려질 때 이미 있던 점수는 다시 연출하지 않음 (화면 전환 때 되풀이 방지)
    val initialSeq = remember { game.lastGain?.seq }
    val gain = game.lastGain?.takeIf { it.seq != initialSeq }
    var recentCorrect by remember { mutableStateOf(false) }
    LaunchedEffect(gain?.seq) {
        // 정답·작은 안전 행동 점수(+100 미만)는 점수판으로
        if (gain != null && (gain.reason == GainReason.CORRECT || (gain.reason == GainReason.SAFETY && gain.points < 100))) {
            recentCorrect = true
            delay(3_000)
            recentCorrect = false
        }
    }
    val tierName = MOON_TIERS.last { game.lifetimePoints >= it.from }.name
    var prevTier by remember { mutableStateOf(tierName) }
    var levelUp by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(tierName) {
        if (tierName != prevTier) {
            levelUp = tierName
            prevTier = tierName
        }
    }
    var picking by remember { mutableStateOf(false) }
    if (picking) {
        GamePickerDialog(
            level = assessment?.level ?: FatigueLevel.NORMAL,
            onPick = { picking = false; onPickGame(it) },
            onDismiss = { picking = false },
        )
    }
    val resting = assessment?.driveState == DriveState.RESTING
    var pickerOpen by remember { mutableStateOf(false) }
    var assignMode by remember { mutableStateOf(false) }
    // 처음 실행이면 운전자 창을 한 번 (시연 중·운행 중에는 띄우지 않음)
    LaunchedEffect(askDriverOnStart, driverEditable) {
        if (askDriverOnStart && driverEditable) {
            assignMode = false
            pickerOpen = true
            onDriverPrompted()
        }
    }
    // 달토끼가 방금 한 말만 잠깐 (늘 떠 있는 안내 문구는 두지 않음)
    var lineVisible by remember { mutableStateOf(false) }
    LaunchedEffect(lastLunaLine, speaking, listening) {
        lineVisible = lastLunaLine.isNotEmpty()
        if (lineVisible && !speaking && !listening) {
            delay(LINE_SHOW_MS)
            lineVisible = false
        }
    }
    if (pickerOpen) {
        DriverDialog(
            current = driver,
            drivers = drivers,
            assignMode = assignMode,
            talkLevel = talkLevel,
            onTalkLevel = onTalkLevel,
            onSelect = { id ->
                if (assignMode) onResolveMismatch(id) else onSelectDriver(id)
                pickerOpen = false
            },
            onAdd = onAddDriver,
            onRename = onRenameDriver,
            onRemove = onRemoveDriver,
            onResetHabit = onResetHabit,
            onClearMemory = onClearMemory,
            onWear = onWear,
            onDismiss = {
                if (assignMode) onResolveMismatch(null)
                pickerOpen = false
            },
        )
    }
    val report = if (resting) stats else lastTrip
    val hasReport = report != null && report.tripStartMs > 0

    BoxWithConstraints(modifier.fillMaxSize().background(LunaColors.Background)) {
        val narrow = maxWidth < 600.dp
        val pad = if (narrow) 14.dp else 32.dp
        val avail = maxWidth - pad * 2
        // 차량 화면(배율 적용 후 폭 약 1,120dp)에서도 3칸이 되게 (예전 340dp는 2칸으로 넘쳐 아래가 잘렸음)
        val minCol = 300.dp
        val columns = when {
            avail >= minCol * 3 + pad * 2 -> 3
            avail >= minCol * 2 + pad -> 2
            else -> 1
        }
        // 반올림 오차로 마지막 단이 줄바꿈되지 않도록 1dp 여유
        val colWidth = minOf((avail - pad * (columns - 1)) / columns - 1.dp, 520.dp)
        // 달토끼 크기는 화면 세로에 맞춰 (자동 시연 맨 위 자막 줄이 있는 에뮬레이터 녹화 화면에서도 스크롤 없이 한 화면에)
        // 달토끼 칸에서 달토끼를 뺀 높이(이름·운전자·버튼·말 2줄 ≈ 360dp) + 점수판 자리 + 위아래 여백 ≈ 480dp
        val avatar = if (narrow) 160.dp else (maxHeight - 480.dp).coerceIn(200.dp, 260.dp)
        val vPad = if (narrow) 14.dp else 20.dp
        val screenHeight = maxHeight

        val lunaColumn: @Composable (Modifier) -> Unit = { m ->
            Column(
                m,
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            ) {
                val mood = when {
                    listening -> TokkiMood.LISTENING
                    speaking -> TokkiMood.SPEAKING
                    resting -> TokkiMood.SLEEPING
                    game.recentBadge != null -> TokkiMood.HAPPY
                    else -> TokkiMood.DEFAULT
                }
                // 점수를 받으면 달토끼가 통통 (정차 중에만)
                val bounce = remember { Animatable(0f) }
                LaunchedEffect(gain?.seq) {
                    if (gain != null) {
                        bounce.snapTo(0f)
                        bounce.animateTo(1f, tween(520))
                        bounce.snapTo(0f)
                    }
                }
                Box(contentAlignment = Alignment.Center) {
                    MoonGlow(game.combo >= 5, avatar * 1.12f)
                    // 정답이면 달빛 한 번, 휴식 완료면 두 겹으로
                    RingPulse(
                        gain?.seq, avatar * 1.15f, gainColor(gain), animate = true,
                        rings = if (gain?.reason == GainReason.REST || (gain?.combo ?: 0) >= 3) 2 else 1,
                    )
                    TokkiRing(
                        mood = mood,
                        ringColor = if (resting) LunaColors.Context else LunaColors.Moon,
                        progress = when {
                            napRemainingMin != null -> (1f - napRemainingMin / NAP_MIN).coerceIn(0f, 1f)
                            resting -> (restElapsedMin / 10f).coerceIn(0f, 1f)
                            else -> null
                        },
                        size = avatar,
                        listening = listening,
                        accessory = driver?.wearing,
                        modifier = Modifier.graphicsLayer {
                            val b = bounce.value
                            // 위로 두 번 통통 (두 번째는 작게)
                            val h = if (b < 0.55f) kotlin.math.sin(b / 0.55f * Math.PI).toFloat() else 0.4f * kotlin.math.sin((b - 0.55f) / 0.45f * Math.PI).toFloat()
                            translationY = -28f * h
                            scaleX = 1f + 0.04f * h; scaleY = 1f + 0.04f * h
                        },
                    )
                    ComboBurst(gain, animate = true, modifier = Modifier.align(Alignment.TopCenter).offset(y = (-30).dp))
                    // 안 연 달빛 선물 상자: 달토끼 오른쪽 아래 배지 (눌러서 열기, 자리를 차지하지 않아 화면이 흔들리지 않음)
                    val gifts = driver?.gifts ?: 0
                    if (gifts > 0) {
                        GiftBadge(gifts, Modifier.align(Alignment.BottomEnd), onClick = onOpenGift)
                    }
                }
                if (resting && napRemainingMin != null) {
                    // 커피냅: 15분 뒤 깨우고 반응 게임으로 다시 잼
                    Text("커피냅 중", color = LunaColors.Context, fontSize = 36.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "${kotlin.math.ceil(napRemainingMin).toInt()}분 뒤에 깨워 줄게요",
                        color = LunaColors.TextPrimary, fontSize = 22.sp, textAlign = TextAlign.Center,
                    )
                    BigButton("운행 종료", primary = false, modifier = Modifier.fillMaxWidth(), onClick = onEndTrip)
                } else if (resting) {
                    Text("휴식 중", color = LunaColors.Context, fontSize = 36.sp, fontWeight = FontWeight.Bold)
                    val left = (10f - restElapsedMin).coerceAtLeast(0f)
                    Text(
                        if (left > 0f) "${"%.0f".format(left)}분 더 쉬어요" else "충분히 쉬었어요! 출발해도 좋아요",
                        color = LunaColors.TextPrimary, fontSize = 22.sp, textAlign = TextAlign.Center,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                        BigButton("커피냅 15분", primary = true, modifier = Modifier.weight(1f), onClick = onNap)
                        BigButton("운행 종료", primary = false, modifier = Modifier.weight(1f), onClick = onEndTrip)
                    }
                } else {
                    Text("달토끼", color = LunaColors.Moon, fontSize = if (narrow) 34.sp else 48.sp, fontWeight = FontWeight.Bold)
                    DriverButton(driver, enabled = driverEditable, modifier = Modifier.fillMaxWidth()) { assignMode = false; pickerOpen = true }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                        BigButton("달토끼야", primary = true, modifier = Modifier.weight(1f), onClick = onTalk)
                        BigButton(
                            if (inGame) "게임 그만" else "말놀이 게임", primary = false, modifier = Modifier.weight(1f),
                            onClick = { if (inGame) onStopGame() else picking = true },
                        )
                    }
                    // 달토끼가 방금 한 말: 버튼 아래 2줄 자리를 늘 비워 둠 (말이 나타나고 사라져도 버튼이 움직이지 않게)
                    Text(
                        if (lineVisible) lastLunaLine else "",
                        color = LunaColors.TextPrimary,
                        fontSize = 24.sp, lineHeight = 32.sp, textAlign = TextAlign.Center,
                        minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        val conditionColumn: @Composable (Modifier, Boolean) -> Unit = { m, fill ->
            Column(m, verticalArrangement = Arrangement.spacedBy(18.dp)) {
                if (habitMismatch != null && !resting) {
                    HabitMismatchCard(
                        habitMismatch,
                        onConfirm = { driver?.let { onResolveMismatch(it.id) } },
                        onOther = { assignMode = true; pickerOpen = true },
                        onSkip = { onResolveMismatch(null) },
                    )
                }
                val cardMod = if (fill) Modifier.weight(1f) else Modifier
                if (resting) StretchCard(cardMod) else ConditionCard(sleepHours, restIntervalMin, onSleep, cardMod)
            }
        }
        val recordColumn: @Composable (Modifier, Boolean) -> Unit = { m, fill ->
            Column(m) {
                RecordCard(
                    driver = driver,
                    game = game,
                    report = report?.takeIf { hasReport },
                    resting = resting,
                    modifier = if (fill) Modifier.weight(1f) else Modifier,
                    onStamps = { stampBookOpen = true },
                    onReport = onOpenReport,
                )
            }
        }

        // 화면 세로 가운데에. 내용이 화면보다 길 때만 스크롤
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .heightIn(min = screenHeight)
                .padding(horizontal = pad, vertical = vPad),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // 점수판 자리: 세 칸 위에 늘 같은 높이로 비워 둠 → 점수판·"게임 끝!"이 카드 글자를 덮지 않고, 나타나도 화면이 움직이지 않음
            Box(Modifier.fillMaxWidth().height(HUD_SLOT_H), contentAlignment = Alignment.Center) {
                if (inGame || recentCorrect) {
                    ScoreHud(
                        points = game.tripPoints, combo = game.combo,
                        gain = gain?.takeIf { it.reason == GainReason.CORRECT || it.reason == GainReason.SAFETY },
                        animate = true, big = true, shards = game.shards,
                    )
                }
                // 게임이 끝나면 "게임 끝! +420점 · 최고 4연속" (같은 자리에 2초 덮어)
                GameEndSummary(inGame, gain, animate = true)
            }
            Spacer(Modifier.height(4.dp))
            if (columns == 3) {
                // 세 칸 높이를 맞춰 위·아래 선을 일치
                Row(
                    Modifier.height(IntrinsicSize.Max),
                    horizontalArrangement = Arrangement.spacedBy(pad),
                ) {
                    lunaColumn(Modifier.width(colWidth).fillMaxHeight())
                    conditionColumn(Modifier.width(colWidth).fillMaxHeight(), true)
                    recordColumn(Modifier.width(colWidth).fillMaxHeight(), true)
                }
            } else {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(pad, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(if (narrow) 16.dp else 24.dp),
                ) {
                    lunaColumn(Modifier.width(colWidth))
                    conditionColumn(Modifier.width(colWidth), false)
                    recordColumn(Modifier.width(colWidth), false)
                }
            }
        }
        // 휴식 완료·도장·커피냅·제때 쉬기·큰 안전 행동(+100 이상): 별가루 + 큰 "+700" (새 도장이면 "쾅" 먼저)
        // 달 레벨이 오르면 "볼록달이 됐어요!"
        // 휴식 완료(10분)는 '잭팟': 도장 쾅 → 달빛 조각 빨려 듦 → 내역 한 줄씩 → 합계 + 팡파르 → 레벨 업
        val restGain = gain?.takeIf { it.reason == GainReason.REST }
        RewardJackpot(restGain, levelUp = if (restGain != null) levelUp else null, onSound = onSound)
        val bigGain = gain?.takeIf { it.reason == GainReason.NAP || (it.reason == GainReason.SAFETY && it.points >= 100) }
        Celebration(
            key = if (restGain != null) null else levelUp?.let { "level-$it-${bigGain?.seq}" } ?: bigGain?.seq,
            points = bigGain?.points,
            label = listOfNotNull(bigGain?.let { gainTitle(it) }, levelUp?.let { "${it}이 됐어요!" }).joinToString(" · ").ifEmpty { null },
            color = gainColor(bigGain),
            stampName = bigGain?.stampName,
        )
    }
}

/** 정차 화면 맨 위 점수판 자리 높이 (큰 점수판 + 여유) */
private val HUD_SLOT_H = 72.dp

/** 안 연 달빛 선물 상자 배지: "선물 2" (살짝 흔들려 눈에 띔) */
@Composable
private fun GiftBadge(count: Int, modifier: Modifier, onClick: () -> Unit) {
    val wobble = androidx.compose.animation.core.rememberInfiniteTransition(label = "gift")
    val r by wobble.animateFloat(
        initialValue = -6f, targetValue = 6f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(tween(420), androidx.compose.animation.core.RepeatMode.Reverse),
        label = "giftR",
    )
    Row(
        modifier
            .graphicsLayer { rotationZ = r }
            .clip(RoundedCornerShape(999.dp))
            .background(LunaColors.Moon)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("선물 열기 $count", color = LunaColors.Background, fontSize = 22.sp, fontWeight = FontWeight.Black)
    }
}

@Composable
private fun BigButton(text: String, primary: Boolean, modifier: Modifier, onClick: () -> Unit) {
    if (primary) {
        Button(
            onClick = onClick,
            modifier = modifier.height(84.dp),
            shape = RoundedCornerShape(42.dp),
            colors = ButtonDefaults.buttonColors(containerColor = LunaColors.Moon, contentColor = LunaColors.Background),
            contentPadding = PaddingValues(horizontal = 6.dp),
        ) { Text(text, fontSize = 26.sp, fontWeight = FontWeight.Bold, maxLines = 1) }
    } else {
        OutlinedButton(
            onClick = onClick,
            modifier = modifier.height(84.dp),
            shape = RoundedCornerShape(42.dp),
            border = BorderStroke(2.dp, LunaColors.Outline),
            contentPadding = PaddingValues(horizontal = 6.dp),
        ) { Text(text, color = LunaColors.TextPrimary, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, maxLines = 1) }
    }
}

/** A안 카드: 큰 둥근 모서리, 넉넉한 안쪽 여백 */
@Composable
private fun CardBox(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(36.dp))
            .background(LunaColors.Surface)
            .padding(horizontal = 32.dp, vertical = 36.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) { content() }
}

@Composable
private fun CardTitle(text: String) {
    Text(text, color = LunaColors.Moon, fontSize = 28.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun StretchCard(modifier: Modifier = Modifier) {
    CardBox(modifier) {
        CardTitle("간단한 스트레칭")
        // 화면은 한 줄 요약만 (긴 설명은 달토끼가 말로)
        LunaLines.stretchShort.forEachIndexed { i, step ->
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${i + 1}", color = LunaColors.Context, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Text(step, color = LunaColors.TextPrimary, fontSize = 26.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/**
 * 오늘 컨디션(졸음 예보). 답했으면 "오늘은 / 120 분 / 마다 쉬어가요"를 크게만 보여 주고 [다시 고르기].
 * 아직 안 답했으면(또는 다시 고르기) 어젯밤 잠 버튼 4개.
 * (말 수 '수다'에서는 출발할 때 달토끼가 음성으로도 묻습니다)
 */
@Composable
private fun ConditionCard(sleepHours: Float?, restIntervalMin: Int, onSleep: (Float) -> Unit, modifier: Modifier = Modifier) {
    var choosing by remember(sleepHours) { mutableStateOf(sleepHours == null) }
    CardBox(modifier) {
        CardTitle("오늘 컨디션")
        if (choosing || sleepHours == null) {
            Text("어젯밤 몇 시간 잤어요?", color = LunaColors.TextPrimary, fontSize = 28.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                SLEEP_CHOICES.forEach { (label, hours) ->
                    val selected = sleepHours != null && sleepBucket(sleepHours) == hours
                    OutlinedButton(
                        onClick = { onSleep(hours); choosing = false },
                        modifier = Modifier.weight(1f).height(76.dp),
                        shape = RoundedCornerShape(38.dp),
                        border = BorderStroke(2.dp, if (selected) LunaColors.Moon else LunaColors.Outline),
                        contentPadding = PaddingValues(horizontal = 2.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = if (selected) LunaColors.Moon.copy(alpha = 0.14f) else LunaColors.Surface,
                        ),
                    ) {
                        Text(label, color = if (selected) LunaColors.Moon else LunaColors.TextPrimary, fontSize = 28.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    }
                }
            }
            Text("답하면 오늘 몇 분마다 쉬면 좋을지 알려 드려요", color = LunaColors.TextSecondary, fontSize = 22.sp, lineHeight = 30.sp)
        } else {
            Text("어젯밤 ${sleepLabel(sleepHours)} 잤어요", color = LunaColors.TextSecondary, fontSize = 24.sp)
            Column {
                Text("오늘은", color = LunaColors.TextPrimary, fontSize = 30.sp)
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("$restIntervalMin", color = LunaColors.Moon, fontSize = 104.sp, lineHeight = 108.sp, fontWeight = FontWeight.Bold, letterSpacing = (-3).sp)
                    Text("분", color = LunaColors.TextPrimary, fontSize = 36.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 14.dp))
                }
                Text("마다 쉬어가요", color = LunaColors.TextPrimary, fontSize = 30.sp)
            }
            OutlinedButton(
                onClick = { choosing = true },
                modifier = Modifier.height(60.dp),
                shape = RoundedCornerShape(30.dp),
                border = BorderStroke(2.dp, LunaColors.Outline),
                contentPadding = PaddingValues(horizontal = 26.dp),
            ) { Text("다시 고르기", color = LunaColors.TextSecondary, fontSize = 22.sp) }
        }
    }
}

/** 6.0 → "6시간", 7.5 → "7시간 이상", 4 → "4시간 이하" */
private fun sleepLabel(hours: Float): String = when (sleepBucket(hours)) {
    4f -> "4시간 이하"
    7.5f -> "7시간 이상"
    else -> "${sleepBucket(hours).toInt()}시간"
}

/** (버튼 글자, 저장할 시간) — 질문에 '몇 시간'이 있어 숫자만 크게 */
private val SLEEP_CHOICES = listOf("4↓" to 4f, "5" to 5f, "6" to 6f, "7↑" to 7.5f)

/** 음성으로 답한 시간(예: 5.5)을 가장 가까운 버튼으로 */
private fun sleepBucket(hours: Float): Float = when {
    hours < 4.5f -> 4f
    hours < 5.5f -> 5f
    hours < 6.5f -> 6f
    else -> 7.5f
}

/** 누적 각성 점수에 따른 달 레벨: 초승달 → 반달 → 볼록달 → 보름달 (달토끼의 달이 차오름) */
private data class MoonTier(val from: Int, val name: String, val fill: Float)

private val MOON_TIERS = listOf(
    MoonTier(0, "초승달", 0.25f),
    MoonTier(500, "반달", 0.5f),
    MoonTier(1_500, "볼록달", 0.75f),
    MoonTier(5_000, "보름달", 1f),
)

/**
 * 나의 기록 (운전자별, A안): 달 레벨 + 진행 막대 + 세 줄(휴게소 도장 · 배지 · 지난 운행)을 큰 숫자로.
 * 새 배지를 받으면 맨 위에 잠깐 크게.
 */
@Composable
private fun RecordCard(
    driver: DriverUi?,
    game: GameUi,
    report: TripStats?,
    resting: Boolean,
    modifier: Modifier = Modifier,
    onStamps: () -> Unit = {},
    onReport: () -> Unit = {},
) {
    val points = game.lifetimePoints
    val tier = MOON_TIERS.last { points >= it.from }
    val next = MOON_TIERS.firstOrNull { it.from > points }
    val progress = next?.let { (points - tier.from).toFloat() / (it.from - tier.from) } ?: 1f
    val earned = game.badges.count { it.earned }
    val who = driver?.takeUnless { it.guest }?.name
    CardBox(modifier) {
        CardTitle(if (who != null) "${who}의 기록" else "나의 기록")
        game.recentBadge?.let { badge ->
            // 새 배지: 카드가 뒤집히며 나타나고 빛이 한 번 지나감
            val flip = remember(badge) { Animatable(0f) }
            LaunchedEffect(badge) { flip.animateTo(1f, tween(900, easing = FastOutSlowInEasing)) }
            Row(
                Modifier
                    .graphicsLayer {
                        rotationY = 90f * (1f - (flip.value / 0.4f).coerceAtMost(1f))
                        cameraDistance = 12f * density
                    }
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(LunaColors.Moon.copy(alpha = 0.16f))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    LunaIcons.Medal, contentDescription = null, tint = LunaColors.Moon,
                    modifier = Modifier.size(30.dp).graphicsLayer { val sh = (flip.value - 0.4f).coerceIn(0f, 0.6f) / 0.6f; scaleX = 1f + 0.3f * sin(sh * 3.14f); scaleY = scaleX },
                )
                Text("새 배지 · $badge", color = LunaColors.Moon, fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        // 달 레벨
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            // 달 레벨이 오르면 달이 실제로 차오르는 모양으로
            val fill by animateFloatAsState(tier.fill, animationSpec = tween(1_200, easing = FastOutSlowInEasing), label = "moon")
            MoonPhase(fill, 68.dp)
            Column(Modifier.weight(1f)) {
                Text(tier.name, color = LunaColors.TextPrimary, fontSize = 40.sp, lineHeight = 46.sp, fontWeight = FontWeight.Bold)
                // 점수가 오르면 촤르륵 세기
                val shown by animateIntAsState(points, animationSpec = tween(900), label = "lifetime")
                Text("${"%,d".format(shown)}점", color = LunaColors.TextSecondary, fontSize = 24.sp)
            }
        }
        ProgressBar(progress, LunaColors.Moon, track = LunaColors.Track)
        Text(
            next?.let { "${it.name}까지 ${"%,d".format(it.from - points)}점" } ?: "보름달! 모든 단계를 채웠어요",
            color = LunaColors.TextSecondary, fontSize = 22.sp,
        )
        // 도장 · 배지 · 지난 운행: 한 줄에 하나씩 큰 숫자
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(LunaColors.Inner).padding(horizontal = 16.dp, vertical = 4.dp),
        ) {
            StatRow("휴게소 도장", "${game.stampPlaces}", if (game.stampTotal > 0) "/ ${game.stampTotal}곳" else "곳", onClick = onStamps)
            HorizontalDivider(color = LunaColors.SurfaceHigh)
            StatRow("배지", "$earned", "/ ${game.badges.size}")
            if (driver != null && !driver.guest) {
                HorizontalDivider(color = LunaColors.SurfaceHigh)
                StatRow("안전 운행", "${driver.streak}", "번 연속")
            }
            if (report != null) {
                HorizontalDivider(color = LunaColors.SurfaceHigh)
                StatRow(
                    if (resting) "이번 운행" else "지난 운행", "${report.drivingMs / 60_000L}분", "",
                    caption = "휴식 ${report.rests}번 · 최고 단계 ${report.maxLevel.label}",
                    onClick = if (resting) null else onReport,
                )
            }
        }
    }
}

@Composable
private fun StatRow(label: String, value: String, unit: String, caption: String? = null, onClick: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, color = LunaColors.TextSecondary, fontSize = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (caption != null) Text(caption, color = LunaColors.TextSecondary, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(value, color = LunaColors.TextPrimary, fontSize = 34.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        if (unit.isNotEmpty()) Text(unit, color = LunaColors.TextSecondary, fontSize = 18.sp, maxLines = 1)
        // 누를 수 있는 줄 표시 (도장첩·운행 리포트)
        if (onClick != null) Text("›", color = LunaColors.Moon, fontSize = 30.sp, fontWeight = FontWeight.Bold)
    }
}


@Composable
private fun MoonPhase(fill: Float, size: Dp) {
    // 겹치는 원이 밖으로 그려지지 않게 원 모양으로 자름
    Canvas(Modifier.size(size).clip(CircleShape)) {
        val r = this.size.minDimension / 2f
        drawCircle(LunaColors.Moon, radius = r)
        if (fill < 1f) {
            // 어두운 원을 오른쪽으로 비켜 겹쳐서 달의 모양을 만듭니다
            drawCircle(LunaColors.Track, radius = r, center = Offset(center.x + 2f * r * fill, center.y))
        }
    }
}

/** 커피냅 길이(분) — DialogCoordinator.NAP_MIN과 같게 */
private const val NAP_MIN = 15f

/** 달토끼가 방금 한 말을 정차 화면에 보여주는 시간 */
private const val LINE_SHOW_MS = 20_000L
