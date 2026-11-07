package app.daltokki.drive.domain.dialog

import app.daltokki.drive.domain.game.Hangul
import app.daltokki.drive.domain.game.WordChainEngine
import java.util.Locale
import kotlin.random.Random

/**
 * 달토끼의 고정 대사. 미리 만들어 두면 LLM 호출 없이 바로 말할 수 있어 응답이 빠릅니다.
 * 말투: 친근한 반말, 짧게(운전 중에 듣기 편하게 한두 문장).
 */
object LunaLines {

    fun pick(list: List<String>, random: Random = Random.Default): String = list.random(random)

    val greeting = listOf(
        "출발! 오늘도 옆자리에서 같이 갈게.",
        "좋아, 출발하자! 달토끼가 옆에 있을게.",
        "안전운전 시작! 졸리면 언제든 달토끼야 하고 불러줘.",
    )

    val greetingQuestion = listOf(
        "오늘은 어디 가는 길이야?",
        "오늘 컨디션은 어때?",
        "지금 어디로 가는 중이야?",
    )

    val greetingNoAnswer = listOf(
        "대답은 나중에 해도 돼. 졸리면 언제든 달토끼야 하고 불러줘!",
    )

    val checkInQuestions = listOf(
        "있잖아, 오늘 점심 뭐 먹었어?",
        "지금 제일 듣고 싶은 노래 하나만 말해줄래?",
        "요즘 제일 재밌었던 일 하나만 말해줘!",
        "도착하면 제일 먼저 뭐 할 거야?",
        "너는 커피파야, 차파야?",
        "이번 주말엔 뭐 할 거야?",
        "요즘 제일 먹고 싶은 음식이 뭐야?",
    )

    val noResponse = listOf(
        "달토끼 말 들려? 대답 한 번만 해줘!",
        "음? 내 말 들었어? 한마디만 해줘!",
    )

    val noResponseFinal = listOf(
        "대답이 없어서 걱정돼. 창문 조금 열고 바람 좀 쐬자.",
        "많이 피곤한 거 아니지? 에어컨 바람을 조금 세게 해 보자.",
    )

    val offerWordGame = listOf(
        "심심한데 끝말잇기 한 판 할래?",
        "졸음 쫓는 데는 끝말잇기가 최고야. 한 판 할래?",
    )

    val offerQuiz = listOf(
        "간단한 퀴즈 하나 낼게, 맞혀볼래?",
        "머리 좀 깨워볼까? 퀴즈 세 문제 어때?",
    )

    val gameDeclined = listOf(
        "알겠어! 대신 졸리면 꼭 말해줘.",
        "오케이, 다음에 하자. 피곤하면 바로 말해줘!",
    )

    val gameTimeout = listOf(
        "대답이 없네… 많이 피곤한 거 아니지? 이번 판은 여기까지!",
        "어? 생각이 안 나? 졸린 거면 꼭 쉬어가자. 게임은 여기까지!",
    )

    val gameEnd = listOf(
        "끝말잇기 실력 좋은데? 정신 번쩍 들었지?",
        "재밌었다! 이제 좀 깨어난 것 같아?",
    )

    val lunaGivesUp = listOf(
        "으악, 생각이 안 나… 네가 이겼어!",
        "졌다! 그 단어는 못 이어가겠어. 대단한데?",
    )

    val alert = listOf(
        "잠깐! 지금 많이 피곤해 보여. 창문 조금 열고, 다음 휴게소에서 꼭 쉬어가자.",
        "위험해! 핸들이 자꾸 흔들려. 지금 바로 쉬어가는 게 좋겠어.",
    )

    val sleepyAck = listOf(
        "솔직하게 말해줘서 고마워. 졸릴 땐 쉬는 게 제일 좋아.",
        "졸리구나. 말해줘서 잘했어. 바로 쉴 곳부터 찾아볼게.",
    )

