package app.daltokki.drive.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.daltokki.drive.domain.game.AwakeScore
import app.daltokki.drive.engine.GainReason
import app.daltokki.drive.engine.ScoreGain
import app.daltokki.drive.ui.theme.LunaColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/*
 * 게임 느낌 연출 (점수·콤보·안전 행동).
 * 원칙: 운전 중에는 짧고 조용하게(1초 안팎, 깜빡임 없음), 실제 주행 화면 제한이면(animate=false) 움직임 없이 숫자만,
 * 화려한 연출(별가루·큰 숫자·도장 쾅)은 정차 중에만.
 */

/** 안전 행동 색 (게임 점수의 달빛 노랑과 구분) */
val SafetyTeal = Color(0xFF3FD9C2)
private val ComboOrange = Color(0xFFFFA53D)
private val ComboCoral = Color(0xFFFF7A6B)
private val FastBlue = Color(0xFF8FD3FF)
private val Gold = Color(0xFFFFC94A)

/** "×2" 같은 배율 글자 */
fun multiplierText(m: Float): String = if (m % 1f == 0f) "×${m.toInt()}" else "×$m"

fun gainColor(g: ScoreGain?): Color = if (g?.reason == GainReason.SAFETY) SafetyTeal else LunaColors.Moon

/** 숫자가 자리마다 위로 굴러 올라가며 바뀜 (1,300 → 1,375) */
@Composable
fun RollingNumber(value: Int, fontSize: TextUnit, color: Color, animate: Boolean, suffix: String = "") {
    val text = "%,d".format(value)
    Row(verticalAlignment = Alignment.CenterVertically) {
        text.forEachIndexed { i, ch ->
            if (!animate) {
                Text(ch.toString(), fontSize = fontSize, color = color, fontWeight = FontWeight.Bold)
            } else {
                AnimatedContent(
                    targetState = ch,
                    transitionSpec = { slideInVertically(tween(300)) { it } togetherWith slideOutVertically(tween(300)) { -it } },
                    label = "digit$i",
                ) { c -> Text(c.toString(), fontSize = fontSize, color = color, fontWeight = FontWeight.Bold) }
            }
        }
        if (suffix.isNotEmpty()) Text(suffix, fontSize = fontSize, color = color, fontWeight = FontWeight.Bold)
    }
}

/**
 * 점수판: 이번 운행 점수(자리마다 굴러감) + 콤보 배지("3연속 ×2", 톡 튀어나옴).
 * 점수를 얻으면 "+75"가 왼쪽 아래에서 날아와 점수판에 합쳐지고, 합쳐지는 순간 점수판이 살짝 커졌다 돌아옴.
 * 5연속부터는 금색 테두리(달빛 모드). 안전 행동이면 청록색 "+30 · 외기 전환".
 * @param animate false면(실제 주행 화면 제한) 움직임 없이 숫자만 바뀜
 */
@Composable
fun ScoreHud(points: Int, combo: Int, gain: ScoreGain?, animate: Boolean, modifier: Modifier = Modifier, big: Boolean = false, shards: Int = 0) {
    val bump = remember { Animatable(1f) }
    LaunchedEffect(gain?.seq) {
        if (gain != null && animate) {
            delay(520) // 날아온 점수가 닿을 때
            bump.snapTo(1.15f)
            bump.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow))
        }
    }
    val moonMode = combo >= 5
    Box(modifier) {
        Row(
            Modifier
                .graphicsLayer { scaleX = bump.value; scaleY = bump.value }
                .clip(RoundedCornerShape(999.dp))
                .background(LunaColors.Surface)
                .then(if (moonMode) Modifier.border(BorderStroke(3.dp, LunaColors.Moon), RoundedCornerShape(999.dp)) else Modifier)
                .padding(horizontal = if (big) 26.dp else 20.dp, vertical = if (big) 12.dp else 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(LunaIcons.Moon, contentDescription = null, tint = LunaColors.Moon, modifier = Modifier.size(if (big) 30.dp else 26.dp))
            RollingNumber(points, if (big) 34.sp else 28.sp, LunaColors.TextPrimary, animate, suffix = "점")
            if (combo >= 2) ComboBadge(combo, animate, big)
            if (shards > 0) ShardPouch(shards, animate)
        }
        FlyingGain(gain, animate, Modifier.align(Alignment.BottomEnd))
        ComboBreak(combo, animate, Modifier.align(Alignment.BottomCenter))
    }
}

