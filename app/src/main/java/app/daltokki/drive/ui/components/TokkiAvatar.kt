package app.daltokki.drive.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.daltokki.drive.ui.theme.LunaColors

/** 달토끼 표정 */
enum class TokkiMood(val label: String) {
    DEFAULT("기본"),
    LISTENING("듣는 중"),
    SPEAKING("말하는 중"),
    WORRIED("걱정"),
    HAPPY("축하"),
    SLEEPING("꾸벅 휴식"),
}

/** 달토끼 캐릭터 색 */
object TokkiColors {
    val Fur = Color(0xFFFFF6EA)
    val EarInner = Color(0xFFF7C1B4)
    val Blush = Color(0xFFF7B3B0)
    val Ink = Color(0xFF2B2730)
    val Strap = Color(0xFF2E2F38)
    val Frame = Color(0xFFA3A9B3)
    val FrameDark = Color(0xFF5E636E)
    /** 기본 고글 렌즈 (짙은 회색) */
    val Lens = Color(0xFF3A3F4B)
    /** 경고·위험일 때 렌즈 */
    val LensWarn = Color(0xFFF2A65A)
    val Moon = Color(0xFFF6D77A)
    val Tongue = Color(0xFFE58C8A)
}

/**
 * 달토끼 캐릭터: 크림색 달토끼가 머리 위에 비행 고글을 올려 쓴 모습 (코드로 그린 벡터 → 어떤 크기에서도 선명).
 * 설계 좌표는 가로 200 × 세로 230. 운전 중에는 움직임 없이 표정만 바뀝니다.
 */
@Composable
fun TokkiAvatar(
    mood: TokkiMood,
    modifier: Modifier = Modifier,
    width: Dp = 200.dp,
    lens: Color = TokkiColors.Lens,
    /** 달토끼 꾸미기 아이템 id (달빛 선물 상자에서 나옴). 없으면 null */
    accessory: String? = null,
) {
    Canvas(
        modifier
            .size(width, width * 1.15f)
            .semantics { contentDescription = "달토끼 · ${mood.label}" },
    ) {
        scale(size.width / 200f, size.height / 230f, pivot = Offset.Zero) {
            drawTokki(mood, lens)
            drawAccessory(accessory)
        }
    }
}

/**
 * 링 안의 달토끼 (원형 초상화). 링 색 = 피로 단계, 링 길이 = 위험도(또는 휴식 진행).
 * 안쪽 원은 단계 색을 아주 옅게 깔고, 달토끼는 원 아래에 맞춰 어깨가 원에 잘리도록 배치해 깔끔하게 보이게 합니다.
 * 들을 때는 링 바깥에 얇은 링을 하나 더 그립니다.
 */
@Composable
fun TokkiRing(
    mood: TokkiMood,
    ringColor: Color,
    progress: Float?,
    size: Dp,
    modifier: Modifier = Modifier,
    listening: Boolean = false,
    lens: Color = TokkiColors.Lens,
    accessory: String? = null,
) {
    val stroke = size * 0.045f
    val inner = size - stroke * 2.6f
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val sw = stroke.toPx()
            val r = this.size.minDimension / 2f - sw / 2f
            drawCircle(color = LunaColors.Track, radius = r, style = Stroke(width = sw))
            if (progress != null) {
                drawArc(
                    color = ringColor, startAngle = -90f, sweepAngle = 360f * progress.coerceIn(0.02f, 1f), useCenter = false,
                    topLeft = Offset(center.x - r, center.y - r), size = Size(r * 2, r * 2),
                    style = Stroke(width = sw, cap = StrokeCap.Round),
                )
            } else {
                drawCircle(color = ringColor, radius = r, style = Stroke(width = sw))
            }
            if (listening) {
                drawCircle(color = ringColor.copy(alpha = 0.35f), radius = r + sw * 0.9f, style = Stroke(width = sw * 0.35f))
            }
        }
        Box(
            Modifier
                .size(inner)
                .clip(CircleShape)
                .background(Brush.verticalGradient(listOf(LunaColors.SurfaceHigh, ringColor.copy(alpha = 0.22f).compositeOver(LunaColors.Surface)))),
            contentAlignment = Alignment.BottomCenter,
        ) {
            TokkiAvatar(mood = mood, width = inner * 0.84f, lens = lens, accessory = accessory, modifier = Modifier.offset(y = inner * 0.02f))
        }
    }
}

private fun earOuter(left: Boolean) = Path().apply {
    if (left) {
        moveTo(70f, 104f); cubicTo(56f, 62f, 56f, 16f, 74f, 10f); cubicTo(92f, 6f, 94f, 58f, 90f, 104f)
    } else {
        moveTo(130f, 104f); cubicTo(144f, 62f, 144f, 16f, 126f, 10f); cubicTo(108f, 6f, 106f, 58f, 110f, 104f)
    }
    close()
}

