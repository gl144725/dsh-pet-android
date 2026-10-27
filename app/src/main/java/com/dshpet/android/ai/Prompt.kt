package com.dshpet.android.ai

/**
 * 所有能改「她怎么说话」的文字都收在这里，只有一处，方便对照和改写。
 *
 *  - [system]：每次对话拼的 system prompt（人设 + 情绪 + 记得的事 + 最近聊过）
 *  - [EXTRACT_PROMPT]：F3 提炼记忆的 prompt（**票面要求逐字一致，不要改动一个字**）
 *  - [proactiveUser]：F8 主动开口时给模型的一行指令
 */
object Prompt {

    /** 用户戳她一下时，给模型看的「用户输入」 */
    const val TAP_INPUT = "（用户戳了戳你）"

    /** F8：开口前先来一个「想起」的动作（文字形态，本期不绑动画） */
    val PROACTIVE_PREFIXES = listOf(
        "（歪头）", "（想了想）", "（凑近一点）", "（甩了甩尾巴）", "（眨眨眼）",
    )

    /**
     * F3 提炼 prompt —— 与票面逐字一致。
     * 事件原文作为 user 消息单独发出，不拼进这段。
     */
    const val EXTRACT_PROMPT = """从下面这些对话里，提炼出关于「主人」的长期印象。
要求：
- 0-3 条，每条不超过 30 字
- 只写能长期成立的事实或偏好，不写一次性的动作（「他刚才点了外卖」不算）
- 不写你自己说过的话
- 没有值得记的就返回空数组
只输出 JSON 数组，不要任何解释。例：["主人经常熬夜到凌晨两三点","主人讨厌反复返工"]"""

    /** 兜底：模型没按格式输出时不会用到这段，这里只是留个说明位 */
    const val TAP_RULE = "只回 1-3 句，像微信语音那样短。不要列表、不要小标题、不要总结、不要说教。"

    /**
     * 一次对话的 system prompt。
     *
     * @param persona  人设（来自 ai-config.json 的 persona，缺省用 AiConfig.DEFAULT_PERSONA）
     * @param moodLine F6 的固定格式情绪行
     * @param memories 「你记得的事」—— F4 检索出来的印象原文
     * @param recent   「最近聊过」—— 最近几轮对话原文（不打分、不过滤）
     */
    fun system(
        persona: String,
        moodLine: String,
        memories: List<String>,
        recent: List<String>,
    ): String {
        val sb = StringBuilder()
        sb.append(persona.trim()).append("\n\n")
        sb.append(moodLine).append('\n')
        if (memories.isNotEmpty()) {
            sb.append("\n你记得的事：\n")
            for (m in memories) sb.append("- ").append(m).append('\n')
        }
        if (recent.isNotEmpty()) {
            sb.append("\n最近聊过：\n")
            for (r in recent) sb.append("- ").append(r).append('\n')
        }
        sb.append('\n').append(TAP_RULE)
        return sb.toString()
    }

    /** F8：把一条印象变成一句主动开口的话 */
    fun proactiveUser(impression: String): String =
        "你突然想起了一件事：「$impression」。用 1-2 句自然地跟主人提起来，开头用一个括号动作（比如（歪头）），" +
            "用「对了」「上次」这种词，不要解释为什么想起，不要道歉。"

    /** 模型挂了 / 没配置时的兜底开口（纯模板，不走网络） */
    fun proactiveFallback(prefix: String, impression: String): String =
        prefix + "对了，你上次说过「" + impression + "」，后来怎么样了？"
}
