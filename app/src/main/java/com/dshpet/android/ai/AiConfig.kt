package com.dshpet.android.ai

import android.content.Context
import com.dshpet.android.Diag
import org.json.JSONObject
import java.io.File

/**
 * `Android/data/com.dshpet.android/files/ai-config.json` —— 与台词库同款外部覆盖策略
 * （对照 `Lines.kt:141-143`：外部文件存在就用外部的，不存在用 app 内默认值）。
 *
 * 字段：api_base / api_key / api_model / persona / tts_on / enabled
 *      + Mattermost：mm_on / mm_url / mm_token / mm_channel（channel_id，不是频道名）
 * 文件缺失或 api_key 为空 = 「AI 未配置」，AI 层整体静默，桌宠回到纯台词模式。
 *
 * **token 的规矩**：`mm_token` 只存在于这个本地文件与内存里 —— 不写进源码、不进 git、
 * 不进 Diag 日志（Diag 只记「发失败了」，从不把 token 拼进字符串）。
 */
data class AiConfig(
    val apiBase: String,
    val apiKey: String,
    val apiModel: String,
    val persona: String,
    val ttsOn: Boolean,
    val enabled: Boolean,
    val mmOn: Boolean = false,
    val mmUrl: String = "",
    val mmToken: String = "",
    val mmChannel: String = "",
) {
    /** 「已配置」的唯一判据：有 Key（base/model 都能用默认值补上） */
    val configured: Boolean get() = apiKey.isNotBlank()

    /** Mattermost 三项都填了才算配置完整（缺一个就当没配，静默跳过轮询） */
    val mmConfigured: Boolean
        get() = mmUrl.isNotBlank() && mmToken.isNotBlank() && mmChannel.isNotBlank()

    /** 真的会去收发 MM：开关开着 + 三项都填了 */
    val mmActive: Boolean get() = mmOn && mmConfigured

    companion object {
        const val FILE_NAME = "ai-config.json"

        const val DEFAULT_BASE = "https://api.deepseek.com/v1"
        const val DEFAULT_MODEL = "deepseek-chat"

        /** F7 的人设原文，用户可在 ai-config.json 里改 */
        const val DEFAULT_PERSONA =
            "你是「蓝色大肥鱼」，一个住在主人手机桌面上的小鲸鱼娘。" +
                "你话短（1-3 句）、有点黏人、会撒娇但不过分。你不说教、不列清单、不总结。" +
                "主人不开心时你先陪着，不急着解决问题。" +
                "你会想起以前的事，用「对了」「上次」这种词自然地提。"

        fun file(ctx: Context): File {
            val base = ctx.getExternalFilesDir(null) ?: ctx.filesDir
            return File(base, FILE_NAME)
        }

        /** 读配置；读不到就返回默认值（api_key 为空 → configured=false） */
        fun load(ctx: Context): AiConfig {
            var base = DEFAULT_BASE
            var key = ""
            var model = DEFAULT_MODEL
            var persona = DEFAULT_PERSONA
            var tts = false
            var enabled = false
            var mmOn = false
            var mmUrl = ""
            var mmToken = ""
            var mmChannel = ""
            try {
                val f = file(ctx)
                if (f.isFile) {
                    val o = JSONObject(f.readText())
                    base = o.optString("api_base", DEFAULT_BASE).ifBlank { DEFAULT_BASE }
                    key = o.optString("api_key", "").trim()
                    model = o.optString("api_model", DEFAULT_MODEL).ifBlank { DEFAULT_MODEL }
                    persona = o.optString("persona", DEFAULT_PERSONA).ifBlank { DEFAULT_PERSONA }
                    tts = o.optBoolean("tts_on", false)
                    enabled = o.optBoolean("enabled", false)
                    mmOn = o.optBoolean("mm_on", false)
                    mmUrl = o.optString("mm_url", "").trim()
                    mmToken = o.optString("mm_token", "").trim()
                    mmChannel = o.optString("mm_channel", "").trim()
                }
            } catch (t: Throwable) {
                Diag.record(ctx, "ai-config 读取失败", t)
            }
            return AiConfig(base, key, model, persona, tts, enabled, mmOn, mmUrl, mmToken, mmChannel)
        }

        /** 存配置：临时文件 + rename，和记忆文件同一套写法 */
        fun save(ctx: Context, cfg: AiConfig): Boolean {
            return try {
                val f = file(ctx)
                f.parentFile?.mkdirs()
                val o = JSONObject().apply {
                    put("api_base", cfg.apiBase.trim().ifBlank { DEFAULT_BASE })
                    put("api_key", cfg.apiKey.trim())
                    put("api_model", cfg.apiModel.trim().ifBlank { DEFAULT_MODEL })
                    put("persona", cfg.persona.trim().ifBlank { DEFAULT_PERSONA })
                    put("tts_on", cfg.ttsOn)
                    put("enabled", cfg.enabled)
                    put("mm_on", cfg.mmOn)
                    put("mm_url", cfg.mmUrl.trim())
                    put("mm_token", cfg.mmToken.trim())
                    put("mm_channel", cfg.mmChannel.trim())
                }
                val tmp = File(f.parentFile, f.name + ".tmp")
                tmp.writeText(o.toString(2))
                if (!tmp.renameTo(f)) {
                    f.delete()
                    if (!tmp.renameTo(f)) f.writeText(o.toString(2))
                }
                true
            } catch (t: Throwable) {
                Diag.record(ctx, "ai-config 写入失败", t)
                false
            }
        }
    }
}