private fun earInner(left: Boolean) = Path().apply {
    if (left) {
        moveTo(74f, 96f); cubicTo(64f, 64f, 64f, 28f, 75f, 22f); cubicTo(86f, 20f, 86f, 62f, 84f, 96f)
    } else {
        moveTo(126f, 96f); cubicTo(136f, 64f, 136f, 28f, 125f, 22f); cubicTo(114f, 20f, 114f, 62f, 116f, 96f)
    }
    close()
}

private fun DrawScope.ears(droop: Boolean) {
    val tilt = if (droop) 20f else 0f
    rotate(-tilt, pivot = Offset(80f, 104f)) {
        drawPath(earOuter(true), TokkiColors.Fur)
        drawPath(earInner(true), TokkiColors.EarInner)
    }
    rotate(tilt, pivot = Offset(120f, 104f)) {
        drawPath(earOuter(false), TokkiColors.Fur)
        drawPath(earInner(false), TokkiColors.EarInner)
    }
}

/** 꾸미기 아이템 그리기 (설계 좌표 200×230: 머리 26~174, 귀 위쪽 10, 고글 중심 (80,100)·(120,100), 어깨 196~) */
private fun DrawScope.drawAccessory(id: String?) {
    when (id) {
        "hat" -> {
            // 남색 고깔모자 + 노란 별
            val hat = Path().apply { moveTo(84f, 84f); lineTo(116f, 84f); lineTo(102f, 30f); close() }
            drawPath(hat, Color(0xFF3B3F8F))
            drawRoundRect(Color(0xFF2A2D6B), topLeft = Offset(80f, 80f), size = Size(40f, 8f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f))
            star(Offset(101f, 58f), 7f, TokkiColors.Moon)
            drawCircle(TokkiColors.Moon, radius = 4.5f, center = Offset(102f, 29f))
        }
        "ribbon" -> {
            // 오른쪽 귀 아래 분홍 리본
            val c = Offset(122f, 86f)
            drawPath(Path().apply { moveTo(c.x, c.y); lineTo(c.x - 16f, c.y - 10f); lineTo(c.x - 16f, c.y + 10f); close() }, Color(0xFFFF8FB1))
            drawPath(Path().apply { moveTo(c.x, c.y); lineTo(c.x + 16f, c.y - 10f); lineTo(c.x + 16f, c.y + 10f); close() }, Color(0xFFFF8FB1))
            drawCircle(Color(0xFFE5668E), radius = 5f, center = c)
            star(Offset(c.x, c.y), 3f, Color.White)
        }
        "scarf" -> {
            // 빨간 목도리 + 늘어진 꼬리
            drawRoundRect(Color(0xFFE5484D), topLeft = Offset(50f, 190f), size = Size(100f, 18f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(9f))
            drawRoundRect(Color(0xFFC93A3F), topLeft = Offset(116f, 198f), size = Size(16f, 30f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(5f))
            for (x in listOf(62f, 82f, 102f, 122f, 142f)) drawLine(Color.White.copy(alpha = 0.5f), Offset(x, 192f), Offset(x - 6f, 206f), strokeWidth = 3f)
        }
        "star_goggle" -> {
            // 고글 렌즈에 반짝이는 별
            star(Offset(80f, 100f), 11f, TokkiColors.Moon)
            star(Offset(120f, 100f), 11f, TokkiColors.Moon)
        }
        "crown" -> {
            // 달빛 왕관 (두 귀 사이)
            val crown = Path().apply {
                moveTo(80f, 88f); lineTo(80f, 64f); lineTo(90f, 74f); lineTo(100f, 56f); lineTo(110f, 74f); lineTo(120f, 64f); lineTo(120f, 88f); close()
            }
            drawPath(crown, Color(0xFFF5C542))
            drawPath(crown, Color(0xFFC9971C), style = Stroke(width = 2.5f))
            drawCircle(Color(0xFFFF7A6B), radius = 4f, center = Offset(100f, 78f))
            drawCircle(Color(0xFF8FD3FF), radius = 3f, center = Offset(88f, 81f))
            drawCircle(Color(0xFF8FD3FF), radius = 3f, center = Offset(112f, 81f))
        }
    }
}

/** 다섯 꼭지 별 */
private fun DrawScope.star(c: Offset, r: Float, color: Color) {
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

private fun DrawScope.goggles(lens: Color) {
    // 고글 끈 (머리 위를 감싸는 곡선)
    val strap = Path().apply { moveTo(30f, 124f); quadraticBezierTo(100f, 72f, 170f, 124f) }
    drawPath(strap, TokkiColors.Strap, style = Stroke(width = 9f, cap = StrokeCap.Round))
    for (cx in listOf(80f, 120f)) {
        val c = Offset(cx, 100f)
        drawCircle(TokkiColors.FrameDark, radius = 20.5f, center = c)
        drawCircle(TokkiColors.Frame, radius = 18f, center = c)
        drawCircle(lens, radius = 14.5f, center = c)
        // 렌즈 반짝임
        drawCircle(Color.White.copy(alpha = 0.55f), radius = 3.6f, center = Offset(cx - 6f, 94f))
        drawCircle(Color.White.copy(alpha = 0.45f), radius = 1.8f, center = Offset(cx + 1.5f, 91.5f))
    }
    drawRoundRect(TokkiColors.FrameDark, topLeft = Offset(96f, 96f), size = Size(8f, 7f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(3f))
    // 노란 초승달 장식
    drawCircle(TokkiColors.Moon, radius = 7f, center = Offset(147f, 104f))
    drawCircle(TokkiColors.Fur, radius = 6f, center = Offset(150f, 101f))
}

private fun DrawScope.line(path: Path, width: Float = 3f, color: Color = TokkiColors.Ink) {
    drawPath(path, color, style = Stroke(width = width, cap = StrokeCap.Round))
}

private fun DrawScope.drawTokki(mood: TokkiMood, lens: Color) {
    val droop = mood == TokkiMood.WORRIED || mood == TokkiMood.SLEEPING
    ears(droop)
    // 몸(어깨)과 머리
    drawOval(TokkiColors.Fur, topLeft = Offset(48f, 196f), size = Size(104f, 60f))
    drawOval(TokkiColors.Fur, topLeft = Offset(26f, 80f), size = Size(148f, 120f))
    goggles(lens)

    // 볼
    val blushAlpha = if (mood == TokkiMood.HAPPY) 1f else 0.85f
    drawOval(TokkiColors.Blush.copy(alpha = blushAlpha), topLeft = Offset(48f, 151f), size = Size(26f, 15f))
    drawOval(TokkiColors.Blush.copy(alpha = blushAlpha), topLeft = Offset(126f, 151f), size = Size(26f, 15f))

    when (mood) {
        TokkiMood.DEFAULT -> {
            drawCircle(TokkiColors.Ink, radius = 4.8f, center = Offset(78f, 142f))
            drawCircle(TokkiColors.Ink, radius = 4.8f, center = Offset(122f, 142f))
            line(Path().apply { moveTo(95f, 160f); lineTo(105f, 160f) })
        }
        TokkiMood.LISTENING -> {
            drawCircle(TokkiColors.Ink, radius = 5.3f, center = Offset(78f, 141f))
            drawCircle(TokkiColors.Ink, radius = 5.3f, center = Offset(122f, 141f))
            drawCircle(TokkiColors.Ink, radius = 3.2f, center = Offset(100f, 160f))
        }
        TokkiMood.SPEAKING -> {
            drawCircle(TokkiColors.Ink, radius = 4.8f, center = Offset(78f, 142f))
            drawCircle(TokkiColors.Ink, radius = 4.8f, center = Offset(122f, 142f))
            drawOval(TokkiColors.Ink, topLeft = Offset(93f, 154f), size = Size(14f, 11f))
            drawOval(TokkiColors.Tongue, topLeft = Offset(96f, 159.5f), size = Size(8f, 4.5f))
        }
        TokkiMood.WORRIED -> {
            line(Path().apply { moveTo(66f, 129f); lineTo(84f, 125f) }, width = 3.5f)
            line(Path().apply { moveTo(134f, 129f); lineTo(116f, 125f) }, width = 3.5f)
            drawCircle(TokkiColors.Ink, radius = 4.5f, center = Offset(78f, 144f))
            drawCircle(TokkiColors.Ink, radius = 4.5f, center = Offset(122f, 144f))
            line(Path().apply { moveTo(92f, 163f); quadraticBezierTo(96f, 159f, 100f, 163f); quadraticBezierTo(104f, 167f, 108f, 163f) })
        }
        TokkiMood.HAPPY -> {
            line(Path().apply { moveTo(71f, 145f); quadraticBezierTo(78f, 135f, 85f, 145f) }, width = 4f)
            line(Path().apply { moveTo(115f, 145f); quadraticBezierTo(122f, 135f, 129f, 145f) }, width = 4f)
            drawPath(
                Path().apply {
                    moveTo(92f, 156f); lineTo(108f, 156f); quadraticBezierTo(107f, 167f, 100f, 167f); quadraticBezierTo(93f, 167f, 92f, 156f); close()
                },
                TokkiColors.Ink,
            )
        }
        TokkiMood.SLEEPING -> {
            line(Path().apply { moveTo(71f, 141f); quadraticBezierTo(78f, 148f, 85f, 141f) }, width = 3.5f)
            line(Path().apply { moveTo(115f, 141f); quadraticBezierTo(122f, 148f, 129f, 141f) }, width = 3.5f)
            drawCircle(TokkiColors.Ink, radius = 2.5f, center = Offset(100f, 160f))
            // 잘 때는 귀가 바깥으로 처지므로 "z"는 오른쪽 귀 바깥 아래쪽에 (귀와 겹치지 않게), 작은 z 하나 더
            line(Path().apply { moveTo(168f, 78f); lineTo(180f, 78f); lineTo(168f, 92f); lineTo(180f, 92f) }, width = 3f, color = LunaColors.TextSecondary)
            line(Path().apply { moveTo(182f, 60f); lineTo(190f, 60f); lineTo(182f, 69f); lineTo(190f, 69f) }, width = 2.5f, color = LunaColors.TextSecondary)
        }
    }
}
