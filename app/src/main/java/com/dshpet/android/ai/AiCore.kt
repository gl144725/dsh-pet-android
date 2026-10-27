package com.dshpet.android.ai

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.dshpet.android.Diag
import com.dshpet.android.Prefs
import java.util.Calendar
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * AI 层的总调度：戳一下说话（F7）+ 自己挑时间开口（F8）。
 *
 * 三条铁律，写在这里是因为最容易被后来的人改坏：
 *  1. **不许碰渲染**：所有输出只走 [Host.sayText]，气泡是唯一出口（`PetService.say()` 保持 private）。
 *  2. **不许在主线程做网络**：网络/文件都在 [io] 单线程里跑，回主线程只做「说一句话」。
 *  3. **不许因为 AI 挂了影响桌宠**：所有异常就地吞掉记 Diag，任何一步失败都退化成原台词。
 *
 * 「AI 未配置」（没 api_key）时整个类形同不存在，桌宠回到纯台词模式。
 */
class AiCore(private val ctx: Context, private val host: Host) {

    /** 宿主（PetService）唯一需要提供的能力：说一句 AI 文本 / 用现有分类说一句兜底台词 */
    interface Host {
        fun sayText(text: String)
        fun sayCategory(id: String)
    }

    private val main = Handler(Looper.getMainLooper())
    private val io: ExecutorService = Executors.newSingleThreadExecutor()

    private val memory = MemoryCore(ctx)

    @Volatile private var started = false
    @Volatile private var busy = false
    @Volatile private var lastTapMs = 0L

    /** F8 的下一次主动开口时间（毫秒）。只在内存里，Service 重启会重排 —— 可接受 */
    private var nextProactiveAt = 0L

    // ------------------------------------------------------------ 生命周期

    fun start() {
        if (started) return
        started = true
        nextProactiveAt = nextWindowTime(System.currentTimeMillis())
        Diag.record(
            ctx,
            "AiCore 启动：开关=" + Prefs.aiOn(ctx) + "，下次主动开口≈" + nextProactiveAt,
        )
    }

    fun stop() {
        started = false
        try {
            memory.relation.save()
        } catch (_: Throwable) {
        }
        try {
            io.shutdown()
        } catch (_: Throwable) {
        }
    }

    // ------------------------------------------------------------ F7 戳一下

    /**
     * 用户戳了戳她。
     * @return true = 这次由 AI 接管（会说话）；false = 走原有台词逻辑
     */
    fun onTap(): Boolean {
        if (!started || busy) return false
        if (!Prefs.aiOn(ctx)) return false
        val cfg = AiConfig.load(ctx)
        if (!cfg.configured) return false
        val now = System.currentTimeMillis()
        if (now - lastTapMs < TAP_DEBOUNCE_MS) return false
        lastTapMs = now

        busy = true
        val nowS = now / 1000L
        try {
            memory.relation.onTalk(nowS)
        } catch (t: Throwable) {
            Diag.record(ctx, "onTalk 失败", t)
        }
        val input = Prompt.TAP_INPUT
        io.execute {
            try {
                converse(cfg, input, nowS)
            } catch (t: Throwable) {
                Diag.record(ctx, "对话失败", t)
                main.post { safeSayCategory("busy") }
            } finally {
                busy = false
            }
        }
        return true
    }

    /** 一次完整的「想 → 说 → 记」 */
    private fun converse(cfg: AiConfig, input: String, nowS: Long) {
        val card = memory.recall(input, 4, nowS)
        val system = Prompt.system(
            persona = cfg.persona,
            moodLine = memory.relation.moodLine(nowS),
            memories = card.impressionTexts,
            recent = card.recentLines(),
        )
        val client = AiClient(cfg)
        val reply = client.chat(system, input, 220, 0.9)
        if (reply.isNullOrBlank()) {
            Diag.record(ctx, "AI 回复失败，退回原台词：" + (client.lastError ?: "未知"))
            main.post { safeSayCategory("busy") }
            return
        }
        val text = clean(reply)
        if (text.isEmpty()) {
            main.post { safeSayCategory("busy") }
            return
        }
        memory.record("user", input, nowS)
        memory.record("pet", text, nowS)
        main.post { safeSayText(text) }

        // 顺手做两件慢活：回头看一眼（F3）、淡忘（F5）
        try {
            memory.maybeConsolidate(client, nowS)
            memory.maybeForget(nowS)
        } catch (t: Throwable) {
            Diag.record(ctx, "提炼/淡忘失败", t)
        }
    }

    // ------------------------------------------------------------ F8 主动开口

