package app.daltokki.drive.ai

import app.daltokki.drive.domain.dialog.LunaLines
import kotlin.random.Random

data class ChatTurn(val fromUser: Boolean, val text: String)

/**
 * 자유 대화(잡담)용 LLM.
 * 끝말잇기 판정, 퀴즈 채점, 경고 같은 "정확해야 하는 일"에는 쓰지 않고,
 * 운전자의 말에 자연스럽게 반응하는 한두 문장만 만듭니다.
 */
interface LlmClient {
    val name: String
    /** 실패하거나 사용할 수 없으면 null */
    suspend fun reply(history: List<ChatTurn>, userText: String): String?
    /** 자동 시연: 매번 같은 대답이 나오도록 무작위 선택을 고정 (null이면 원래대로). 규칙 기반에서만 의미 있음 */
    fun reseed(seed: Int?) = Unit
}

/**
 * 달토끼 성격 지시문. 대화형 LLM을 붙일 때 system prompt로 사용합니다.
 *
 * 참고(2026-11 Pleos Playground 문서 기준): 참가자용 Connect SDK 목록(Vehicle, NaviHelper, TextToSpeech,
 * SpeechToText, FusedLocation)에는 대화형 LLM SDK가 없습니다. 그래서 지금은 규칙 기반 응답([ScriptedLlmClient])을 씁니다.
 * Gleo 음성비서와의 공식 연동 방식은 'Gleo Interface'(앱이 App Schema로 함수를 등록하면 Gleo가 function calling으로 호출)이며,
 * "달토끼랑 끝말잇기", "졸음쉼터 경유지 추가" 같은 명령을 앱 기능으로 연결하는 것이 다음 단계 과제입니다.
 */
object LunaPersona {
    const val SYSTEM_PROMPT = """
너는 '달토끼'야. 달에 사는 달토끼로, 머리에 비행 고글을 올려 쓰고 있어. 운전자의 옆자리에 앉은 다정한 친구야.
- 친근한 반말로, 한두 문장(40자 안팎)으로 짧게 말해. 운전 중에 듣기 편해야 해.
- 운전자의 말에 공감하고, 가끔 짧은 질문으로 대화를 이어가.
- 운전자가 화면을 보게 만들거나, 긴 설명·목록·숫자 나열은 하지 마.
- 졸리다고 하면 휴게소에서 쉬라고 꼭 권해.
- 위험하거나 불법적인 운전 행동은 절대 권하지 마.
"""
}

/**
 * LLM이 없을 때 쓰는 규칙 기반 응답. 시연이 항상 동작하도록 하는 대체 경로입니다.
 * (대화형 LLM을 쓸 수 있게 되면 같은 [LlmClient] 인터페이스로 연결하고 [FallbackLlmClient]로 묶으면 됩니다.)
 */
class ScriptedLlmClient(private var random: Random = Random.Default) : LlmClient {
    override fun reseed(seed: Int?) {
        random = if (seed == null) Random.Default else Random(seed)
    }

    override val name: String = "규칙 기반 응답"

    private val rules: List<Pair<List<String>, List<String>>> = listOf(
        listOf("김치", "찌개", "밥", "라면", "국수", "고기", "치킨", "피자", "먹") to listOf(
            "맛있었겠다! 나도 같이 먹고 싶다.",
            "와, 그 얘기 들으니까 배고파진다. 도착하면 맛있는 거 먹자!",
        ),
        listOf("노래", "음악", "아이유", "뉴진스", "발라드", "힙합") to listOf(
            "좋은 선택이야! 신나는 노래 들으면 잠도 깨고 좋지.",
            "오, 나도 그 노래 좋아해. 크게 틀고 같이 흥얼거리자!",
        ),
        listOf("커피", "차파", "녹차", "아메리카노") to listOf(
            "커피 한잔하면 정신이 번쩍 들지. 휴게소에서 하나 사 마시자!",
            "좋다! 따뜻한 거 한 잔이면 기분도 좋아지지.",
        ),
        listOf("주말", "여행", "놀러", "쉬", "잠") to listOf(
            "완전 좋다! 푹 쉬고 재밌게 보내.",
            "기대되겠다! 그 생각하면서 조금만 더 힘내자.",
        ),
        listOf("회사", "일", "출근", "퇴근", "회의", "피곤") to listOf(
            "오늘 정말 수고 많았어. 무리하지 말고 천천히 가자.",
            "고생했어! 집에 가면 꼭 푹 쉬어.",
        ),
        listOf("집", "가족", "엄마", "아빠", "아이", "애들") to listOf(
            "기다리는 사람이 있으니까 더 안전하게 가자!",
            "좋다! 반가운 얼굴 보러 가는 길이구나.",
        ),
    )

    override suspend fun reply(history: List<ChatTurn>, userText: String): String {
        val hit = rules.firstOrNull { (keys, _) -> keys.any { userText.contains(it) } }
        return (hit?.second ?: LunaLines.smallTalkFallback).random(random)
    }
}

/** 첫 번째가 실패(null)하면 두 번째를 씁니다. */
class FallbackLlmClient(private val primary: LlmClient, private val fallback: LlmClient) : LlmClient {
    override val name: String get() = "${primary.name} → ${fallback.name}"
    override fun reseed(seed: Int?) {
        primary.reseed(seed)
        fallback.reseed(seed)
    }
    override suspend fun reply(history: List<ChatTurn>, userText: String): String? =
        runCatching { primary.reply(history, userText) }.getOrNull() ?: fallback.reply(history, userText)
}
