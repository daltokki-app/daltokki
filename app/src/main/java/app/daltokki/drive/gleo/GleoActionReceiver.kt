package app.daltokki.drive.gleo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import app.daltokki.drive.engine.CopilotEngine
import app.daltokki.drive.appContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * Gleo Interface 연동: Gleo 음성비서가 assets/schema.json에 등록한 함수를 부르면 여기로 옵니다.
 *
 * - component="service" → Gleo가 sendBroadcast로 액션 이름(START_WORD_GAME 등)을 우리 패키지에 보냄
 * - 결과는 7초 안에 ai.pleos.playground.intent.action.APP_ACTION_RESPONSE 브로드캐스트로 돌려줌
 *   (extras: packageName, response=JSON {functionName, status, message, mode, data})
 * - 달토끼가 직접 말하므로 mode는 "silent"(Gleo는 따로 말하지 않음)
 *
 * Gleo 없이 시험: adb shell am broadcast -a START_WORD_GAME -p app.daltokki.drive
 */
class GleoActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val engine = context.appContainer.engine
        Log.i(TAG, "Gleo action: $action")
        // 같은 요청이 짧은 시간에 반복되면 무시 (다른 앱이 신호를 연달아 보내는 경우 대비)
        val now = SystemClock.elapsedRealtime()
        val last = lastAt[action]
        if (last != null && now - last < REPEAT_GUARD_MS) {
            respond(context, action, "fail", "Ignored a repeated request.")
            return
        }
        lastAt[action] = now
        when (action) {
            ACTION_START_WORD_GAME -> {
                val started = engine.assistantStartWordGame()
                respond(
                    context, action, if (started) "success" else "fail",
                    if (started) "Started a word chain game with Daltokki." else "Daltokki suggested a rest break instead because drowsiness risk is high.",
                )
            }
            ACTION_REPORT_DROWSINESS -> {
                engine.assistantReportSleepy()
                respond(context, action, "success", "Daltokki raised the drowsiness level and is guiding the driver to a rest area.")
            }
            ACTION_ADD_REST_STOP -> {
                val pending = goAsync()
                scope.launch {
                    try {
                        val result = withTimeoutOrNull(RESPONSE_LIMIT_MS) { engine.assistantAddRestStop() }
                        val area = result?.first
                        val outcome = result?.second
                        when {
                            area == null -> respond(context, action, "fail", "No rest area was found ahead.")
                            outcome == CopilotEngine.AssistantRest.ASKED -> respond(
                                context, action, "success", "Daltokki is asking the driver to confirm adding ${area.name} as a waypoint.",
                                data = JSONArray().put(JSONObject().put("restArea", area.name).put("distanceKm", area.distanceKm)),
                            )
                            outcome == CopilotEngine.AssistantRest.ADDED -> respond(
                                context, action, "success", "Added ${area.name} as a waypoint.",
                                data = JSONArray().put(JSONObject().put("restArea", area.name).put("distanceKm", area.distanceKm)),
                            )
                            else -> respond(
                                context, action, "fail", "Found ${area.name} but could not add it as a waypoint.",
                                data = JSONArray().put(JSONObject().put("restArea", area.name).put("distanceKm", area.distanceKm)),
                            )
                        }
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
    }

    private fun respond(context: Context, functionName: String, status: String, message: String, data: JSONArray? = null) {
        val json = JSONObject()
            .put("functionName", functionName)
            .put("status", status)
            .put("message", message)
            .put("mode", "silent")
        if (data != null) json.put("data", data.toString())
        context.sendBroadcast(
            Intent(ACTION_RESPONSE)
                .putExtra("packageName", context.packageName)
                .putExtra("response", json.toString()),
        )
    }

    companion object {
        private const val TAG = "Gleo"
        const val ACTION_START_WORD_GAME = "START_WORD_GAME"
        const val ACTION_REPORT_DROWSINESS = "REPORT_DROWSINESS"
        const val ACTION_ADD_REST_STOP = "ADD_REST_STOP"
        const val ACTION_RESPONSE = "ai.pleos.playground.intent.action.APP_ACTION_RESPONSE"
        /** Gleo는 7초 안에 응답이 없으면 취소하므로 6초 안에 답함 */
        private const val RESPONSE_LIMIT_MS = 6_000L
        /** 같은 요청을 다시 받기까지 최소 간격 */
        private const val REPEAT_GUARD_MS = 10_000L
        private val lastAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

        /** 데모 패널에서 Gleo가 부른 것처럼 시험할 때 */
        fun simulate(context: Context, action: String) {
            context.sendBroadcast(Intent(action).setPackage(context.packageName))
        }
    }
}