    val quietAck = listOf(
        "알겠어, 조용히 있을게. 대신 많이 피곤해 보이면 그땐 말할게.",
    )

    val resumeAck = listOf(
        "다시 수다 모드! 심심하면 언제든 불러줘.",
    )

    val restArrived = listOf(
        "휴게소 도착! 잘했어. 10분 이상 쉬었다 가자.",
    )

    val stretchSteps = listOf(
        "먼저 차에서 내려서 크게 기지개 한 번 켜 봐.",
        "목을 천천히 오른쪽으로 다섯 번, 왼쪽으로 다섯 번 돌려 줘.",
        "어깨를 귀 쪽으로 끌어올렸다가 툭 떨어뜨리기, 다섯 번!",
        "허리에 손 얹고 상체를 뒤로 살짝 젖혀 줘. 오래 앉아 있으면 허리가 제일 힘들거든.",
        "마지막으로 물 한 잔 마시고, 10분 꽉 채워서 쉬었다 가자!",
    )

    /** 화면 카드용 한 줄 요약 (긴 설명은 달토끼가 말로 [stretchSteps]) */
    val stretchShort = listOf(
        "내려서 크게 기지개",
        "목 천천히 좌우 5번",
        "어깨 올렸다 툭 5번",
        "허리 뒤로 살짝 젖히기",
        "물 마시고 10분 쉬기",
    )

    val restCompleted = listOf(
        "10분 잘 쉬었어! 이제 다시 출발해도 좋아.",
    )

    val resumedRested = listOf(
        "다시 출발! 계속 옆에 있을게.",
    )

    val resumedEarly = listOf(
        "벌써 출발? 조금 더 쉬면 좋았을 텐데. 졸리면 바로 말해줘!",
    )

    val smallTalkFallback = listOf(
        "오 좋다! 계속 얘기해줘, 듣고 있을게.",
        "그렇구나! 그 얘기 더 듣고 싶다.",
        "헤헤, 재밌다. 운전하면서 나랑 계속 수다 떨자.",
    )

    // ── 말 수 '최소/보통'용 짧은 대사 ──

    val greetingShort = listOf(
        "출발! 졸리면 달토끼야 하고 불러줘.",
        "좋아, 출발하자. 졸리면 언제든 불러줘.",
    )

    /** 경고 단계에서 한 번만 하는 제안 (피곤할 때는 생각할 게 적은 반응 게임을 권함) */
    val offerRestOrGame = listOf(
        "졸음 신호가 보여. 쉬어갈까, 아니면 짧은 반응 게임으로 잠 깨워줄까?",
        "조금 피곤해 보여. 휴게소 들를까, 반응 게임 한 판 할까?",
    )

    // ── 반응 게임 ("달!" → "빛!") ──
    val reactionIntro = listOf(
        "반응 게임! 내가 '달' 하면 바로 '빛'이라고 외쳐줘. 간다?",
        "달빛 반응 게임 시작! '달' 들리면 바로 '빛'!",
    )
    const val REACTION_CUE = "달!"
    val reactionMiss = listOf(
        "앗, 놓쳤어!",
        "어? 못 들었어?",
    )

    fun reactionHit(ms: Long): String = when {
        ms <= 900 -> "번개! ${seconds(ms)}초!"
        ms <= 1_500 -> "좋아, ${seconds(ms)}초!"
        else -> "${seconds(ms)}초. 조금 느렸어."
    }

    /** 결과: 평균 반응 시간과 성공 횟수. 느리거나 많이 놓치면 휴식을 권함 */
    fun reactionResult(avgMs: Long?, hits: Int, rounds: Int): String = when {
        avgMs == null -> "한 번도 못 받았어… 많이 피곤한 것 같아. 꼭 쉬어가자."
        hits < rounds || avgMs > 1_800 -> "평균 ${seconds(avgMs)}초, ${rounds}번 중 ${hits}번. 반응이 느려졌어. 쉬어가는 게 좋겠어."
        avgMs <= 1_000 -> "평균 ${seconds(avgMs)}초! 반응 아주 좋아."
        else -> "평균 ${seconds(avgMs)}초, 다 받았어! 좋아."
    }

