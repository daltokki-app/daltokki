package app.daltokki.drive.domain

import app.daltokki.drive.domain.fatigue.DropCause
import app.daltokki.drive.domain.fatigue.RiskDropTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** 행동 뒤 위험도가 '실제로' 내려갔을 때만 "−N · 행동" 표시 */
class RiskDropTest {

    @Test
    fun answer_showsOnlyWhenDialogSignalImproves() {
        val r = RiskDropTracker()
        r.onRisk(0, true, 40, 0.5f, null)
        r.onAction(1_000, DropCause.ANSWER, "빠른 대답", 40, 0.5f)
        // 위험도는 내려갔지만 대화 신호가 그대로면 (다른 이유) 표시 안 함
        assertNull(r.onRisk(2_000, true, 37, 0.5f, null))
        // 대화 신호가 줄고 2 이상 내려가면 표시
        val d1 = r.onRisk(3_000, true, 38, 0.4f, null)
        assertNotNull(d1)
        assertEquals(2, d1!!.amount)
        assertEquals("빠른 대답", d1.label)
        // 같은 숫자면 다시 안 띄움, 더 내려가면 합계를 늘려 같은 행동으로
        assertNull(r.onRisk(4_000, true, 38, 0.4f, null))
        val d2 = r.onRisk(5_000, true, 35, 0.35f, null)!!
        assertEquals(5, d2.amount)
        assertEquals(d1.actionSeq, d2.actionSeq)
        // 90초가 지나면 그 행동 덕분이라고 보지 않음
        assertNull(r.onRisk(100_000, true, 30, 0.2f, null))
    }

    @Test
    fun noDropWhenRiskRisesOrHidden() {
        val r = RiskDropTracker()
        r.onAction(0, DropCause.ANSWER, "빠른 대답", 30, 0.3f)
        assertNull(r.onRisk(1_000, true, 33, 0.2f, null))
        assertNull(r.onRisk(2_000, false, 20, 0.1f, null)) // 숫자가 안 보일 때
        assertNull(r.onRisk(3_000, true, 29, 0.1f, null)) // 1만 내려감
    }

    @Test
    fun freshAir_needsCo2Drop() {
        val r = RiskDropTracker()
        r.onAction(0, DropCause.AIR, "외기 전환", 50, 1_800f)
        assertNull(r.onRisk(60_000, true, 46, null, 1_790f))
        val d = r.onRisk(120_000, true, 45, null, 1_500f)!!
        assertEquals(5, d.amount)
        assertEquals("외기 전환", d.label)
    }

    @Test
    fun rest_showsOnceAfterDrivingResumes() {
        val r = RiskDropTracker()
        r.onRisk(0, true, 62, null, null)
        r.onRisk(10_000, false, 62, null, null) // 정차·휴식
        r.onRest(700_000)
        // 쉬는 동안 대답이 와도 휴식 결과는 지킴
        r.onAction(710_000, DropCause.ANSWER, "빠른 대답", 10, 0.1f)
        assertNull(r.onRisk(720_000, false, 8, null, null))
        // 다시 달리고 5초 뒤 한 번
        assertNull(r.onRisk(900_000, true, 9, null, null))
        val d = r.onRisk(905_000, true, 10, null, null)!!
        assertEquals(52, d.amount)
        assertEquals("휴식", d.label)
        assertNull(r.onRisk(910_000, true, 5, null, null))
    }
}
