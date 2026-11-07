package app.daltokki.drive.domain.game

import app.daltokki.drive.domain.model.FatigueLevel
import app.daltokki.drive.domain.model.TripStats
import kotlin.random.Random

/** 달토끼 꾸미기 아이템 (달빛 선물 상자에서 나옴, 운전자별로 모음) */
object Cosmetics {
    data class Item(val id: String, val name: String, val rare: Boolean = false)

    val HAT = Item("hat", "달 모자")
    val RIBBON = Item("ribbon", "별 리본")
    val SCARF = Item("scarf", "빨간 목도리")
    val STAR_GOGGLE = Item("star_goggle", "별 고글")
    val CROWN = Item("crown", "달빛 왕관", rare = true)

    val all = listOf(HAT, RIBBON, SCARF, STAR_GOGGLE, CROWN)

    fun byId(id: String?): Item? = all.firstOrNull { it.id == id }
}

/** 달빛 선물 상자에서 나온 것 */
sealed class Gift {
    data class Points(val points: Int) : Gift()
    data class Item(val item: Cosmetics.Item) : Gift()
}

/**
 * 달빛 선물 상자: 휴게소에서 10분 쉴 때마다 하나. 열어 보기 전에는 무엇이 나올지 모름(매번 다른 보상이 가장 설렘).
 * - 처음 여는 상자는 꼭 꾸미기 아이템 (첫 경험을 확실히)
 * - 그다음: 아직 없는 아이템 45% (그중 왕관은 드묾), 보너스 점수 50~300점 55%
 * - 돈이 오가지 않는 점수·꾸미기라 사행성 없음
 */
object GiftBox {
    fun open(random: Random, owned: Set<String>): Gift {
        val notOwned = Cosmetics.all.filter { it.id !in owned }
        val common = notOwned.filter { !it.rare }
        if (owned.isEmpty() && common.isNotEmpty()) return Gift.Item(common[random.nextInt(common.size)])
        if (notOwned.isNotEmpty() && random.nextFloat() < 0.45f) {
            val rare = notOwned.firstOrNull { it.rare }
            val pick = if (rare != null && (common.isEmpty() || random.nextFloat() < 0.15f)) rare else common[random.nextInt(common.size)]
            return Gift.Item(pick)
        }
        return Gift.Points(50 + random.nextInt(26) * 10)
    }
}

/**
 * 운행 결과 (게임 결과 화면처럼): 별 3개 · 등급 · 신기록.
 * ★1 안전: 졸음 신호 없이('주의'까지) 끝났거나 졸리면 쉼
 * ★2 안전 행동 2번 이상 (외기 전환·온도 낮추기·휴게소 가기·제때 쉬기·졸림 신고 등)
 * ★3 최고 콤보 3 이상
 * 등급: 별 3개 S · 2개 A · 1개 B · 0개 C
 */
data class TripResult(
    val stars: List<Boolean>,
    val grade: String,
    val points: Int,
    val bestCombo: Int,
    val newBestScore: Boolean,
    val newBestCombo: Boolean,
) {
    val starCount: Int get() = stars.count { it }

    companion object {
        val STAR_LABELS = listOf("안전 운행", "안전 행동 2번", "3콤보 이상")

        fun compute(
            stats: TripStats,
            tripPoints: Int,
            bestCombo: Int,
            safetyActions: Int,
            prevBestPoints: Int,
            prevBestCombo: Int,
        ): TripResult {
            val stars = listOf(
                stats.maxLevel <= FatigueLevel.CAUTION || stats.rests > 0,
                safetyActions >= 2,
                bestCombo >= 3,
            )
            val grade = when (stars.count { it }) {
                3 -> "S"
                2 -> "A"
                1 -> "B"
                else -> "C"
            }
            return TripResult(
                stars, grade, tripPoints, bestCombo,
                newBestScore = tripPoints > prevBestPoints && tripPoints > 0,
                newBestCombo = bestCombo > prevBestCombo && bestCombo >= 2,
            )
        }
    }
}