/** 콤보 배지: "3연속 ×2". 콤보가 늘 때마다 1.35배로 커졌다 탄성 있게 돌아옴. ×3이면 둘레에 반짝이 */
@Composable
fun ComboBadge(combo: Int, animate: Boolean, big: Boolean = false) {
    val scale = remember { Animatable(1f) }
    LaunchedEffect(combo) {
        if (animate && combo >= 2) {
            scale.snapTo(1.35f)
            scale.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium))
        }
    }
    val mult = AwakeScore.comboMultiplier(combo)
    // 배율이 오를수록 더 따뜻한 색 (×1.5 달빛 → ×2 주황 → ×3 산호)
    val color = when {
        mult >= 3f -> ComboCoral
        mult >= 2f -> ComboOrange
        else -> LunaColors.Moon
    }
    Box(contentAlignment = Alignment.Center) {
        if (mult >= 3f && animate) Sparkles(Modifier.size(if (big) 170.dp else 150.dp, 60.dp), seed = combo)
        Row(
            Modifier
                .graphicsLayer { scaleX = scale.value; scaleY = scale.value }
                .clip(RoundedCornerShape(999.dp))
                .background(color)
                .padding(horizontal = if (big) 16.dp else 14.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("${combo}연속", color = LunaColors.Background, fontSize = if (big) 26.sp else 22.sp, fontWeight = FontWeight.Bold)
            Text(multiplierText(mult), color = LunaColors.Background, fontSize = if (big) 26.sp else 22.sp, fontWeight = FontWeight.Black)
        }
    }
}

/** ×3 배지 둘레의 작은 반짝이 (천천히 돌며 반짝, 깜빡임 없이) */
@Composable
private fun Sparkles(modifier: Modifier, seed: Int) {
    val t = remember { Animatable(0f) }
    LaunchedEffect(seed) {
        while (true) {
            t.snapTo(0f)
            t.animateTo(1f, tween(2_400, easing = LinearEasing))
        }
    }
    Canvas(modifier) {
        val c = Offset(size.width / 2f, size.height / 2f)
        repeat(6) { i ->
            val a = (i / 6f + t.value) * 6.283f
            val pos = Offset(c.x + cos(a) * size.width * 0.48f, c.y + sin(a) * size.height * 0.48f)
            drawStar(pos, 7f, LunaColors.Moon.copy(alpha = 0.85f))
        }
    }
}