    private fun seconds(ms: Long): String = String.format(Locale.ROOT, "%.1f", ms / 1000.0)

    /** 끝말잇기 도중 피로가 올라 더 쉬운 게임으로 바꿀 때 */
    const val GAME_EASIER = "조금 피곤해 보여서 더 간단한 반응 게임으로 바꿀게."

    /** 경고 단계에서 "안 졸려": 반응 게임으로 확인 */
    val awakeClaimReaction = listOf(
        "좋아! 그럼 반응 게임으로 확인해 볼까?",
        "정말? 반응 게임 세 번만 받아 봐!",
    )

    // ── 출발 전 컨디션 (졸음 예보) ──
    val sleepQuestion = listOf(
        "출발 전에 하나만! 어젯밤 몇 시간 잤어?",
        "오늘 컨디션 체크! 어젯밤에 몇 시간 잤어?",
    )

    private fun hoursText(hours: Float): String {
        val h = hours.toInt()
        return if (hours - h >= 0.5f) "${h}시간 반" else "${h}시간"
    }

    /** 수면 답에 대한 반응 + 오늘의 권장 휴식 간격 */
    fun sleepAck(hours: Float, intervalMin: Int): String = when {
        hours < 1f -> "한숨도 못 잤구나… 오늘은 ${intervalMin}분마다 꼭 쉬어가자. 시간 되면 내가 알려줄게."
        intervalMin >= 120 && hours >= 7f -> "푹 잤구나! 그래도 2시간마다는 쉬어가자."
        intervalMin >= 120 -> "좋아, ${hoursText(hours)}. 2시간마다 쉬어가자."
        else -> "${hoursText(hours)} 잤구나. 오늘은 ${intervalMin}분마다 쉬어가자. 시간 되면 내가 알려줄게."
    }

    /** 대화 기록용 */
    fun sleepLog(hours: Float, intervalMin: Int) = "출발 전 컨디션 · 수면 ${hoursText(hours)} → 권장 휴식 간격 ${intervalMin}분"

    // ── 통화·음성 인식 상태 ──
    const val CALL_HOLD = "통화 중 · 말 걸기 잠시 대기 (화면 안내는 계속)"
    const val MISS_NOT_COUNTED = "음성 인식이 듣지 못하는 상태라 '대답 없음'은 졸음 판단에 넣지 않아요"
    const val NOT_HEARD_GAME_END = "목소리가 잘 안 들려. 이번 판은 여기까지 할게!"
    const val REACTION_NOT_HEARD = "목소리가 안 들렸어. 이번 건 빼고 할게."
    const val REACTION_VOICE_OFF = "음성 인식이 잘 안 돼서 반응 게임은 여기까지 할게."

    // ── 휴게소 도장 깨기 ──
    /** 도장 받음 (보통: 짧게, 수다: 대표 음식까지) */
    fun stamp(name: String, isNew: Boolean, places: Int, food: String?, withFood: Boolean): String {
        val head = if (isNew) "$name 도장 쾅! ${places}곳째 도장이야." else "$name 도장 쾅! 또 왔네."
        val tail = if (withFood && food != null) " 여기 대표 메뉴는 ${food}래." else ""
        return head + tail
    }

    /** 대화 기록·화면용 */
    fun stampLog(name: String, isNew: Boolean, places: Int, routeText: String?): String =
        (if (isNew) "도장 · $name (${places}곳째, +200점)" else "도장 · $name (다시 방문)") +
            (routeText?.let { " · $it" } ?: "")

    fun routeProgress(route: String, have: Int, total: Int) = "$route $have/${total}곳"

