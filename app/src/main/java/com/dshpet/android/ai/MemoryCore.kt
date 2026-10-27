package com.dshpet.android.ai

import android.content.Context
import com.dshpet.android.Diag
import org.json.JSONArray

/**
 * 记忆层的门面：把「事件 / 印象 / 关系」三个 Store 串起来，
 * 负责 F3 提炼、F4 检索、F5 淡忘的**时点**（什么时候跑），Store 负责**怎么算**。
 *
 * 一个反直觉的设计：记忆全是「懒」的 —— 她不主动记，只在
 *  ① 距上次提炼 > 24 小时，或 ② 又攒了 ≥ 20 条新事件
 * 时才回头看一眼。这既是省 token，也是「像人」：人不会记下每一句。
 */
class MemoryCore(private val ctx: Context) {

    val events = EventStore(ctx)
    val imps = ImpressionStore(ctx)
    val relation = RelationStore(ctx)

    /** 提炼/淡忘的进度存在 SharedPreferences 里，不占 mem/ 下那 4 个文件的名额 */
    private val meta = ctx.getSharedPreferences("ai_mem", Context.MODE_PRIVATE)

    /** F4 的返回：印象若干条 + 最近几轮对话原文 */
    data class Recall(
        val impressions: List<ImpressionStore.Impression>,
        val recent: List<EventStore.Event>,
    ) {
        val impressionTexts: List<String> get() = impressions.map { it.text }

        /** 拼进 prompt 的「最近聊过」几行 */
        fun recentLines(): List<String> = recent.map {
            (if (it.who == "pet") "她：" else "主人：") + it.text
        }
    }

    // ------------------------------------------------------------ 记录

    /**
     * 记一条事件（F2 打分在这里落地）。
     * who = "user" / "pet"；她自己的话恒定低分，不会污染「主人」的印象。
     */
    fun record(who: String, text: String, now: Long): EventStore.Event {
        val clean = text.trim()
        val (imp, emo) = EventStore.scoreEvent(clean, who)
        val e = EventStore.Event(
            id = "",
            t = now,
            who = who,
            text = clean,
            imp = imp,
            emo = emo,
            tags = if (who == "pet") emptyList() else EventStore.tagsOf(clean),
        )
        events.append(e)
        return e
    }

    // ------------------------------------------------------------ F4 检索

    /**
     * 「会串」：不是拿最近 5 条，而是拿**跟这句话最相关的** 4 条。
     * 被选中的印象会 mentions+1、last=now（这也喂给 F8 的 7 天冷却）。
     */
    fun recall(input: String, limit: Int = 4, now: Long = sec()): Recall {
        val picked = imps.recall(input, limit, now)
        val all = events.all()
        val recent = if (all.size <= RECENT_TURNS * 2) all else all.subList(all.size - RECENT_TURNS * 2, all.size)
        return Recall(picked, recent)
    }

    // ------------------------------------------------------------ F3 提炼

    /**
     * 到点就提炼一次；不到点、样本不够、模型失败、JSON 不合法 —— 全部静默跳过。
     * @param force 设置页「立刻提炼记忆」用，跳过 24 小时 / 20 条那道门
     * @return 本次新增的印象条数
     */
    fun maybeConsolidate(client: AiClient, now: Long, force: Boolean = false): Int {
        val last = meta.getLong(KEY_LAST_CONSOLIDATE, 0L)
        val mark = meta.getInt(KEY_EVENT_MARK, 0)
        val total = events.count()
        val due = force || last <= 0L || (now - last) > CONSOLIDATE_GAP_SECONDS || (total - mark) >= CONSOLIDATE_MIN_NEW
        if (!due) return 0

        val since = if (force || last <= 0L) 0L else last
        val cand = ArrayList<EventStore.Event>()
        for (e in events.all()) {
            if (e.t >= since && e.imp >= DISTILL_MIN_IMP && e.who == "user") cand.add(e)
        }
        val picked = if (cand.size <= DISTILL_MAX_EVENTS) cand else cand.subList(cand.size - DISTILL_MAX_EVENTS, cand.size)
        if (picked.size < DISTILL_MIN_EVENTS) return 0

        val dialog = StringBuilder()
        for (e in picked) dialog.append(if (e.who == "pet") "她：" else "主人：").append(e.text).append('\n')

        val raw = client.chat(Prompt.EXTRACT_PROMPT, dialog.toString(), 200, 0.3)
        if (raw == null) {
            Diag.record(ctx, "提炼记忆失败：" + (client.lastError ?: "未知"))
            return 0
        }
        val items = parseStringArray(raw)
        if (items == null) {
            Diag.record(ctx, "提炼记忆：JSON 不合法，跳过")
            return 0
        }

        val imp = ImpressionStore.impFromSources(picked.map { it.imp })
        val src = picked.take(5).map { it.id }
        var added = 0
        for (t in items) {
            if (t.length > 60) continue
            if (imps.addOrMerge(t, imp, src, now) == "added") added++
        }
        meta.edit()
            .putLong(KEY_LAST_CONSOLIDATE, now)
            .putInt(KEY_EVENT_MARK, total)
            .apply()
        Diag.record(ctx, "提炼记忆：候选 ${picked.size} 条 → 新增 $added 条，现有 ${imps.size()} 条")
        return added
    }

