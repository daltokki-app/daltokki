package app.daltokki.drive.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.daltokki.drive.engine.GraphPoint
import app.daltokki.drive.ui.theme.LunaColors

/**
 * 피로 점수 그래프 (PT 시연의 핵심 장면).
 * 흰색 굵은 선 = 최종 점수, 파랑 = 상황 위험도, 주황 = 핸들 이상, 보라 = 대화 반응 저하.
 * 점선 = 주의(0.3) / 경고(0.5) / 위험(0.75) 기준.
 */
@Composable
fun FatigueGraph(points: List<GraphPoint>, modifier: Modifier = Modifier) {
    Column(modifier) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(190.dp)
                .background(LunaColors.Background),
        ) {
            val w = size.width
            val h = size.height
            val maxMin = (points.lastOrNull()?.minute ?: 0f).coerceAtLeast(10f)
            fun x(m: Float) = (m / maxMin) * w
            fun y(v: Float) = h - v.coerceIn(0f, 1f) * h

            val dash = PathEffect.dashPathEffect(floatArrayOf(10f, 10f))
            listOf(0.3f to LunaColors.Caution, 0.5f to LunaColors.Warning, 0.75f to LunaColors.Danger).forEach { (v, c) ->
                drawLine(c.copy(alpha = 0.6f), Offset(0f, y(v)), Offset(w, y(v)), strokeWidth = 2f, pathEffect = dash)
            }
            if (points.size >= 2) {
                series(points, { it.context }, LunaColors.Context, 3f, ::x, ::y)
                series(points, { it.steering }, LunaColors.Steering, 3f, ::x, ::y)
                series(points, { it.dialog }, LunaColors.Dialog, 3f, ::x, ::y)
                // 굵은 흰 선 = 졸음 위험도(화면 게이지와 같은 값), 회색 = 말 걸기 판단(깨어 있음 확인 반영)
                series(points, { it.score }, LunaColors.Sensor, 3f, ::x, ::y)
                series(points, { it.sensor }, Color.White, 6f, ::x, ::y)
            }
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Legend("위험도", Color.White)
            Legend("말 걸기", LunaColors.Sensor)
            Legend("상황", LunaColors.Context)
            Legend("핸들", LunaColors.Steering)
            Legend("대화", LunaColors.Dialog)
            Text(
                "${"%.0f".format(points.lastOrNull()?.minute ?: 0f)}분",
                color = LunaColors.TextSecondary, fontSize = 13.sp,
            )
        }
    }
}

private fun DrawScope.series(
    points: List<GraphPoint>,
    value: (GraphPoint) -> Float?,
    color: Color,
    width: Float,
    x: (Float) -> Float,
    y: (Float) -> Float,
) {
    val path = Path()
    var started = false
    for (p in points) {
        val v = value(p)
        if (v == null) {
            started = false
            continue
        }
        if (!started) {
            path.moveTo(x(p.minute), y(v)); started = true
        } else {
            path.lineTo(x(p.minute), y(v))
        }
    }
    drawPath(path, color, style = Stroke(width = width))
}

@Composable
private fun Legend(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(color, CircleShape))
        Text(" $label", color = LunaColors.TextSecondary, fontSize = 13.sp)
    }
}
