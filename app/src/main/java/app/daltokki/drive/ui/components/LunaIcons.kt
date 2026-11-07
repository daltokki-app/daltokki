package app.daltokki.drive.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * 앱 전용 선 아이콘 (24×24). Icon(tint = …)으로 색을 입힙니다.
 * 외부 아이콘 라이브러리 없이 시안(D안)과 같은 모양을 씁니다.
 */
object LunaIcons {

    private fun stroke(name: String, vararg paths: String, width: Float = 2f): ImageVector {
        val b = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        paths.forEach { d ->
            b.addPath(
                pathData = addPathNodes(d),
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = width,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
        return b.build()
    }

    private fun filled(name: String, path: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
            .addPath(pathData = addPathNodes(path), fill = SolidColor(Color.Black))
            .build()

    val Moon: ImageVector by lazy { filled("moon", "M20 14.5A8.5 8.5 0 1 1 9.5 4a7 7 0 0 0 10.5 10.5z") }

    val Mic: ImageVector by lazy {
        stroke("mic", "M9 6a3 3 0 0 1 6 0v5a3 3 0 0 1-6 0z", "M5 11a7 7 0 0 0 14 0", "M12 18v3")
    }

    val Coffee: ImageVector by lazy {
        stroke("coffee", "M4 9h12v5a5 5 0 0 1-5 5H9a5 5 0 0 1-5-5z", "M16 11h2a2 2 0 0 1 0 4h-2", "M8 3v3M12 3v3", width = 1.8f)
    }

    val Lock: ImageVector by lazy {
        stroke(
            "lock",
            "M7 11h10a2 2 0 0 1 2 2v6a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2v-6a2 2 0 0 1 2-2z",
            "M8 11V8a4 4 0 0 1 8 0v3",
            width = 2.2f,
        )
    }

    val ArrowDown: ImageVector by lazy { stroke("arrow_down", "M12 5v14M5 12l7 7 7-7", width = 2.6f) }

    val Clock: ImageVector by lazy {
        stroke("clock", "M12 3a9 9 0 1 1 0 18a9 9 0 1 1 0-18z", "M12 7v5l3 2", width = 1.8f)
    }

    /** 바람(환기 제안) */
    val Wind: ImageVector by lazy {
        stroke("wind", "M3 8h11a3 3 0 1 0-3-3", "M3 12h15a3 3 0 1 1-3 3", "M3 16h7", width = 1.9f)
    }

    /** 체크(깨어 있음 확인) */
    val Check: ImageVector by lazy { stroke("check", "M5 12.5l4.5 4.5L19 7.5", width = 2.6f) }

    /** 전화(통화 중에는 기다림) */
    val Phone: ImageVector by lazy {
        stroke(
            "phone",
            "M5 4h3.5l1.8 4.5-2.3 1.5a11 11 0 0 0 6 6l1.5-2.3L20 15.5V19a2 2 0 0 1-2 2A16 16 0 0 1 3 6a2 2 0 0 1 2-2z",
            width = 1.9f,
        )
    }

    /** 사람(운전자 말풍선) */
    val Person: ImageVector by lazy {
        stroke("person", "M12 4a4 4 0 1 1 0 8a4 4 0 1 1 0-8z", "M4.5 20.5a7.5 7.5 0 0 1 15 0", width = 1.9f)
    }

    val Medal: ImageVector by lazy {
        stroke("medal", "M12 3a6 6 0 1 1 0 12a6 6 0 1 1 0-12z", "M9 14.5L8 21l4-2 4 2-1-6.5")
    }
}