    // ------------------------------------------------------------ F5 淡忘

    /** 跟提炼同一个时点跑（一天最多一次）。忘掉不等于删掉，先进 archive.jsonl */
    fun maybeForget(now: Long): Int {
        val last = meta.getLong(KEY_LAST_FORGET, 0L)
        if (last > 0L && (now - last) < FORGET_GAP_SECONDS) return 0
        val n = imps.forget(now)
        meta.edit().putLong(KEY_LAST_FORGET, now).apply()
        return n
    }

    // ------------------------------------------------------------ 设置页用

    fun dump(now: Long = sec()): String {
        val sb = StringBuilder()
        sb.append(imps.dump(now)).append("\n\n")
        sb.append("关系：好感 ").append(relation.affinity).append("/100，信任 ").append(relation.trust)
            .append("，当前情绪「").append(relation.currentMood(now)).append("」\n")
        sb.append("事件：").append(events.count()).append(" 条")
        if (events.count() > 0) {
            val all = events.all()
            if (all.isNotEmpty()) sb.append("（最早 ").append(all[0].t).append("，最新 ").append(all[all.size - 1].t).append("）")
        }
        sb.append("\n\n对话原文与印象都在 files/mem/ 下，可直接用文件管理器查看或备份。")
        return sb.toString()
    }

    fun clearAll() {
        events.clear()
        imps.clear()
        relation.clear()
        relation.save()
        meta.edit().clear().apply()
        Diag.record(ctx, "记忆已清空")
    }

    companion object {
        const val RECENT_TURNS = 3

        const val CONSOLIDATE_GAP_SECONDS = 24L * 3600L
        const val CONSOLIDATE_MIN_NEW = 20
        const val DISTILL_MIN_IMP = 3
        const val DISTILL_MAX_EVENTS = 30
        const val DISTILL_MIN_EVENTS = 3

        const val FORGET_GAP_SECONDS = 24L * 3600L

        const val KEY_LAST_CONSOLIDATE = "last_consolidate"
        const val KEY_LAST_FORGET = "last_forget"
        const val KEY_EVENT_MARK = "event_mark"

        fun sec(): Long = System.currentTimeMillis() / 1000L

        /**
         * 解析模型吐出来的 JSON 数组。
         * 宽容处理 ```json 包裹、前后多余文字；解析不出来返回 null（调用方静默跳过）。
         */
        fun parseStringArray(raw: String): List<String>? {
            var s = raw.trim()
            if (s.startsWith("```")) {
                s = s.removePrefix("```json").removePrefix("```").trim()
                val end = s.lastIndexOf("```")
                if (end > 0) s = s.substring(0, end)
            }
            val b = s.indexOf('[')
            val e = s.lastIndexOf(']')
            if (b < 0 || e <= b) return null
            return try {
                val arr = JSONArray(s.substring(b, e + 1))
                val out = ArrayList<String>()
                var i = 0
                while (i < arr.length()) {
                    val t = arr.optString(i, "").trim()
                    if (t.isNotEmpty()) out.add(t)
                    i++
                }
                out
            } catch (_: Throwable) {
                null
            }
        }
    }
}