/** 점수가 왼쪽 아래에서 날아와 점수판에 합쳐짐: "+75" (번개면 "번개 +30", 안전 행동이면 청록 "+30 · 외기 전환") */
@Composable
fun FlyingGain(gain: ScoreGain?, animate: Boolean, modifier: Modifier = Modifier) {
    if (gain == null || !animate) return
    val p = remember(gain.seq) { Animatable(0f) }
    LaunchedEffect(gain.seq) { p.animateTo(1f, tween(1_100, easing = FastOutSlowInEasing)) }
    val v = p.value
    if (v >= 1f) return
    // 0~0.5: 왼쪽 아래(-220, 160)에서 점수판으로 날아옴, 0.5~1: 점수판 위에서 살짝 떠오르며 사라짐
    val fly = (v / 0.5f).coerceAtMost(1f)
    val fade = ((v - 0.5f) / 0.5f).coerceIn(0f, 1f)
    val color = if (gain.reason != GainReason.SAFETY && gain.points >= 100) Gold else gainColor(gain)
    Column(
        modifier.graphicsLayer {
            translationX = -220f * (1f - fly)
            translationY = 160f * (1f - fly) - 70f * fade - 40f
            val s = 0.8f + 0.4f * fly
            scaleX = s; scaleY = s
            alpha = 1f - fade
        },
        horizontalAlignment = Alignment.End,
    ) {
        val title = if (gain.reason == GainReason.SAFETY) gain.label else null
        Text("+${gain.points}" + (title?.let { " · $it" } ?: ""), color = color, fontSize = 32.sp, fontWeight = FontWeight.Black)
        if (gain.fast) Text("번개 +30", color = FastBlue, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    }
}

/** 콤보가 끊기면 "최고 5연속"이 1.2초 부드럽게 작아지며 사라짐 (꾸중하는 느낌 없이) */
@Composable
private fun ComboBreak(combo: Int, animate: Boolean, modifier: Modifier) {
    var prev by remember { mutableIntStateOf(combo) }
    var best by remember { mutableIntStateOf(0) }
    val p = remember { Animatable(1f) }
    LaunchedEffect(combo) {
        if (combo == 0 && prev >= 2 && animate) {
            best = prev
            p.snapTo(0f)
            p.animateTo(1f, tween(1_200))
        }
        prev = combo
    }
    if (best == 0 || p.value >= 1f) return
    Text(
        "최고 ${best}연속",
        color = LunaColors.TextSecondary, fontSize = 22.sp, fontWeight = FontWeight.Bold,
        modifier = modifier.graphicsLayer { translationY = 56f; alpha = 1f - p.value; val s = 1f - 0.3f * p.value; scaleX = s; scaleY = s },
    )
}

/** 3연속부터 링 위에 큰 "3 콤보!"가 떠올랐다 사라짐 (0.9초) */
@Composable
fun ComboBurst(gain: ScoreGain?, animate: Boolean, modifier: Modifier = Modifier) {
    if (gain == null || !animate || gain.reason != GainReason.CORRECT || gain.combo < 3) return
    val p = remember(gain.seq) { Animatable(0f) }
    LaunchedEffect(gain.seq) { p.animateTo(1f, tween(900, easing = FastOutSlowInEasing)) }
    val v = p.value
    if (v >= 1f) return
    val color = if (gain.combo >= 5) ComboCoral else ComboOrange
    Text(
        "${gain.combo} 콤보!",
        color = color, fontSize = 56.sp, fontWeight = FontWeight.Black,
        // 링(금색 테두리) 위에 겹쳐도 읽히게 어두운 그림자
        style = androidx.compose.ui.text.TextStyle(shadow = androidx.compose.ui.graphics.Shadow(LunaColors.Background, blurRadius = 16f)),
        modifier = modifier.graphicsLayer {
            val s = if (v < 0.25f) 0.5f + 2f * v else 1f + 0.1f * (v - 0.25f)
            scaleX = s; scaleY = s
            translationY = -50f * v
            alpha = if (v < 0.7f) 1f else (1f - v) / 0.3f
        },
    )
}

/** 정답 때 링 둘레에 달빛이 퍼짐 (0.7초, 3연속부터는 두 겹) */
@Composable
fun RingPulse(trigger: Int?, size: Dp, color: Color, animate: Boolean, rings: Int = 1) {
    if (trigger == null || !animate) return
    val p = remember(trigger) { Animatable(0f) }
    LaunchedEffect(trigger) { p.animateTo(1f, tween(if (rings > 1) 900 else 700, easing = FastOutSlowInEasing)) }
    val v = p.value
    if (v >= 1f) return
    Canvas(Modifier.size(size)) {
        val r = this.size.minDimension / 2f
        repeat(rings) { i ->
            val w = (v - i * 0.25f).coerceIn(0f, 1f)
            if (w > 0f && w < 1f) {
                drawCircle(color.copy(alpha = 0.55f * (1f - w)), radius = r * (0.9f + 0.18f * w), style = Stroke(width = r * 0.06f * (1f - w) + 2f))
            }
        }
    }
}

/** 5연속부터 콤보가 이어지는 동안 링이 은은한 금빛 (달빛 모드). 움직임 없는 빛이라 주행 중에도 그대로 */
@Composable
fun MoonGlow(active: Boolean, size: Dp) {
    if (!active) return
    Canvas(Modifier.size(size)) {
        val r = this.size.minDimension / 2f
        drawCircle(LunaColors.Moon.copy(alpha = 0.18f), radius = r * 0.98f, style = Stroke(width = r * 0.08f))
        drawCircle(LunaColors.Moon.copy(alpha = 0.35f), radius = r * 0.93f, style = Stroke(width = r * 0.025f))
    }
}

/**
 * 게임이 끝나면 "게임 끝! +420점 · 최고 4연속" 카드 2초 (점수를 얻은 게임일 때만).
 * 게임 중 얻은 점수·최고 콤보는 화면이 셉니다.
 */
@Composable
fun GameEndSummary(inGame: Boolean, gain: ScoreGain?, animate: Boolean, modifier: Modifier = Modifier) {
    var runPoints by remember { mutableIntStateOf(0) }
    var runBest by remember { mutableIntStateOf(0) }
    var lastSeq by remember { mutableStateOf<Int?>(null) }
    var wasInGame by remember { mutableStateOf(inGame) }
    var show by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    LaunchedEffect(gain?.seq) {
        if (gain != null && gain.seq != lastSeq && gain.reason == GainReason.CORRECT && inGame) {
            runPoints += gain.points
            runBest = maxOf(runBest, gain.combo)
        }
        lastSeq = gain?.seq
    }
    LaunchedEffect(inGame) {
        if (inGame && !wasInGame) {
            runPoints = 0
            runBest = 0
        }
        if (!inGame && wasInGame && runPoints > 0) {
            show = runPoints to runBest
            delay(2_200)
            show = null
        }
        wasInGame = inGame
    }
    val s = show ?: return
    val p = remember(s) { Animatable(0f) }
    LaunchedEffect(s) { if (animate) p.animateTo(1f, tween(300)) else p.snapTo(1f) }
    Row(
        modifier
            .graphicsLayer { alpha = p.value; translationY = 30f * (1f - p.value) }
            .clip(RoundedCornerShape(28.dp))
            .background(LunaColors.Surface)
            .border(BorderStroke(2.dp, LunaColors.Moon), RoundedCornerShape(28.dp))
            .padding(horizontal = 28.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("게임 끝!", color = LunaColors.TextPrimary, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text("+${"%,d".format(s.first)}점", color = LunaColors.Moon, fontSize = 32.sp, fontWeight = FontWeight.Black)
        if (s.second >= 2) Text("최고 ${s.second}연속", color = LunaColors.TextSecondary, fontSize = 24.sp)
    }
}

/**
 * 정차 중 축하 연출 (휴식 완료·도장·커피냅·제때 쉬기·달 레벨 상승): 별가루가 터지고 큰 "+700"과 한 줄, 1.8초.
 * 새 도장이면 먼저 도장이 위에서 "쾅" 내려와 찍힘. 안전 행동이면 청록색.
 * @param key 바뀔 때마다 한 번 (gain.seq 또는 레벨업)
 */
@Composable
fun Celebration(key: Any?, points: Int?, label: String?, modifier: Modifier = Modifier, color: Color = LunaColors.Moon, stampName: String? = null) {
    if (key == null) return
    var visible by remember(key) { mutableStateOf(true) }
    val p = remember(key) { Animatable(0f) }
    val stamp = remember(key) { Animatable(0f) }
    val total = if (stampName != null) 2_400L else 1_800L
    LaunchedEffect(key) {
        if (stampName != null) {
            stamp.animateTo(1f, tween(450, easing = FastOutSlowInEasing))
            delay(150)
        }
        launch { p.animateTo(1f, tween(1_600, easing = FastOutSlowInEasing)) }
        delay(total - if (stampName != null) 600 else 0)
        visible = false
    }
    if (!visible) return
    val n = sparkCount(80)
    val light = LocalLightEffects.current
    val sparks = remember(key, n) { makeSparks(key.hashCode(), n) }
    val v = p.value
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) { if (v > 0f) burst(sparks, v, color, glow = !light) }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (stampName != null) StampMark(stampName, stamp.value, p.value)
            Column(
                Modifier.graphicsLayer {
                    val s = if (v < 0.2f) 0.6f + 2f * v else 1f
                    scaleX = s; scaleY = s
                    alpha = if (v <= 0f) 0f else if (v < 0.8f) 1f else (1f - v) * 5f
                },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (points != null && points > 0) Text("+${"%,d".format(points)}", color = color, fontSize = 96.sp, fontWeight = FontWeight.Black)
                if (label != null) Text(label, color = LunaColors.TextPrimary, fontSize = 32.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** 도장 "쾅": 1.6배 크기로 위에서 내려와 찍히고 살짝 흔들림 */
@Composable
private fun StampMark(name: String, drop: Float, after: Float, size: Dp = 170.dp) {
    val shake = if (drop >= 1f && after < 0.15f) sin(after * 90f) * 6f * (1f - after / 0.15f) else 0f
    Box(
        Modifier
            .graphicsLayer {
                val s = 1.6f - 0.6f * drop
                scaleX = s; scaleY = s
                translationY = -120f * (1f - drop)
                translationX = shake
                rotationZ = -8f
                alpha = if (after > 0.8f) (1f - after) * 5f else drop.coerceAtLeast(0.2f)
            }
            .size(size)
            .clip(CircleShape)
            .border(BorderStroke(6.dp, ComboCoral), CircleShape)
            .padding(10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(name.take(7), color = ComboCoral, fontSize = 24.sp, fontWeight = FontWeight.Black)
            Text("도장", color = ComboCoral, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/** 입자: 방향·거리·크기·모양(0 별, 1 초승달, 2 점)·회전 속도·색 */
private data class Spark(val angle: Float, val dist: Float, val size: Float, val warm: Boolean, val shape: Int = 0, val spin: Float = 0f)

/** 가벼운 연출 (데모 패널 스위치): 입자 절반, 빛 번짐 원 없음 — 느린 노트북 에뮬레이터·저사양 기기용 */
val LocalLightEffects = androidx.compose.runtime.staticCompositionLocalOf { false }

/** 가벼운 연출이면 입자 수 절반 */
@Composable
private fun sparkCount(n: Int): Int = if (LocalLightEffects.current) n / 2 else n

private fun makeSparks(seed: Int, n: Int): List<Spark> {
    val rnd = Random(seed)
    return List(n) {
        Spark(
            angle = rnd.nextFloat() * 6.283f, dist = 0.35f + rnd.nextFloat() * 0.75f, size = 5f + rnd.nextFloat() * 13f,
            warm = rnd.nextFloat() < 0.65f, shape = rnd.nextInt(3), spin = (rnd.nextFloat() - 0.5f) * 720f,
        )
    }
}

/** 입자 터짐: 사방으로 퍼졌다가 중력으로 떨어지며 돌고, 점점 사라짐. 겹쳐 그려 빛 번짐 */
private fun DrawScope.burst(sparks: List<Spark>, v: Float, color: Color, glow: Boolean = true) {
    val c = Offset(size.width / 2f, size.height / 2f)
    val reach = size.minDimension * 0.55f
    val gravity = size.height * 0.35f
    sparks.forEach { s ->
        val d = reach * s.dist * (1f - (1f - v) * (1f - v)) // 처음엔 빠르게, 나중엔 느리게
        val pos = Offset(c.x + cos(s.angle) * d, c.y + sin(s.angle) * d + gravity * v * v)
        val a = (1f - v).coerceIn(0f, 1f)
        val col = if (s.warm) color else Color.White
        val r = s.size * (1f - 0.35f * v)
        // 빛 번짐 (크고 흐린 원)
        if (glow) drawCircle(col.copy(alpha = 0.18f * a), radius = r * 2.2f, center = pos)
        when (s.shape) {
            0 -> rotateAround(pos, s.spin * v) { drawStar(pos, r, col.copy(alpha = a)) }
            1 -> rotateAround(pos, s.spin * v) { crescent(pos, r, col.copy(alpha = a)) }
            else -> drawCircle(col.copy(alpha = a), radius = r * 0.45f, center = pos)
        }
    }
}

private inline fun DrawScope.rotateAround(p: Offset, deg: Float, block: DrawScope.() -> Unit) {
    drawContext.transform.rotate(deg, p)
    block()
    drawContext.transform.rotate(-deg, p)
}

/** 초승달 조각 */
private fun DrawScope.crescent(c: Offset, r: Float, color: Color) {
    val outer = Path().apply { addOval(androidx.compose.ui.geometry.Rect(c, r)) }
    val inner = Path().apply { addOval(androidx.compose.ui.geometry.Rect(Offset(c.x + r * 0.45f, c.y - r * 0.15f), r * 0.85f)) }
    val moon = Path().apply { op(outer, inner, androidx.compose.ui.graphics.PathOperation.Difference) }
    drawPath(moon, color)
}

/** 네 갈래 반짝이 별 */
private fun DrawScope.drawStar(c: Offset, r: Float, color: Color) {
    val path = Path().apply {
        moveTo(c.x, c.y - r)
        quadraticBezierTo(c.x, c.y, c.x + r, c.y)
        quadraticBezierTo(c.x, c.y, c.x, c.y + r)
        quadraticBezierTo(c.x, c.y, c.x - r, c.y)
        quadraticBezierTo(c.x, c.y, c.x, c.y - r)
        close()
    }
    drawPath(path, color)
}

/** 화면에서 쓰는 이유별 한 줄 */
fun gainTitle(g: ScoreGain): String? = g.label ?: when (g.reason) {
    GainReason.REST -> "휴식 완료"
    GainReason.NAP -> "커피냅 효과"
    GainReason.SAFETY -> "안전 행동"
    GainReason.CORRECT -> null
}

// ───────────────────────── 휴식 잭팟 · 달빛 선물 · 운행 결과 ─────────────────────────

/**
 * 휴식 잭팟 (정차 중, 약 6초, 화면 전체): 주변을 어둡게 →
 * ① 도장 "쾅"(화면 살짝 흔들림) → ② 운전 중 모아 둔 달빛 조각이 사방에서 날아와 가운데로 빨려 듦('띵'이 점점 높게)
 * → ③ 내역이 한 줄씩("휴식 완료 +500", "제때 쉬기 +200", "새 도장 +200", "달빛 조각 ×12 +120")
 * → ④ 합계가 굴러 올라가 금색으로 멈춤 + 팡파르 + 입자 80개 터짐 → ⑤ 달 레벨이 오르면 "레벨 업! 볼록달".
 * @param onSound 연출에 맞춘 효과음
 */
@Composable
fun RewardJackpot(gain: ScoreGain?, levelUp: String?, onSound: (app.daltokki.drive.voice.SoundEffect, Int) -> Unit, modifier: Modifier = Modifier) {
    if (gain == null || gain.reason != GainReason.REST) return
    val key = gain.seq
    var visible by remember(key) { mutableStateOf(true) }
    val scrim = remember(key) { Animatable(0f) }
    val stamp = remember(key) { Animatable(0f) }
    val shardFly = remember(key) { Animatable(0f) }
    var linesShown by remember(key) { mutableIntStateOf(0) }
    var total by remember(key) { mutableIntStateOf(0) }
    val burst = remember(key) { Animatable(0f) }
    var showLevel by remember(key) { mutableStateOf(false) }
    val shake = remember(key) { Animatable(0f) }
    LaunchedEffect(key) {
        scrim.animateTo(1f, tween(250))
        if (gain.stampName != null) {
            stamp.animateTo(1f, tween(420, easing = FastOutSlowInEasing))
            launch { shake.snapTo(1f); shake.animateTo(0f, tween(260)) }
            delay(200)
        }
        // 달빛 조각 빨려 들기 + '띵' 점점 높게
        val n = gain.shards.coerceIn(0, 24)
        if (n > 0) {
            launch { shardFly.animateTo(1f, tween(1_200, easing = LinearEasing)) }
            // '띵'은 최대 10번 (소리 하나 0.12초라 너무 촘촘하면 밀림)
            val ticks = n.coerceAtMost(10)
            repeat(ticks) { i ->
                onSound(app.daltokki.drive.voice.SoundEffect.SHARD, i)
                delay(1_200L / ticks)
            }
        }
        // 내역 한 줄씩 + 합계 올라감
        for ((i, part) in gain.parts.withIndex()) {
            linesShown = i + 1
            total += part.second
            delay(380)
        }
        if (gain.parts.isEmpty()) total = gain.points
        onSound(app.daltokki.drive.voice.SoundEffect.FANFARE, 0)
        launch { burst.animateTo(1f, tween(1_800, easing = FastOutSlowInEasing)) }
        delay(1_300)
        if (levelUp != null) {
            showLevel = true
            onSound(app.daltokki.drive.voice.SoundEffect.FANFARE, 0)
            delay(1_600)
        } else {
            delay(700)
        }
        scrim.animateTo(0f, tween(400))
        visible = false
    }
    if (!visible) return
    val sparkN = sparkCount(80)
    val light = LocalLightEffects.current
    val sparks = remember(key, sparkN) { makeSparks(key * 31, sparkN) }
    val shardsList = remember(key) { makeSparks(key * 17, gain.shards.coerceIn(0, 24)) }
    Box(
        modifier.fillMaxSize().graphicsLayer { translationX = sin(shake.value * 40f) * 10f * shake.value }
            .background(LunaColors.Background.copy(alpha = 0.78f * scrim.value)),
        contentAlignment = Alignment.Center,
    ) {
        // 달빛 조각: 가장자리에서 가운데로
        Canvas(Modifier.fillMaxSize()) {
            val f = shardFly.value
            if (f > 0f && f < 1f) {
                val c = Offset(size.width / 2f, size.height / 2f)
                shardsList.forEachIndexed { i, s ->
                    val start = (i.toFloat() / shardsList.size) * 0.6f
                    val t = ((f - start) / 0.4f).coerceIn(0f, 1f)
                    if (t > 0f && t < 1f) {
                        val far = size.maxDimension * 0.6f
                        val from = Offset(c.x + cos(s.angle) * far, c.y + sin(s.angle) * far)
                        val pos = Offset(from.x + (c.x - from.x) * t * t, from.y + (c.y - from.y) * t * t)
                        if (!light) drawCircle(LunaColors.Moon.copy(alpha = 0.25f), radius = 22f, center = pos)
                        crescent(pos, 11f, LunaColors.Moon)
                    }
                }
            }
            if (burst.value > 0f) burst(sparks, burst.value, Gold, glow = !light)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // 낮은 화면에서도 위아래가 잘리지 않게: 도장 130dp, 내역 26·30sp, 합계 96sp (전체 약 520dp)
            if (gain.stampName != null) StampMark(gain.stampName, stamp.value, 0f, size = 130.dp)
            if (showLevel && levelUp != null) {
                // 레벨 업은 내역 자리에 (이미 다 읽은 내역 대신 → 높이가 늘지 않음)
                Text("레벨 업! $levelUp", color = Gold, fontSize = 44.sp, fontWeight = FontWeight.Black)
            } else {
                gain.parts.take(linesShown).forEach { (label, pts) ->
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(label, color = LunaColors.TextPrimary, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                        Text("+${"%,d".format(pts)}", color = LunaColors.Moon, fontSize = 30.sp, fontWeight = FontWeight.Black)
                    }
                }
            }
            if (total > 0) {
                val pop = if (burst.value in 0.001f..0.2f) 1f + burst.value * 1.5f else 1f
                Row(Modifier.graphicsLayer { scaleX = pop; scaleY = pop }, verticalAlignment = Alignment.CenterVertically) {
                    RollingNumber(total, 96.sp, if (burst.value > 0f) Gold else LunaColors.Moon, animate = true, suffix = "점")
                }
            }
        }
    }
}

/**
 * 달빛 선물 상자 열기 (약 3.5초): 상자가 흔들리다 → 뚜껑이 열리며 빛이 터지고 → 꾸미기를 쓴 달토끼(또는 보너스 점수).
 */
@Composable
fun GiftRevealOverlay(
    reveal: app.daltokki.drive.engine.GiftReveal?,
    onSound: (app.daltokki.drive.voice.SoundEffect, Int) -> Unit,
    onDismiss: () -> Unit,
) {
    if (reveal == null) return
    val wobble = remember(reveal.seq) { Animatable(0f) }
    val open = remember(reveal.seq) { Animatable(0f) }
    LaunchedEffect(reveal.seq) {
        repeat(3) { i ->
            wobble.animateTo(1f, tween(110)); wobble.animateTo(-1f, tween(110)); wobble.animateTo(0f, tween(110))
            onSound(app.daltokki.drive.voice.SoundEffect.SHARD, i * 3)
        }
        onSound(app.daltokki.drive.voice.SoundEffect.FANFARE, 0)
        open.animateTo(1f, tween(1_400, easing = FastOutSlowInEasing))
        delay(2_200)
        onDismiss()
    }
    val n = sparkCount(70)
    val light = LocalLightEffects.current
    val sparks = remember(reveal.seq, n) { makeSparks(reveal.seq * 7, n) }
    Box(
        Modifier.fillMaxSize().background(LunaColors.Background.copy(alpha = 0.85f))
            .then(Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) { if (open.value > 0f) burst(sparks, open.value, LunaColors.Moon, glow = !light) }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("달빛 선물", color = LunaColors.Moon, fontSize = 34.sp, fontWeight = FontWeight.Bold)
            if (open.value < 0.15f) {
                // 상자 (흔들림)
                Canvas(Modifier.size(200.dp).graphicsLayer { rotationZ = wobble.value * 8f }) {
                    val w = size.width
                    drawRoundRect(Color(0xFF3B3F8F), topLeft = Offset(w * 0.1f, w * 0.4f), size = androidx.compose.ui.geometry.Size(w * 0.8f, w * 0.55f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(18f))
                    drawRoundRect(Color(0xFF4B50A8), topLeft = Offset(w * 0.05f, w * 0.3f), size = androidx.compose.ui.geometry.Size(w * 0.9f, w * 0.16f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(14f))
                    drawRect(LunaColors.Moon, topLeft = Offset(w * 0.45f, w * 0.3f), size = androidx.compose.ui.geometry.Size(w * 0.1f, w * 0.65f))
                    crescent(Offset(w * 0.5f, w * 0.22f), w * 0.1f, LunaColors.Moon)
                }
                Text("두근두근…", color = LunaColors.TextSecondary, fontSize = 26.sp)
            } else {
                val s = 0.6f + 0.4f * open.value
                Box(Modifier.graphicsLayer { scaleX = s; scaleY = s; alpha = open.value }, contentAlignment = Alignment.Center) {
                    if (reveal.itemId != null) {
                        TokkiRing(mood = TokkiMood.HAPPY, ringColor = LunaColors.Moon, progress = null, size = 240.dp, accessory = reveal.itemId)
                    } else {
                        Text("+${reveal.points}", color = Gold, fontSize = 110.sp, fontWeight = FontWeight.Black)
                    }
                }
                Text(reveal.title, color = LunaColors.TextPrimary, fontSize = 40.sp, fontWeight = FontWeight.Black)
                Text(reveal.detail, color = LunaColors.TextSecondary, fontSize = 24.sp)
            }
        }
    }
}

/** 운전 중 모으는 달빛 조각 주머니: "조각 7" (하나 들어올 때마다 톡) */
@Composable
fun ShardPouch(shards: Int, animate: Boolean) {
    val pop = remember { Animatable(1f) }
    LaunchedEffect(shards) {
        if (animate && shards > 0) {
            pop.snapTo(1.3f)
            pop.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
        }
    }
    Row(
        Modifier.graphicsLayer { scaleX = pop.value; scaleY = pop.value }
            .clip(RoundedCornerShape(999.dp)).background(LunaColors.SurfaceHigh).padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Canvas(Modifier.size(18.dp)) { crescent(Offset(size.width / 2f, size.height / 2f), size.width / 2.2f, LunaColors.Moon) }
        Text("조각 $shards", color = LunaColors.TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    }
}

/**
 * 운행 결과 카드 (게임 결과 화면처럼): 별 3개가 하나씩 "쾅" 찍히고 → 등급 도장(S/A/B/C) → 신기록 띠.
 */
@Composable
fun TripResultCard(
    result: app.daltokki.drive.domain.game.TripResult,
    onSound: (app.daltokki.drive.voice.SoundEffect, Int) -> Unit,
    modifier: Modifier = Modifier,
    animate: Boolean = true,
) {
    var shownStars by remember(result) { mutableIntStateOf(if (animate) 0 else 3) }
    val grade = remember(result) { Animatable(if (animate) 0f else 1f) }
    LaunchedEffect(result) {
        if (!animate) return@LaunchedEffect
        delay(300)
        for (i in 0 until 3) {
            shownStars = i + 1
            if (result.stars[i]) onSound(app.daltokki.drive.voice.SoundEffect.STAR, i * 2)
            delay(420)
        }
        grade.animateTo(1f, tween(380, easing = FastOutSlowInEasing))
        if (result.grade == "S") onSound(app.daltokki.drive.voice.SoundEffect.FANFARE, 0)
    }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
            for (i in 0 until 3) {
                val on = i < shownStars && result.stars[i]
                val s = remember(result, i) { Animatable(1f) }
                LaunchedEffect(shownStars) { if (animate && i == shownStars - 1 && on) { s.snapTo(1.8f); s.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy)) } }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Canvas(Modifier.size(64.dp).graphicsLayer { scaleX = s.value; scaleY = s.value }) {
                        val col = if (on) Gold else LunaColors.Track
                        if (on) drawCircle(Gold.copy(alpha = 0.25f), radius = size.minDimension * 0.6f)
                        bigStar(Offset(size.width / 2f, size.height / 2f), size.minDimension * 0.48f, col)
                    }
                    Text(app.daltokki.drive.domain.game.TripResult.STAR_LABELS[i], color = if (on) LunaColors.TextPrimary else LunaColors.TextSecondary, fontSize = 16.sp)
                }
            }
            // 등급 도장
            Box(
                Modifier.size(96.dp).graphicsLayer {
                    val g = grade.value
                    val sc = 1.8f - 0.8f * g
                    scaleX = sc; scaleY = sc; alpha = g; rotationZ = -12f
                }.clip(CircleShape).border(BorderStroke(5.dp, gradeColor(result.grade)), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(result.grade, color = gradeColor(result.grade), fontSize = 54.sp, fontWeight = FontWeight.Black)
            }
        }
        if (grade.value >= 1f) {
            val banners = listOfNotNull(
                if (result.newBestScore) "개인 최고 점수!" else null,
                if (result.newBestCombo) "최고 콤보 갱신! ${result.bestCombo}연속" else null,
            )
            banners.forEach { b ->
                Text(
                    b, color = LunaColors.Background, fontSize = 22.sp, fontWeight = FontWeight.Black,
                    modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(Gold).padding(horizontal = 18.dp, vertical = 6.dp),
                )
            }
        }
    }
}

private fun gradeColor(g: String) = when (g) {
    "S" -> Gold
    "A" -> LunaColors.Moon
    "B" -> SafetyTeal
    else -> LunaColors.TextSecondary
}

/** 큰 다섯 꼭지 별 */
private fun DrawScope.bigStar(c: Offset, r: Float, color: Color) {
    val p = Path()
    for (i in 0 until 10) {
        val rr = if (i % 2 == 0) r else r * 0.45f
        val a = Math.toRadians((-90 + i * 36).toDouble())
        val x = c.x + (rr * Math.cos(a)).toFloat()
        val y = c.y + (rr * Math.sin(a)).toFloat()
        if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
    }
    p.close()
    drawPath(p, color)
}

// ───────────────────────── 졸음 위험도가 실제로 내려갔을 때 ─────────────────────────

/**
 * 졸음 위험도 숫자 + 행동 덕분에 내려갔을 때 "−7 · 빠른 대답".
 * 숫자가 초록으로 바뀌며 톡 커지고, 오른쪽 위에 "−7"이 튀어나왔다가 2.8초 뒤 아래로 가라앉으며 사라짐.
 * 더 내려가면 같은 표시의 숫자만 커짐(−2 → −5). 표시는 자리를 차지하지 않아 화면이 흔들리지 않음.
 * 실제 주행 화면 제한이면 움직임 없이 숫자·색만.
 */
@Composable
fun RiskNumber(risk: Int, drop: app.daltokki.drive.domain.fatigue.RiskDrop?, fontSize: TextUnit, animate: Boolean) {
    // 화면이 처음 그려질 때 이미 있던 표시는 다시 보여 주지 않음
    val initialSeq = remember { drop?.seq }
    val d = drop?.takeIf { it.seq != initialSeq }
    var showing by remember { mutableStateOf<app.daltokki.drive.domain.fatigue.RiskDrop?>(null) }
    val pop = remember { Animatable(1f) }
    val sink = remember { Animatable(0f) }
    LaunchedEffect(d?.seq) {
        if (d == null) return@LaunchedEffect
        val newAction = showing?.actionSeq != d.actionSeq
        showing = d
        sink.snapTo(0f)
        if (animate) {
            pop.snapTo(if (newAction) 1.6f else 1.25f)
            pop.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow))
        }
        delay(RISK_DROP_SHOW_MS)
        if (animate) sink.animateTo(1f, tween(600)) else delay(600)
        showing = null
    }
    val numColor by androidx.compose.animation.animateColorAsState(
        if (showing != null) LunaColors.Normal else LunaColors.TextPrimary, tween(250), label = "riskColor",
    )
    Row(verticalAlignment = Alignment.Bottom) {
        Text(
            "$risk", color = numColor, fontSize = fontSize, fontWeight = FontWeight.Bold,
            modifier = Modifier.graphicsLayer {
                val k = 1f + (pop.value - 1f) * 0.35f
                scaleX = k; scaleY = k
            },
        )
        // 자리를 차지하지 않는 표시 (숫자 오른쪽 위로 넘쳐 그림)
        Box(Modifier.floatAboveEnd()) {
            val sd = showing
            if (sd != null) {
                Column(
                    Modifier.graphicsLayer {
                        val k = pop.value
                        scaleX = k; scaleY = k
                        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 1f)
                        translationY = sink.value * 36f
                        alpha = 1f - sink.value
                    },
                ) {
                    Text("−${sd.amount}", color = LunaColors.Normal, fontSize = fontSize * 0.9f, fontWeight = FontWeight.Black, lineHeight = fontSize)
                    Text(sd.label, color = LunaColors.Normal.copy(alpha = 0.85f), fontSize = fontSize * 0.42f, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

private const val RISK_DROP_SHOW_MS = 2_800L

/** 크기 0으로 자리 잡고, 내용은 오른쪽 위로 넘쳐 그림 (나타나고 사라져도 옆 글자가 움직이지 않게) */
private fun Modifier.floatAboveEnd(): Modifier = this.layout { measurable, _ ->
    val p = measurable.measure(androidx.compose.ui.unit.Constraints())
    val gap = 10.dp.roundToPx()
    layout(0, 0) { p.place(gap, -p.height) }
}
