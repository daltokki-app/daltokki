package app.daltokki.drive.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.ui.Modifier
import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import app.daltokki.drive.ui.theme.LunaColors
import kotlin.math.cos
import kotlin.math.sin

/**
 * 반원 위험도 게이지 (A안 게이지를 위젯용으로).
 * 가로:세로 = 600:340 비율로 그리며, 주의(30)·경고(50)·위험(75) 위치에 눈금을 표시합니다.
 * 숫자와 단계 표시는 이 게이지 위에 겹쳐서 따로 그립니다.
 */
@Composable
fun RiskGauge(
    risk: Int,
    color: Color,
    modifier: Modifier = Modifier,
    trackColor: Color = LunaColors.Track,
    knobFill: Color = LunaColors.Surface,
    showTicks: Boolean = true,
) {
    Canvas(modifier) {
        val unit = size.width / 600f
        val cx = size.width / 2f
        val cy = 300f * unit
        val r = 260f * unit
        val stroke = 30f * unit
        val topLeft = Offset(cx - r, cy - r)
        val arcSize = Size(r * 2, r * 2)

        drawArc(trackColor, 180f, 180f, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
        val sweep = 180f * risk.coerceIn(0, 100) / 100f
        if (sweep > 0f) {
            drawArc(color, 180f, sweep, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
        }

        if (showTicks) {
            listOf(30 to LunaColors.Caution, 50 to LunaColors.Warning, 75 to LunaColors.Danger).forEach { (v, c) ->
                val a = Math.toRadians(180.0 + 180.0 * v / 100.0)
                val r1 = 278f * unit
                val r2 = 298f * unit
                drawLine(
                    c,
                    Offset(cx + r1 * cos(a).toFloat(), cy + r1 * sin(a).toFloat()),
                    Offset(cx + r2 * cos(a).toFloat(), cy + r2 * sin(a).toFloat()),
                    strokeWidth = 5f * unit,
                    cap = StrokeCap.Round,
                )
            }
        }

        // 손잡이
        val ka = Math.toRadians(180.0 + 180.0 * risk.coerceIn(0, 100) / 100.0)
        val knob = Offset(cx + r * cos(ka).toFloat(), cy + r * sin(ka).toFloat())
        drawCircle(knobFill, 19f * unit, knob)
        drawCircle(color, 19f * unit, knob, style = Stroke(8f * unit))
    }
}