    val offerDeclined = listOf(
        "알겠어. 졸리면 바로 말해줘.",
    )

    val restShort = listOf(
        "휴게소 도착! 10분만 쉬어가. 스트레칭은 화면에 띄워 둘게.",
    )

    val tripEndShort = listOf(
        "오늘도 수고 많았어. 운행 리포트는 화면에 있어.",
    )

    /** "안 졸려"라고 먼저 말했을 때: 말만으로는 깨어 있음이 확인되지 않는다는 게임 규칙 안내 */
    val awakeClaimFree = listOf(
        "좋아! 근데 말로만은 확인이 안 돼. 끝말잇기로 증명해 볼래?",
        "오, 자신 있구나? 끝말잇기로 깨어 있는 거 보여줘!",
    )

    /** 차량이 운전자 주의 경고(DAW)를 띄웠을 때의 위험 경고 */
    val alertVehicleWarning = listOf(
        "차도 휴식을 권하고 있어. 다음 휴게소나 졸음쉼터에서 꼭 쉬어가자.",
        "자동차가 주의 경고를 띄웠어. 지금은 쉬어가는 게 제일 안전해.",
    )

    /** 내기순환이 오래 켜져 있을 때 (말 수 보통·수다) */
    val freshAir = listOf(
        "내기순환이 오래 켜져 있어. 외기로 바꾸면 공기가 덜 답답할 거야.",
        "공기가 좀 답답할 수 있어. 외기 유입으로 바꾸거나 창문을 살짝 열어 볼래?",
    )

    /** 화면 안내 문구 (말 수 최소) */
    fun freshAirChip(minutes: Int) = "내기순환 ${minutes}분째 · 외기로 바꾸면 덜 답답해요"

    fun freshAirChipCo2(ppm: Int) = "실내 CO₂ 추정 ${"%,d".format(Locale.US, ppm / 100 * 100)}ppm · 외기로 바꾸면 덜 답답해요"

    /** 달토끼 질문에 빠르게 "안 졸려"라고 답했을 때 */
    val awakeClaimFast = listOf(
        "오, 대답 빠른데? 좋아.",
    )

    /** 위험 단계에서 게임을 하자고 할 때 */
    val gamePausedDanger = listOf(
        "지금은 게임보다 휴식이 먼저야. 휴게소부터 들르자.",
        "졸음 신호가 강해서 게임은 잠깐 멈출게. 먼저 쉬어가자.",
    )

    fun talkLevelChanged(level: TalkLevel): String = when (level) {
        TalkLevel.MIN -> "알겠어, 말 수를 '최소'로 줄일게. 위험할 때만 말할게."
        TalkLevel.NORMAL -> "좋아, 말 수를 '보통'으로 할게."
        TalkLevel.CHATTY -> "좋아, 수다 모드! 내가 먼저 말 걸게."
    }

    fun talkLevelUnchanged(level: TalkLevel): String = when (level) {
        TalkLevel.MIN -> "지금도 최소로 말하고 있어. 위험할 때만 말할게."
        TalkLevel.CHATTY -> "이미 수다 모드야!"
        TalkLevel.NORMAL -> "지금은 '보통'이야."
    }

    fun newBadge(name: String) = "새 배지! '$name'"

    fun wordGameStart(word: String) = "내가 먼저 할게. $word!"

    fun wordGameReply(userWord: String, lunaWord: String) = "$userWord! 그럼 나는 $lunaWord!"

    fun wordGameInvalid(r: WordChainEngine.Result.Invalid): String {
        val starts = r.expected.joinToString(" 아니면 ") { "'$it'" }
        return when (r.reason) {
            WordChainEngine.Reason.WRONG_START -> "앗, $starts 글자로 시작해야 해. 다시 해볼래?"
            WordChainEngine.Reason.ALREADY_USED -> "${r.word}${Hangul.josa(r.word, "은", "는")} 이미 나왔어! 다른 단어!"
            WordChainEngine.Reason.UNKNOWN_WORD -> "음, ${r.word}${Hangul.josa(r.word, "은", "는")} 내 사전에 없어. 다른 단어 해줄래?"
            WordChainEngine.Reason.TOO_SHORT, WordChainEngine.Reason.NOT_HANGUL -> "잘 못 들었어. 두 글자 이상으로 한 번 더 말해줘!"
        }
    }

