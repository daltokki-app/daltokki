package app.daltokki.drive.domain.game

/** 한글 음절 분해·조합, 두음법칙, 조사 선택 */
object Hangul {
    private const val BASE = 0xAC00
    private const val LAST = 0xD7A3

    private const val CHO_N = 2   // ㄴ
    private const val CHO_R = 5   // ㄹ
    private const val CHO_NG = 11 // ㅇ

    // 중성 번호: ㅑ2 ㅕ6 ㅖ7 ㅛ12 ㅠ17 ㅣ20
    private val R_TO_NG_VOWELS = setOf(2, 6, 7, 12, 17, 20)
    private val N_TO_NG_VOWELS = setOf(6, 12, 17, 20)

    fun isSyllable(c: Char): Boolean = c.code in BASE..LAST

    fun cho(c: Char): Int = (c.code - BASE) / 588
    fun jung(c: Char): Int = ((c.code - BASE) % 588) / 28
    fun jong(c: Char): Int = (c.code - BASE) % 28

    fun compose(cho: Int, jung: Int, jong: Int): Char = (BASE + cho * 588 + jung * 28 + jong).toChar()

    fun hasBatchim(c: Char): Boolean = isSyllable(c) && jong(c) != 0

    /**
     * 두음법칙 적용 결과. 적용 대상이 아니면 null.
     * 예) 력→역, 리→이, 라→나, 로→노, 녀→여, 뉴→유
     */
    fun dueum(c: Char): Char? {
        if (!isSyllable(c)) return null
        val ch = cho(c)
        val ju = jung(c)
        val jo = jong(c)
        val newCho = when (ch) {
            CHO_R -> if (ju in R_TO_NG_VOWELS) CHO_NG else CHO_N
            CHO_N -> if (ju in N_TO_NG_VOWELS) CHO_NG else return null
            else -> return null
        }
        return compose(newCho, ju, jo)
    }

    /** 끝말잇기에서 이 글자 다음에 시작할 수 있는 글자들 */
    fun startOptions(last: Char): Set<Char> = setOfNotNull(last, dueum(last))

    /** 받침 유무에 따라 조사 선택. 예) josa("휴게소", "을", "를") → "를" */
    fun josa(word: String, withBatchim: String, withoutBatchim: String): String {
        val last = word.lastOrNull() ?: return withoutBatchim
        return if (hasBatchim(last)) withBatchim else withoutBatchim
    }
}
