package app.daltokki.drive.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.daltokki.drive.ui.theme.LunaColors

/**
 * 달토끼 아바타: 초승달 얼굴 + 피로 단계 색의 링.
 * 말할 때는 링이 숨 쉬듯 커지고, 들을 때는 점선 대신 얇은 링이 깜빡입니다.
 */
@Composable
fun LunaAvatar(
    ringColor: Color,
    speaking: Boolean,
    listening: Boolean,
    progress: Float? = null,
    size: Dp = 220.dp,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "luna")
    val pulse by transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "pulse",
    )
    Canvas(modifier = modifier.size(size)) {
        val r = this.size.minDimension / 2f
        val c = center
        // 바깥 링
        val ringWidth = r * 0.07f
        val ringR = r - ringWidth
        val ringScale = if (speaking) 1f + 0.03f * pulse else 1f
        // 링 안쪽 바탕(불투명). 초승달을 깎아낼 때도 같은 색을 써야 자연스럽습니다.
        val fill = ringColor.copy(alpha = 0.18f).compositeOver(LunaColors.Background)
        drawCircle(color = fill, radius = ringR * ringScale, center = c)
        if (progress != null) {
            drawArc(
                color = ringColor, startAngle = -90f, sweepAngle = 360f * progress, useCenter = false,
                topLeft = Offset(c.x - ringR, c.y - ringR), size = Size(ringR * 2, ringR * 2),
                style = Stroke(width = ringWidth),
            )
        } else {
            drawCircle(color = ringColor, radius = ringR * ringScale, center = c, style = Stroke(width = ringWidth))
        }
        if (listening) {
            drawCircle(
                color = LunaColors.TextPrimary.copy(alpha = 0.25f + 0.5f * pulse),
                radius = ringR * 0.9f, center = c, style = Stroke(width = ringWidth * 0.4f),
            )
        }
        // 초승달 얼굴
        val moonR = r * 0.52f
        drawCircle(color = LunaColors.Moon, radius = moonR, center = c)
        drawCircle(color = fill, radius = moonR * 0.86f, center = Offset(c.x + moonR * 0.55f, c.y - moonR * 0.28f))
        // 눈(감은 눈 곡선 대신 작은 점) + 볼
        val eye = Offset(c.x - moonR * 0.55f, c.y + moonR * 0.05f)
        drawCircle(color = Color(0xFF2A2440), radius = moonR * 0.07f, center = eye)
        drawCircle(color = Color(0xFFFF9FA8).copy(alpha = 0.7f), radius = moonR * 0.09f, center = Offset(eye.x + moonR * 0.05f, eye.y + moonR * 0.28f))
        // 별
        val star = Offset(c.x + moonR * 0.75f, c.y - moonR * 0.75f)
        drawCircle(color = Color.White.copy(alpha = 0.6f + 0.4f * pulse), radius = r * 0.03f, center = star)
    }
}