    fun quizCorrect(answer: String) = "딩동댕! 정답은 $answer!"
    fun quizWrong(answer: String) = "땡! 정답은 ${answer}였어."
    fun quizTimeout(answer: String) = "시간 끝! 정답은 ${answer}였어."

    fun restSuggest(name: String, km: Int) = "${name}까지 ${km}킬로미터 남았어. 경로에 추가할까?"
    fun restAdded(name: String) = "${name}${Hangul.josa(name, "을", "를")} 경로에 추가했어. 조금만 힘내자!"
    fun restAddFailed(name: String) = "경로 추가는 못 했지만, ${name}에서 꼭 쉬어가자!"
    fun restNoAnswer(name: String) = "대답이 없어서 걱정돼. 일단 ${name}에서 꼭 쉬어가자!"
    const val REST_DECLINED = "알겠어. 그래도 졸리면 바로 쉬어야 해, 약속!"
    const val REST_UNKNOWN = "가까운 휴게소나 졸음쉼터가 보이면 꼭 들러서 쉬어가자."
    const val GAME_ON_THE_WAY = "휴게소 가는 동안 나랑 끝말잇기 하면서 가자!"
    const val GAME_STOPPED = "알겠어, 여기까지! 재밌었어."
    const val MIC_UNAVAILABLE = "음성 인식을 쓸 수 없어서 화면 입력으로 받을게."

    fun tripReport(drivingMin: Int, rests: Int, interventions: Int, maxLevelLabel: String): String =
        "오늘 운전 정리해 줄게. 총 ${drivingMin}분 운전했고, 휴식은 ${rests}번, 내가 말 건 건 ${interventions}번이었어. " +
            "가장 피곤했던 순간은 '$maxLevelLabel' 단계였어. 오늘도 수고 많았어!"

    // ───────────────────────── 14차: 실내 공기 · 커피냅 · 게임 · 기억하는 대화 ─────────────────────────

    /** 환기 제안 (외기로 바꿀지 물음) */
    fun freshAirAsk(ppm: Float?): String =
        if (ppm != null && ppm >= 1_000f) {
            "실내 공기가 좀 답답해. 이산화탄소가 ${"%,d".format(Locale.US, ppm.toInt() / 100 * 100)}피피엠쯤으로 추정돼. 바깥 공기로 바꿔 줄까?"
        } else {
            "내기순환이 오래 켜져 있어. 바깥 공기로 바꿔 줄까?"
        }
    const val FRESH_AIR_DONE = "바깥 공기로 바꿨어. 금방 상쾌해질 거야."
    const val FRESH_AIR_NO_PERMISSION = "지금은 내가 공조를 바꿀 수 없어. 외기 버튼을 누르거나 창문을 조금 열어 줘."
    const val FRESH_AIR_DECLINED = "알겠어. 답답하면 말해 줘."

    const val NAP_ASK = "커피 한 잔 마시고 15분만 눈 붙여 볼래? 커피냅이라고, 깨면 훨씬 개운해."
    const val NAP_START = "좋아, 15분 뒤에 깨워 줄게. 푹 쉬어."
    const val NAP_DECLINED = "좋아, 그럼 간단한 스트레칭 알려 줄게."
    const val NAP_WAKE = "일어날 시간이야! 잘 잤어? 반응 게임으로 얼마나 개운해졌는지 볼까?"

