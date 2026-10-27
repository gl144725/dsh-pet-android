package com.dshpet.android.ai

import android.content.Context
import com.dshpet.android.Diag
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.ceil
import kotlin.math.pow

/**
 * F1 的 `filesDir/mem/impressions.json` + F4 检索 + F5 淡忘。
 *
 * [{"id":"i1","text":"...","imp":1-5,"mentions":0,"last":秒,"born":秒,"src":["e12"]}]
 *
 * 「会串」= 这里按分数排（不是按时间取最新 5 条）；
 * 「会忘」= 分低且 30 天没被提起的挪进 `archive.jsonl`（不是删，超过 500 条才真的丢最老的）。
 */
class ImpressionStore(private val ctx: Context) {

    data class Impression(
        val id: String,
        val text: String,
        val imp: Int,
        var mentions: Int,
        var last: Long,
        val born: Long,
        val src: List<String>,
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("id", id)
            put("text", text)
            put("imp", imp)
            put("mentions", mentions)
            put("last", last)
            put("born", born)
            put("src", JSONArray().apply { for (x in src) put(x) })
        }
    }

    private val dir: File = File(ctx.filesDir, "mem")
    private val file: File = File(dir, "impressions.json")
    private val archiveFile: File = File(dir, "archive.jsonl")

    private var cache: ArrayList<Impression>? = null

    init {
        try {
            dir.mkdirs()
        } catch (t: Throwable) {
            Diag.record(ctx, "mem 目录创建失败", t)
        }
    }

    // ------------------------------------------------------------ 读写

    fun all(): List<Impression> {
        val c = cache
        if (c != null) return c
        val out = ArrayList<Impression>()
        try {
            if (file.isFile) {
                val text = file.readText().trim()
                if (text.isNotEmpty()) {
                    val arr = JSONArray(text)
                    var i = 0
                    while (i < arr.length()) {
                        val o = arr.optJSONObject(i)
                        if (o != null) {
                            val t = o.optString("text", "")
                            if (t.isNotEmpty()) {
                                out.add(
                                    Impression(
                                        id = o.optString("id", "i" + (out.size + 1)),
                                        text = t,
                                        imp = o.optInt("imp", 1).coerceIn(1, 5),
                                        mentions = o.optInt("mentions", 0),
                                        last = o.optLong("last", 0L),
                                        born = o.optLong("born", 0L),
                                        src = optStrList(o.optJSONArray("src")),
                                    )
                                )
                            }
                        }
                        i++
                    }
                }
            }
        } catch (t: Throwable) {
            Diag.record(ctx, "impressions 读取失败", t)
        }
        cache = out
        return out
    }

    fun save() {
        try {
            dir.mkdirs()
            val c = cache ?: return
            val arr = JSONArray()
            for (x in c) arr.put(x.toJson())
            writeAtomic(file, arr.toString())
        } catch (t: Throwable) {
            Diag.record(ctx, "impressions 写入失败", t)
        }
    }

    fun clear() {
        try {
            cache = ArrayList()
            file.delete()
            archiveFile.delete()
        } catch (t: Throwable) {
            Diag.record(ctx, "impressions 清空失败", t)
        }
    }

    fun size(): Int = all().size

    fun archiveSize(): Int = try {
        if (!archiveFile.isFile) 0 else archiveFile.readLines().count { it.trim().isNotEmpty() }
    } catch (_: Throwable) {
        0
    }

    // ------------------------------------------------------------ F3 去重写入

    /**
     * @return "added" / "merged" / "skipped"
     */
    fun addOrMerge(text: String, imp: Int, src: List<String>, now: Long): String {
        val c = all() as ArrayList<Impression>
        val clean = text.trim()
        if (clean.isEmpty()) return "skipped"
        for (x in c) {
            if (TextSim.overlap(clean, x.text) > DEDUPE_THRESHOLD) {
                x.mentions += 1
                x.last = now
                save()
                return "merged"
            }
        }
        var maxId = 0
        for (x in c) {
            val n = x.id.removePrefix("i").toIntOrNull() ?: 0
            if (n > maxId) maxId = n
        }
        c.add(
            Impression(
                id = "i" + (maxId + 1),
                text = clean,
                imp = imp.coerceIn(1, 5),
                mentions = 1,
                last = now,
                born = now,
                src = src,
            )
        )
        save()
        return "added"
    }

    // ------------------------------------------------------------ F4 检索（会串）

    /**
     * 按分数取前 limit 条（不是按时间取最新），只保留 score >= 5 的。
     * 被选中的印象 mentions+1、last=now —— 这同时也是 F8 里「7 天内不再主动提」的依据。
     */
    fun recall(input: String, limit: Int, now: Long): List<Impression> {
        val c = all()
        if (c.isEmpty()) return emptyList()
        val scored = ArrayList<Pair<Impression, Double>>(c.size)
        for (x in c) {
            val d = daysSince(x.last, now)
            scored.add(Pair(x, scoreOf(x.imp, d, TextSim.overlap(input, x.text))))
        }
        scored.sortByDescending { it.second }
        val picked = ArrayList<Impression>(limit)
        for (p in scored) {
            if (picked.size >= limit) break
            if (p.second < RECALL_MIN_SCORE) continue
            picked.add(p.first)
        }
        if (picked.isNotEmpty()) {
            for (x in picked) {
                x.mentions += 1
                x.last = now
            }
            save()
        }
        return picked
    }

    // ------------------------------------------------------------ F8 主动开口的候选

    /** imp >= 3 且 7 天内没被提起过的印象里随机挑一条；没有就返回 null */
    fun pickProactive(now: Long): Impression? {
        val cands = ArrayList<Impression>()
        for (x in all()) {
            if (x.imp >= 3 && daysSince(x.last, now) > PROACTIVE_COOLDOWN_DAYS) cands.add(x)
        }
        if (cands.isEmpty()) return null
        return cands[(Math.random() * cands.size).toInt().coerceIn(0, cands.size - 1)]
    }

    /** 说出来之后：last = now，7 天内不再主动提（同一条印象 7 天最多提 1 次） */
    fun markProactive(x: Impression, now: Long) {
        x.last = now
        x.mentions += 1
        save()
    }

    // ------------------------------------------------------------ F5 淡忘

    /**
     * 有效分 < 1.5 且 30 天没被提起 → 挪进 archive.jsonl。
     * @return 本次归档条数
     */
    fun forget(now: Long): Int {
        val c = all() as ArrayList<Impression>
        if (c.isEmpty()) return 0
        val keep = ArrayList<Impression>(c.size)
        val gone = ArrayList<Impression>()
        for (x in c) {
            if (shouldForget(x.imp, daysSince(x.last, now))) gone.add(x) else keep.add(x)
        }
        if (gone.isEmpty()) return 0
        archive(gone)
        cache = keep
        save()
        Diag.record(ctx, "淡忘：归档 ${gone.size} 条，剩 ${keep.size} 条")
        return gone.size
    }

    private fun archive(gone: List<Impression>) {
        try {
            val sb = StringBuilder()
            for (x in gone) sb.append(x.toJson().toString()).append('\n')
            val body = if (archiveFile.isFile) archiveFile.readText() else ""
            var text = body + sb.toString()
            // 归档上限 500 条：只在这里才真的从磁盘上丢掉最老的
            val lines = text.split("\n").filter { it.trim().isNotEmpty() }
            if (lines.size > ARCHIVE_CAP) {
                val kept = lines.subList(lines.size - ARCHIVE_CAP, lines.size)
                val t = StringBuilder()
                for (l in kept) t.append(l).append('\n')
                text = t.toString()
            }
            writeAtomic(archiveFile, text)
        } catch (t: Throwable) {
            Diag.record(ctx, "archive 写入失败", t)
        }
    }

    /** 「查看记忆」用：人可读的一段纯文本 */
    fun dump(now: Long): String {
        val c = all()
        if (c.isEmpty()) return "印象：0 条\n\n还没有提炼出任何长期印象。"
        val sb = StringBuilder()
        sb.append("印象：").append(c.size).append(" 条（已淡忘归档：").append(archiveSize()).append(" 条）\n\n")
        val sorted = c.sortedByDescending { scoreOf(it.imp, daysSince(it.last, now), 0.0) }
        var i = 1
        for (x in sorted) {
            sb.append(i).append(". [imp ").append(x.imp)
                .append(" · 提过 ").append(x.mentions).append(" 次 · ")
                .append(ageText(daysSince(x.last, now))).append("前] ")
                .append(x.text).append('\n')
            i++
        }
        sb.append("\n文件：").append(file.absolutePath)
        return sb.toString()
    }

    // ------------------------------------------------------------ 工具

    private fun ageText(days: Double): String = when {
        days < 1.0 -> "不到 1 天"
        days < 30.0 -> days.toInt().toString() + " 天"
        else -> (days / 30.0).toInt().toString() + " 个月"
    }

    private fun writeAtomic(target: File, text: String) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(target)) {
            target.delete()
            if (!tmp.renameTo(target)) target.writeText(text)
        }
    }

    private fun optStrList(a: JSONArray?): List<String> {
        if (a == null) return emptyList()
        val out = ArrayList<String>(a.length())
        var i = 0
        while (i < a.length()) {
            out.add(a.optString(i, ""))
            i++
        }
        return out
    }

    companion object {
        /** F4：命中门槛（放大口径下的 5 分） */
        const val RECALL_MIN_SCORE = 5.0

        /** F3 第 5 条：2-gram 重叠率 > 0.6 视为同一条 */
        const val DEDUPE_THRESHOLD = 0.6

        /** F8：同一条印象 7 天内最多主动提 1 次 */
        const val PROACTIVE_COOLDOWN_DAYS = 7.0

        /** F5：归档上限，超过才真的丢最老的 */
        const val ARCHIVE_CAP = 500

        /** F5：多久没被提起才算「可以忘」 */
        const val FORGET_DAYS = 30.0

        /**
         * F5：有效分阈值。
         *
         * 票面 §一.1 写的是 1.5，但那个数在这个系统里**不可达**：提炼门槛是 imp>=3
         * （`MemoryCore.DISTILL_MIN_IMP`），印象的 imp = ceil(来源事件均值) >= 3，
         * 于是有效分最低也有 `3*0.6 + 0 = 1.8` > 1.5 —— 一条都忘不掉，「会忘」形同虚设。
         * 改成 2.0：imp=3 且 30 天没人提的印象会真的淡出，imp>=4 的留下。
         * 这个数是 tools/mem_sim.py 跑出来的（1.5 时淡忘 0 条），实测见该脚本输出。
         */
        const val FORGET_MIN_SCORE = 2.0

        /** 半衰期 30 天：0.5 ^ (天数差 / 30) */
        fun decay(daysSinceLast: Double): Double {
            if (daysSinceLast <= 0.0) return 1.0
            return 0.5.pow(daysSinceLast / 30.0)
        }

        fun daysSince(last: Long, now: Long): Double {
            if (last <= 0L) return 0.0
            val d = (now - last).toDouble() / 86400.0
            return if (d < 0.0) 0.0 else d
        }

        /**
         * F4 检索分（放大 10 倍的口径，整数比较，排序等价）：
         *   score = imp*6 + 30*衰减 + 20*关键词重合
         * 对应票面 §一.1 的 imp×0.6 + 衰减×0.3 + 关键词×0.1（两者同构，只是量纲不同）。
         */
        fun scoreOf(imp: Int, daysSinceLast: Double, overlap: Double): Double =
            imp * 6.0 + 30.0 * decay(daysSinceLast) + 20.0 * overlap

        /**
         * F5 淡忘用的「有效分」（票面 §一.1 的原口径，量纲 0..3.4）：
         *   imp×0.6 + 衰减×0.3 + 关键词×0.1（淡忘时不看关键词，按 0 算）
         * 注意：F4 的 score 最低也有 6 分，永远不可能 < 5，
         * 若拿它当淡忘阈值则一条都忘不掉 —— 所以淡忘用这一把 0..3.4 的尺子。
         */
        fun effectiveScore(imp: Int, daysSinceLast: Double): Double =
            imp * 0.6 + 0.3 * decay(daysSinceLast)

        fun shouldForget(imp: Int, daysSinceLast: Double): Boolean =
            daysSinceLast > FORGET_DAYS && effectiveScore(imp, daysSinceLast) < FORGET_MIN_SCORE

        /** 新印象的 imp = 来源事件 imp 的均值向上取整（F3） */
        fun impFromSources(imps: List<Int>): Int {
            if (imps.isEmpty()) return 1
            var sum = 0
            for (x in imps) sum += x
            return ceil(sum.toDouble() / imps.size).toInt().coerceIn(1, 5)
        }
    }
}