    /**
     * 由 PetService 现有帧循环的 1 秒节流块调用（**不新建线程、不新建 Handler**）。
     * 三道守卫：时段 8:00-23:00、距上次对话 > 4 小时、有 imp>=3 且 7 天没提过的印象。
     */
    fun onTick(nowMs: Long) {
        if (!started) return
        if (nextProactiveAt <= 0L) {
            nextProactiveAt = nextWindowTime(nowMs)
            return
        }
        if (nowMs < nextProactiveAt) return

        val cfg = AiConfig.load(ctx)
        if (busy || !Prefs.aiOn(ctx) || !cfg.configured) {
            nextProactiveAt = nextWindowTime(nowMs)
            return
        }

        val cal = Calendar.getInstance()
        cal.timeInMillis = nowMs
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        if (hour < QUIET_START_HOUR || hour >= QUIET_END_HOUR) {
            nextProactiveAt = nextWindowTime(nowMs)
            return
        }

        val nowS = nowMs / 1000L
        val lastTalk = memory.relation.lastTalk
        if (lastTalk > 0L && (nowS - lastTalk) < MIN_SILENCE_SECONDS) {
            // 刚聊过，不打扰；一小时后再说
            nextProactiveAt = nowMs + 3600_000L
            return
        }

        val imp = memory.imps.pickProactive(nowS)
        if (imp == null) {
            // 没有值得提的事，顺延 2 小时（不硬凑）
            nextProactiveAt = nowMs + 2L * 3600_000L
            return
        }
        memory.imps.markProactive(imp, nowS)
        nextProactiveAt = nextWindowTime(nowMs + 3600_000L)

        val prefix = Prompt.PROACTIVE_PREFIXES[(Math.random() * Prompt.PROACTIVE_PREFIXES.size).toInt()
            .coerceIn(0, Prompt.PROACTIVE_PREFIXES.size - 1)]
        val fallback = Prompt.proactiveFallback(prefix, imp.text)
        val persona = cfg.persona
        busy = true
        io.execute {
            try {
                val system = Prompt.system(
                    persona = persona,
                    moodLine = memory.relation.moodLine(nowS),
                    memories = emptyList(),
                    recent = emptyList(),
                )
                val client = AiClient(cfg)
                val raw = client.chat(system, Prompt.proactiveUser(imp.text), 160, 1.0)
                val text = if (raw.isNullOrBlank()) fallback else clean(raw)
                val say = if (text.isEmpty()) fallback else text
                memory.record("pet", say, nowS)
                main.post { safeSayText(say) }
                Diag.record(ctx, "主动开口：" + imp.text)
            } catch (t: Throwable) {
                Diag.record(ctx, "主动开口失败，用模板兜底", t)
                main.post { safeSayText(fallback) }
            } finally {
                busy = false
            }
        }
    }

    /**
     * 排下一次主动开口的时间：每天 1-2 次，随机落在 8:00-23:00 里。
     * 做法很朴素：今天窗口没关就在剩余时间里随机挑一点，否则排到明天早上。
     */
    private fun nextWindowTime(nowMs: Long): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = nowMs
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val minute = cal.get(Calendar.MINUTE)
        if (hour < QUIET_START_HOUR) {
            cal.set(Calendar.HOUR_OF_DAY, QUIET_START_HOUR)
            cal.set(Calendar.MINUTE, rnd(0, 50))
            return cal.timeInMillis
        }
        val remainMin = QUIET_END_HOUR * 60 - (hour * 60 + minute)
        if (remainMin > 50) {
            cal.add(Calendar.MINUTE, 40 + rnd(0, remainMin - 40))
            return cal.timeInMillis
        }
        cal.add(Calendar.DAY_OF_YEAR, 1)
        cal.set(Calendar.HOUR_OF_DAY, QUIET_START_HOUR + rnd(0, 3))
        cal.set(Calendar.MINUTE, rnd(0, 55))
        return cal.timeInMillis
    }

    // ------------------------------------------------------------ 外部消息（下一票接 Mattermost）

    /**
     * 记忆层对外暴露的唯一入口：外面来一句话，先进记忆，再决定要不要回。
     * 本期只落库 + 更新关系，**不主动回复**（Mattermost 收发是下一票的事）。
     */
    fun onExternalMessage(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        val nowS = System.currentTimeMillis() / 1000L
        try {
            memory.record("user", clean, nowS)
            memory.relation.reactToUserText(clean, nowS)
            memory.relation.onTalk(nowS)
            Diag.record(ctx, "外部消息已进记忆层：" + clean.take(30))
        } catch (t: Throwable) {
            Diag.record(ctx, "外部消息写记忆失败", t)
        }
    }

    // ------------------------------------------------------------ 小工具

    private fun safeSayText(text: String) {
        try {
            host.sayText(text)
        } catch (t: Throwable) {
            Diag.record(ctx, "sayText 失败", t)
        }
    }

    private fun safeSayCategory(id: String) {
        try {
            host.sayCategory(id)
        } catch (t: Throwable) {
            Diag.record(ctx, "sayCategory 失败", t)
        }
    }

    /** 清掉模型爱带的东西：代码块围栏、包裹引号、换行 */
    fun clean(raw: String): String {
        var s = raw.trim()
        if (s.startsWith("```")) {
            s = s.removePrefix("```markdown").removePrefix("```").trim()
            val end = s.lastIndexOf("```")
            if (end > 0) s = s.substring(0, end).trim()
        }
        while (s.length >= 2 && (s.startsWith("\"") && s.endsWith("\""))) s = s.substring(1, s.length - 1).trim()
        while (s.length >= 2 && (s.startsWith("「") && s.endsWith("」"))) s = s.substring(1, s.length - 1).trim()
        s = s.replace("\r", "").replace("\n\n", "\n").replace('\n', ' ').trim()
        if (s.length > MAX_SAY_CHARS) s = s.substring(0, MAX_SAY_CHARS) + "…"
        return s
    }

    private fun rnd(from: Int, until: Int): Int {
        if (until <= from) return from
        return from + (Math.random() * (until - from)).toInt()
    }

    companion object {
        const val TAP_DEBOUNCE_MS = 1500L
        const val MIN_SILENCE_SECONDS = 4L * 3600L
        const val QUIET_START_HOUR = 8
        const val QUIET_END_HOUR = 23
        const val MAX_SAY_CHARS = 160
    }
}