    /** 커피냅 뒤 반응 재측정 결과 */
    fun napResult(beforeMs: Long?, afterMs: Long?): String = when {
        afterMs == null -> "아직 많이 졸린 것 같아. 조금 더 쉬었다 가자."
        beforeMs != null && afterMs < beforeMs -> "반응이 ${seconds(beforeMs)}초에서 ${seconds(afterMs)}초로 빨라졌어! 커피냅 효과 최고야."
        beforeMs != null -> "평균 ${seconds(afterMs)}초야. 아직 조금 피곤할 수 있으니 천천히 가자."
        else -> "평균 ${seconds(afterMs)}초! 아주 또렷해."
    }

    fun boredomAsk(gameLabel: String) = "한참 똑같은 길이네. 졸리기 전에 ${gameLabel} 한 판 할래?"
    const val BOREDOM_DECLINED = "알겠어. 지루해지면 불러 줘."

    const val CHOSUNG_START = "초성 게임 시작! 내가 초성을 말하면 그 초성으로 된 두 글자 낱말을 말해 줘."
    fun chosungCorrect(word: String) = "$word, 정답!"
    fun chosungWrong(example: String) = "아쉬워! ${example} 같은 말이 있었어."

    const val FOOD_START = "휴게소 음식 퀴즈! 휴게소 대표 메뉴를 맞혀 봐."
    fun foodCorrect(stop: String, food: String) = "딩동댕! ${stop} 대표 메뉴는 ${food}야."
    fun foodWrong(stop: String, food: String) = "아쉬워, ${stop} 대표 메뉴는 ${food}래."

    const val DIGITS_START = "숫자 따라 말하기! 내가 말하는 숫자를 그대로 따라 해 줘."
    const val DIGITS_CORRECT = "정확해!"
    fun digitsWrong(spoken: String) = "아까워! 정답은 ${spoken}였어."

    const val RIDDLE_START = "수수께끼 낼게!"
    fun riddleCorrect(reveal: String) = "정답! ${reveal}${Hangul.josa(reveal, "이야", "야")}."
    fun riddleWrong(reveal: String) = "정답은 ${reveal}${Hangul.josa(reveal, "이었어", "였어")}!"

    /** 기억하는 대화: "오늘도 부산 가?" */
    fun destinationAsk(dest: String) = "오늘도 ${dest} 가?"
    fun destinationYes(dest: String) = "좋아, ${dest}까지 같이 가자!"
    const val DESTINATION_OTHER = "그렇구나. 어디든 안전하게 같이 가자!"

    /** 휴게소 안내에 좋아하는 음식을 곁들임 (기억하는 대화) */
    fun restSuggestFavorite(name: String, km: Int, favorite: String) =
        "${name}까지 ${km}킬로미터 남았어. 좋아하는 ${favorite}도 있대. 경로에 추가할까?"

    // ───────────────────────── 21차: 콤보 ─────────────────────────
    const val COMBO_MOON = "5연속! 달빛 모드야!"
    const val COMBO_3 = "3연속! 대단해!"
    const val COMBO_7 = "7연속! 최고야!"

    // ───────────────────────── 18차: 온도 낮추기 · Gleo 확인 ─────────────────────────
    const val COOL_ASK = "조금 졸려 보여. 실내 온도를 2도 낮춰 줄까? 시원하면 덜 졸려."
    const val COOL_DONE = "2도 낮췄어. 시원하게 가자."
    const val COOL_UNSUPPORTED = "지금은 내가 온도를 바꿀 수 없어. 에어컨을 조금 세게 하거나 창문을 살짝 열어 줘."
    const val COOL_DECLINED = "알겠어. 덥거나 졸리면 말해 줘."

    /** 도장 안내 뒤에 붙임: 좋아하는 음식이 이 휴게소 대표 메뉴와 같을 때 */
    fun stampFavorite(favorite: String, menu: String) = " ${favorite} 좋아하잖아! 여기 대표 메뉴가 ${menu}래."
}
