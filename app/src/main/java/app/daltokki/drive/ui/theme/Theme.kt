package app.daltokki.drive.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import app.daltokki.drive.domain.model.FatigueLevel

/**
 * 달빛 미니멀(디자인 A안) 색상: 짙은 남색 바탕 + 달빛 노랑 한 가지 강조색.
 * 단계 색(초록·주황노랑·주황·빨강)은 밝기도 서로 달라 색 구분이 어려워도 알아볼 수 있게.
 */
object LunaColors {
    val Background = Color(0xFF0B1020)
    val Surface = Color(0xFF141B33)
    val SurfaceHigh = Color(0xFF1D2540)
    /** 카드 안쪽 블록 */
    val Inner = Color(0xFF0F1530)
    val Track = Color(0xFF1D2540)
    val Outline = Color(0xFF3A4266)

    /** 위험 단계 카드 */
    val DangerSurface = Color(0xFF1E1A2E)
    val DangerInner = Color(0xFF2A2433)
    val DangerChip = Color(0xFF2A2238)
    val DangerOutline = Color(0xFF4A3F5E)

    val Moon = Color(0xFFF5D37A)
    val TextPrimary = Color(0xFFF2F0E6)
    val TextSecondary = Color(0xFFA9B0C6)
    val TextOnDanger = Color(0xFFC9CDE0)

    val Normal = Color(0xFF5ED39A)
    val Caution = Color(0xFFF4B740)
    val Warning = Color(0xFFFF8A4C)
    val Danger = Color(0xFFFF5A6A)

    // 데모 그래프
    val Context = Color(0xFF8FB8F0)
    val Steering = Color(0xFFFF9A4A)
    val Dialog = Color(0xFFC08BFF)
    val Sensor = Color(0xFF7A82A6)

    fun level(l: FatigueLevel?): Color = when (l) {
        FatigueLevel.CAUTION -> Caution
        FatigueLevel.WARNING -> Warning
        FatigueLevel.DANGER -> Danger
        else -> Normal
    }

    fun levelSoft(l: FatigueLevel?): Color = level(l).copy(alpha = 0.16f)
}

@Composable
fun LunaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = LunaColors.Moon,
            onPrimary = LunaColors.Background,
            secondary = LunaColors.Context,
            background = LunaColors.Background,
            surface = LunaColors.Surface,
            surfaceVariant = LunaColors.SurfaceHigh,
            onBackground = LunaColors.TextPrimary,
            onSurface = LunaColors.TextPrimary,
            onSurfaceVariant = LunaColors.TextSecondary,
            outline = LunaColors.Outline,
        ),
        content = content,
    )
}
