package com.dshpet.android.ai

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 唯一一个会说网络话的类。OpenAI 兼容协议（和上游 `Brain.kt` 同路子，但只用
 * `HttpURLConnection` + `org.json`，不引任何第三方库）。
 *
 * **永不抛异常**：任何失败都返回 null，并把原因放进 [lastError] 交给调用方 Diag。
 * 这样「AI 挂了」永远不会影响桌宠本身（物理、渲染、台词全都不经过这里）。
 */
class AiClient(private val cfg: AiConfig) {

    /** 上一次失败的原因（成功时为 null），调用方拿它记 Diag */
    var lastError: String? = null
        private set

    /**
     * @return 模型回复的纯文本；失败一律 null
     */
    fun chat(
        system: String,
        user: String,
        maxTokens: Int = 220,
        temperature: Double = 0.9,
    ): String? {
        lastError = null
        var conn: HttpURLConnection? = null
        try {
            val payload = JSONObject().apply {
                put("model", cfg.apiModel)
                put(
                    "messages",
                    JSONArray().apply {
                        put(JSONObject().apply { put("role", "system"); put("content", system) })
                        put(JSONObject().apply { put("role", "user"); put("content", user) })
                    },
                )
                put("temperature", temperature)
                put("max_tokens", maxTokens)
                put("stream", false)
            }.toString()

            val c = URL(endpoint(cfg.apiBase)).openConnection() as HttpURLConnection
            conn = c
            c.requestMethod = "POST"
            c.connectTimeout = CONNECT_TIMEOUT_MS
            c.readTimeout = READ_TIMEOUT_MS
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            c.setRequestProperty("Accept", "application/json")
            c.setRequestProperty("Authorization", "Bearer " + cfg.apiKey)

            c.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }

            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val raw = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                lastError = "HTTP " + code + " " + raw.take(160)
                return null
            }
            val text = parseContent(raw)
            if (text.isNullOrBlank()) {
                lastError = "空回复：" + raw.take(160)
                return null
            }
            return text
        } catch (t: Throwable) {
            lastError = t.javaClass.simpleName + ": " + (t.message ?: "")
            return null
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Throwable) {
            }
        }
    }

    companion object {
        const val CONNECT_TIMEOUT_MS = 8000
        const val READ_TIMEOUT_MS = 20000

        /**
         * 各种手填的 base 都能拼出对的地址：
         *  `api.deepseek.com`                       → https://api.deepseek.com/v1/chat/completions
         *  `https://api.deepseek.com/v1`            → …/v1/chat/completions
         *  `https://api.deepseek.com/v1/`           → 同上（去尾斜杠）
         *  `https://x/v1/chat/completions`          → 原样
         */
        fun endpoint(base: String): String {
            var s = base.trim()
            if (s.isEmpty()) s = AiConfig.DEFAULT_BASE
            if (!s.startsWith("http://") && !s.startsWith("https://")) s = "https://" + s
            while (s.endsWith("/")) s = s.substring(0, s.length - 1)
            if (s.contains("/chat/completions")) return s
            if (s.endsWith("/v1")) return s + "/chat/completions"
            return s + "/v1/chat/completions"
        }

        /** 从 OpenAI 兼容响应里挖出正文；三家字段名不一样，按顺序兜底 */
        fun parseContent(raw: String): String? {
            if (raw.isBlank()) return null
            try {
                val o = JSONObject(raw)
                val arr = o.optJSONArray("choices") ?: return null
                if (arr.length() == 0) return null
                val c0 = arr.optJSONObject(0) ?: return null
                val msg = c0.optJSONObject("message")
                val byMessage = msg?.optString("content", "") ?: ""
                if (byMessage.isNotBlank()) return byMessage
                val byText = c0.optString("text", "")
                if (byText.isNotBlank()) return byText
                val byContent = c0.optString("content", "")
                if (byContent.isNotBlank()) return byContent
                return null
            } catch (_: Throwable) {
                return null
            }
        }
    }
}
