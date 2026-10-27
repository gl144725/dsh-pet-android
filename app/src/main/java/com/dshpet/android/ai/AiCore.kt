package com.dshpet.android.ai

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.dshpet.android.Diag
import com.dshpet.android.Prefs
import org.json.JSONObject
import java.io.File
import java.util.Calendar
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * AI 层的总调度：戳一下说话（F7）+ 自己挑时间开口（F8）+ Mattermost 双向收发（F1-F5）。
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

    /** 上次轮询 Mattermost 的时刻（毫秒）。30 秒一次靠这个变量节流 —— **没有新线程** */
    private var lastPollAt = 0L

    /** 正在轮询中（网络慢时别叠着发下一轮） */
    @Volatile private var polling = false

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
        // 手机戳和 MM 来消息共用这一个入口（talk），不各写一份对话流程
        return talk(cfg, Prompt.TAP_INPUT, now, external = false)
    }

    /**
     * 两个入口（[onTap] 手机戳 / [handleExternal] MM 来消息）**共用的唯一对话入口**：
     * 抢 `busy` 锁 → 记关系 → 跑 [converse]。**只有这一个方法，没有第二份对话流程。**
     *
     * 共用一个 `busy` 锁是关键：两边同时说话会互相打断，气泡也会打架。
     *
     * @param inline true = 调用方已经在 io 线程上（MM 轮询逐条处理），就地跑完再取下一条；
     *               false = 投递到 io 队列（手机戳走这条）。
     * @return false = 正忙，这次不接
     */
    private fun talk(
        cfg: AiConfig,
        input: String,
        nowMs: Long,
        external: Boolean,
        inline: Boolean = false,
    ): Boolean {
        if (busy) return false
        busy = true
        try {
            memory.relation.onTalk(nowMs / 1000L)
        } catch (t: Throwable) {
            Diag.record(ctx, "onTalk 失败", t)
        }
        // 显式标 () -> Unit：不标的话最后一句的值是 Boolean，类型会推成 () -> Boolean
        val body: () -> Unit = {
            try {
                converse(cfg, input, nowMs / 1000L, external)
            } catch (t: Throwable) {
                Diag.record(ctx, "对话失败", t)
                main.post { safeSayCategory("busy") }
            } finally {
                busy = false
            }
        }
        if (inline) {
            body()
        } else if (!onIo(body)) {
            // 线程池已经关了（Service 正在停）：把锁还回去，否则她再也不会说话
            busy = false
            return false
        }
        return true
    }

    /**
     * 一次完整的「想 → 说 → 记」。
     * @param external true = 这话是 MM 上主人说的，回复除了气泡还要发回 MM
     */
    private fun converse(cfg: AiConfig, input: String, nowS: Long, external: Boolean) {
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
        // 外部消息在 handleExternal 里已经落过一条 user 事件，这里别记第二遍
        if (!external) memory.record("user", input, nowS)
        memory.record("pet", text, nowS)
        deliver(cfg, text, external)

        // 顺手做两件慢活：回头看一眼（F3）、淡忘（F5）
        try {
            memory.maybeConsolidate(client, nowS)
            memory.maybeForget(nowS)
        } catch (t: Throwable) {
            Diag.record(ctx, "提炼/淡忘失败", t)
        }
    }

    /**
     * 一句话说出去：
     *  ① 永远先 `host.sayText()` —— 气泡是全仓库唯一的显示出口；
     *  ② 若来自 MM，再发一份回 MM（F4-3）。
     *
     * 发送失败**不重试**：下一轮主人再发新消息，对话自然会重新走一遍；
     * 这里只 `Diag.record` 记一笔，方便排查「为什么 MM 上没回」。
     */
    private fun deliver(cfg: AiConfig, text: String, external: Boolean) {
        main.post { safeSayText(text) }
        if (!external) return
        if (!cfg.mmActive) return
        if (MattermostBridge.send(cfg.mmUrl, cfg.mmToken, cfg.mmChannel, text)) return
        Diag.record(ctx, "MM 回复发送失败（不重试）：" + text.take(20))
    }

    /** F5：主动开口也同步发一份到 MM —— 主人不在手机旁时也能看到她想说什么 */
    private fun syncToMm(cfg: AiConfig, text: String) {
        if (!cfg.mmActive) return
        if (MattermostBridge.send(cfg.mmUrl, cfg.mmToken, cfg.mmChannel, text)) return
        Diag.record(ctx, "MM 同步主动开口失败（不重试）：" + text.take(20))
    }

    // ------------------------------------------------------------ F8 主动开口

    /**
     * 由 PetService 现有帧循环的 1 秒节流块调用（**不新建线程、不新建 Handler**）。
     * 三道守卫：时段 8:00-23:00、距上次对话 > 4 小时、有 imp>=3 且 7 天没提过的印象。
     */
    fun onTick(nowMs: Long) {
        if (!started) return
        // F3：每 30 秒看一眼 MM 有没有新消息。这里只是「到点判断」，网络动作在 io 线程里，
        // 全仓库没有为轮询新建第二个线程 / Handler / Service。
        try {
            pollMm(nowMs)
        } catch (t: Throwable) {
            Diag.record(ctx, "MM 轮询异常", t)
        }
        if (nextProactiveAt <= 0L) {
            nextProactiveAt = nextWindowTime(nowMs)
            return
        }
        if (nowMs < nextProactiveAt) return

        val cfg = AiConfig.load(ctx)
        // polling = 正在收 MM 消息，别插话把 busy 抢走（否则那条消息这轮就被跳过了）
        if (busy || polling || !Prefs.aiOn(ctx) || !cfg.configured) {
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
                syncToMm(cfg, say)
                Diag.record(ctx, "主动开口：" + imp.text)
            } catch (t: Throwable) {
                Diag.record(ctx, "主动开口失败，用模板兜底", t)
                main.post { safeSayText(fallback) }
                syncToMm(cfg, fallback)
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

    // ------------------------------------------------------------ F3 Mattermost 轮询

    /**
     * F3：轮询 Mattermost。**没有新线程**，全靠 [onTick]（PetService 的 1 秒节流块）驱动，
     * 内部用 [lastPollAt] 把频率压到 30 秒一次。
     *
     * 三道跳过：没开 / 没配置完整、正忙（跟主人对话中）、上一轮还没回来。
     */
    private fun pollMm(nowMs: Long) {
        if (nowMs - lastPollAt < MM_POLL_MS) return
        lastPollAt = nowMs
        if (polling || busy) return
        if (!Prefs.aiOn(ctx)) return
        polling = true
        onIo {
            try {
                // 配置是磁盘上的 json，读取也放 io 线程（主线程只做「到点了没」这一个判断）
                val cfg = AiConfig.load(ctx)
                if (cfg.mmActive) pollMmOnce(cfg)
            } catch (t: Throwable) {
                Diag.record(ctx, "MM 轮询失败", t)
            } finally {
                polling = false
            }
        }
    }

    /** 真正的一次收信（跑在 io 线程上） */
    private fun pollMmOnce(cfg: AiConfig) {
        var last = readMmLast(cfg)
        if (last <= 0L) {
            // 首次启动：只拿基线，**不补历史**（否则一开机就被半年前的聊天记录淹没）
            last = MattermostBridge.latestCreateAt(cfg.mmUrl, cfg.mmToken, cfg.mmChannel)
            if (last <= 0L) return
            writeMmLast(cfg, last)
            Diag.record(ctx, "MM 首次启动建立基线 create_at=" + last)
            return
        }
        val msgs = MattermostBridge.fetchNewMessages(cfg.mmUrl, cfg.mmToken, cfg.mmChannel, last)
        if (msgs.isEmpty()) return
        var maxAt = last
        for ((at, _id, body) in msgs) {
            // 逐条走 F4 那套流程；这里已经在 io 线程上，用 inline 就地跑完再取下一条
            if (!handleExternal(body, inline = true)) {
                // 正忙（主人正在手机上跟她聊）：**不推进游标**，这条下一轮重试，别把话吞掉
                Diag.record(ctx, "MM 消息这轮没处理，等下一轮：" + body.take(20))
                break
            }
            if (at > maxAt) maxAt = at
            // 处理一条推一条，崩了也不会重复回复已经回过的消息
            writeMmLast(cfg, maxAt)
            MattermostBridge.record("主人", body)
        }
    }

    /**
     * `filesDir/mem/mm-last.json` —— 轮询游标，重启不重复收。
     * 换频道会顺手作废旧游标（`channel` 对不上就返回 0 重建基线）。
     */
    private fun mmLastFile(): File = File(File(ctx.filesDir, "mem"), "mm-last.json")

    private fun readMmLast(cfg: AiConfig): Long {
        try {
            val f = mmLastFile()
            if (!f.isFile) return 0L
            val o = JSONObject(f.readText())
            val ch = o.optString("channel", "")
            // 换了频道：旧游标作废，重新建基线（否则会拿 A 频道的时间戳去查 B 频道）
            if (ch.isNotEmpty() && ch != cfg.mmChannel) return 0L
            return o.optLong("last", 0L)
        } catch (t: Throwable) {
            Diag.record(ctx, "mm-last 读取失败", t)
            return 0L
        }
    }

    private fun writeMmLast(cfg: AiConfig, last: Long) {
        try {
            val f = mmLastFile()
            f.parentFile?.mkdirs()
            val o = JSONObject().apply {
                put("last", last)
                put("channel", cfg.mmChannel)
                put("saved_at", System.currentTimeMillis())
            }
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(o.toString())
            if (!tmp.renameTo(f)) {
                f.delete()
                if (!tmp.renameTo(f)) f.writeText(o.toString())
            }
        } catch (t: Throwable) {
            Diag.record(ctx, "mm-last 写入失败", t)
        }
    }

    // ------------------------------------------------------------ F4 外部消息

    /**
     * 外面来一句话（Mattermost）：先进记忆，再走跟 [onTap] **完全相同**的那套对话
     * （[talk] → [converse]，不是复制一份），回复同时上气泡 + 发回 MM。
     *
     * 和手机戳的对话**共用一个 `busy` 锁**，避免两边同时说话。
     */
    fun onExternalMessage(text: String) {
        handleExternal(text, inline = false)
    }

    /**
     * @param inline true = 调用方已经站在 io 线程上（MM 轮询逐条处理），就地跑完再取下一条；
     *               false = 普通外部调用，投递到 io 队列。
     * @return false = 正忙没接（调用方据此决定要不要重试）
     */
    private fun handleExternal(text: String, inline: Boolean): Boolean {
        val clean = text.trim()
        if (clean.isEmpty()) return true
        if (!started) return true
        if (!Prefs.aiOn(ctx)) return true
        val cfg = AiConfig.load(ctx)
        if (!cfg.mmActive) return true
        val now = System.currentTimeMillis()
        val nowS = now / 1000L
        // F4-1：先落一条事件 —— 哪怕后面模型挂了，这句话也已经进记忆
        try {
            memory.record("user", clean, nowS)
            memory.relation.reactToUserText(clean, nowS)
        } catch (t: Throwable) {
            Diag.record(ctx, "外部消息写记忆失败", t)
        }
        if (!cfg.configured) {
            // 没配模型 Key：话已经进记忆了，只是这轮不回 —— 不算失败，游标照推
            Diag.record(ctx, "MM 来消息但没配模型 Key，只记不说：" + clean.take(20))
            return true
        }
        // F4-2：跟手机戳同一个入口 talk(...)，
        //       回复由 converse → deliver 负责 ①气泡 ②回发 MM
        return talk(cfg, clean, now, external = true, inline = inline)
    }

    // ------------------------------------------------------------ 小工具

    /**
     * 把任务投进 [io] 那条唯一的网络/磁盘线程（**全仓库只此一条**，绝不在主线程做网络）。
     * @return false = 线程池已关（Service 正在停），任务没进去
     */
    private fun onIo(task: () -> Unit): Boolean {
        return try {
            io.execute(task)
            true
        } catch (t: Throwable) {
            Diag.record(ctx, "io 线程已关闭", t)
            false
        }
    }

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
        /** F3：MM 轮询间隔（毫秒）。由 onTick 驱动，不是新线程的 sleep */
        const val MM_POLL_MS = 30_000L
        const val MIN_SILENCE_SECONDS = 4L * 3600L
        const val QUIET_START_HOUR = 8
        const val QUIET_END_HOUR = 23
        const val MAX_SAY_CHARS = 160
    }
}
