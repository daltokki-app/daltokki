package app.daltokki.drive.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.daltokki.drive.domain.game.GameKind
import app.daltokki.drive.domain.game.GamePicker
import app.daltokki.drive.domain.model.FatigueLevel
import app.daltokki.drive.ui.theme.LunaColors

/** 게임 고르기 창의 한 줄 설명 */
private fun GameKind.hint(): String = when (this) {
    GameKind.WORD -> "기차 → 차표 → 표범"
    GameKind.CHOSUNG -> "기역 치읓 → 기차"
    GameKind.QUIZ -> "짧은 상식 문제"
    GameKind.FOOD -> "휴게소 대표 메뉴 맞히기"
    GameKind.RIDDLE -> "다리는 넷인데 못 걷는 것은?"
    GameKind.DIGITS -> "3, 7, 1, 9 따라 말하기"
    GameKind.REACTION -> "\"달!\" 하면 바로 \"빛!\""
}

/** 화면 순서: 첫 칸 '알아서 골라 줘', 그다음 말놀이 → 짧은 게임 */
private val ORDER = listOf(
    GameKind.WORD, GameKind.CHOSUNG, GameKind.QUIZ, GameKind.FOOD,
    GameKind.RIDDLE, GameKind.DIGITS, GameKind.REACTION,
)

/**
 * 게임 고르기 창: 큰 칸 2줄 × 4칸, 스크롤 없음.
 * 첫 칸 [알아서 골라 줘]가 가장 눈에 띄게(운전 중에는 이것만 눌러도 됨). 말로 "초성 게임 하자"도 그대로 됩니다.
 * '경고' 단계에서는 반응 게임·숫자 따라 말하기만 누를 수 있고, 나머지는 흐리게 "지금은 짧은 게임만".
 * 실제 주행 중(차량 화면 제한)이면 [알아서 골라 줘]만 누를 수 있고, 나머지는 말로 고르게 안내합니다.
 *
 * @param onPick 고른 게임 (null = 알아서 골라 줘)
 */
@Composable
fun GamePickerDialog(level: FatigueLevel, onPick: (GameKind?) -> Unit, onDismiss: () -> Unit, restricted: Boolean = false) {
    // 실제 주행 중(차량 화면 제한)에는 화면 조작을 줄이려고 [알아서 골라 줘]만, 나머지는 말로 ("초성 게임 하자")
    val allowed = if (restricted) emptyList() else GamePicker.allowed(level)
    val tiles: List<GameKind?> = listOf<GameKind?>(null) + ORDER
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = LunaColors.Surface,
        title = { Text("어떤 게임 할까요?", color = LunaColors.TextPrimary, fontSize = 26.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.widthIn(min = 640.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                tiles.chunked(4).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        row.forEach { kind ->
                            val enabled = kind == null || kind in allowed
                            GameTile(
                                title = kind?.label ?: "알아서 골라 줘",
                                hint = when {
                                    kind == null -> "지금 컨디션에 맞게"
                                    enabled -> kind.hint()
                                    restricted -> "운전 중엔 말로: \"${kind.label} 하자\""
                                    else -> "지금은 짧은 게임만"
                                },
                                primary = kind == null,
                                enabled = enabled,
                                modifier = Modifier.weight(1f),
                                onClick = { onPick(kind) },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("닫기", color = LunaColors.TextSecondary, fontSize = 18.sp) }
        },
    )
}

@Composable
private fun GameTile(title: String, hint: String, primary: Boolean, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val bg = when {
        primary -> LunaColors.Moon
        else -> LunaColors.Inner
    }
    val titleColor = when {
        primary -> LunaColors.Background
        enabled -> LunaColors.TextPrimary
        else -> LunaColors.TextSecondary.copy(alpha = 0.5f)
    }
    val hintColor = when {
        primary -> LunaColors.Background.copy(alpha = 0.8f)
        enabled -> LunaColors.TextSecondary
        else -> LunaColors.TextSecondary.copy(alpha = 0.5f)
    }
    Column(
        modifier
            .height(112.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(bg)
            .then(if (primary) Modifier else Modifier.border(BorderStroke(2.dp, LunaColors.Outline), RoundedCornerShape(20.dp)))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, color = titleColor, fontSize = 21.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(6.dp))
        Text(hint, color = hintColor, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 19.sp)
    }
}
