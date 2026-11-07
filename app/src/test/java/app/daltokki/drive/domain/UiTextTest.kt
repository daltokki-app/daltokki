package app.daltokki.drive.domain

import app.daltokki.drive.domain.dialog.LunaLines
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 화면 글이 짧고 겹치지 않게 */
class UiTextTest {
    @Test
    fun stretchCard_oneLineSummaryPerSpokenStep() {
        // 화면 요약과 달토끼가 말하는 단계 수가 같아야 번호가 맞음
        assertEquals(LunaLines.stretchSteps.size, LunaLines.stretchShort.size)
        // 카드 한 줄에 들어가게 짧게 (12자 이하)
        LunaLines.stretchShort.forEach { assertTrue(it, it.length <= 12) }
    }
}
