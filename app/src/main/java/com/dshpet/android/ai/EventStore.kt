package com.dshpet.android.ai

import android.content.Context
import com.dshpet.android.Diag
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * F1 的 `filesDir/mem/events.jsonl`。
 *
 * 一行一条事件：{"t":秒,"who":"user"/"pet","text":"...","imp":1-5,"emo":"开心/难过/平常","tags":[...]}
 * 超过 4MB 时把最老的 1MB 挪到 `events.old.jsonl`（只保留一个 old 文件，不无限长）。
 *
 * 写盘一律「临时文件 + rename」（见 [writeAtomic]），避免进程被杀留下半行 JSON。
 */
class EventStore(private val ctx: Context) {

    data class Event(
        val id: String,
        val t: Long,
        val who: String,
        val text: String,
        val imp: Int,
        val emo: String,
        val tags: List<String>,
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("t", t)
            put("who", who)
            put("text", text)
            put("imp", imp)
            put("emo", emo)
            put("tags", JSONArray().apply { for (x in tags) put(x) })
        }
    }

    private val dir: File = File(ctx.filesDir, "mem")
    private val file: File = File(dir, "events.jsonl")
    private val oldFile: File = File(dir, "events.old.jsonl")

    init {
        try {
            dir.mkdirs()
        } catch (t: Throwable) {
            Diag.record(ctx, "mem 目录创建失败", t)
        }
    }

    fun append(e: Event) {
        try {
            dir.mkdirs()
            rollIfNeeded()
            val body = if (file.isFile) file.readText() else ""
            writeAtomic(file, body + e.toJson().toString() + "\n")
        } catch (t: Throwable) {
            Diag.record(ctx, "events 写入失败", t)
        }
    }

    /** 全部事件，按写入顺序（即时间顺序） */
    fun all(): List<Event> {
        val out = ArrayList<Event>()
        try {
            if (!file.isFile) return out
            var i = 0
            file.forEachLine { line ->
                val s = line.trim()
                if (s.isNotEmpty()) {
                    try {
                        val o = JSONObject(s)
                        out.add(
                            Event(
                                id = "e" + (i + 1),
                                t = o.optLong("t", 0L),
                                who = o.optString("who", "user"),
                                text = o.optString("text", ""),
                                imp = o.optInt("imp", 1).coerceIn(1, 5),
                                emo = o.optString("emo", "平常"),
                                tags = optStrList(o.optJSONArray("tags")),
                            )
                        )
                        i++
                    } catch (_: Throwable) {
                        // 坏行直接跳过，不因为一行坏 JSON 丢掉整个记忆
                    }
                }
            }
        } catch (t: Throwable) {
            Diag.record(ctx, "events 读取失败", t)
        }
        return out
    }

    fun count(): Int {
        var n = 0
        try {
            if (!file.isFile) return 0
            file.forEachLine { if (it.trim().isNotEmpty()) n++ }
        } catch (_: Throwable) {
        }
        return n
    }

    fun clear() {
        try {
            file.delete()
            oldFile.delete()
        } catch (t: Throwable) {
            Diag.record(ctx, "events 清空失败", t)
        }
    }

    fun sizeBytes(): Long = try {
        if (file.isFile) file.length() else 0L
    } catch (_: Throwable) {
        0L
    }

    // ------------------------------------------------------------ 滚动

    private fun rollIfNeeded() {
        try {
            if (!file.isFile || file.length() <= MAX_BYTES) return
            val lines = file.readLines()
            val moved = ArrayList<String>()
            val kept = ArrayList<String>()
            var bytes = 0L
            for (l in lines) {
                val len = (l.toByteArray(Charsets.UTF_8).size + 1).toLong()
                if (bytes < MOVE_BYTES) {
                    moved.add(l)
                    bytes += len
                } else {
                    kept.add(l)
                }
            }
            writeAtomic(oldFile, joinLines(moved))
            writeAtomic(file, joinLines(kept))
            Diag.record(ctx, "events 滚动：移出 ${moved.size} 行，保留 ${kept.size} 行")
        } catch (t: Throwable) {
            Diag.record(ctx, "events 滚动失败", t)
        }
    }

    private fun joinLines(lines: List<String>): String {
        if (lines.isEmpty()) return ""
        val sb = StringBuilder()
        for (l in lines) sb.append(l).append('\n')
        return sb.toString()
    }

    private fun writeAtomic(target: File, text: String) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(target)) {
            target.delete()
            if (!tmp.renameTo(target)) {
                target.writeText(text)
            }
        }
    }

    companion object {
        const val MAX_BYTES = 4L * 1024L * 1024L
        const val MOVE_BYTES = 1L * 1024L * 1024L

        /** 命中这些词，说明这句里有「长期有效」的东西（F2：+2） */
        val FEELING_WORDS = listOf(
            "喜欢", "讨厌", "最爱", "受不了", "烦死", "开心", "难过", "生气", "害怕", "想要",
        )

        /** 第一人称（F2：+1） */
        val SELF_WORDS = listOf("我的", "我要", "我想", "我在", "我最近")

        /** 具体时间 / 数字（F2：+1） */
        val TIME_WORDS = listOf("今天", "明天", "下周", "点", "号", "元")

        val POSITIVE_WORDS = listOf(
            "开心", "喜欢", "高兴", "太好了", "爱", "棒", "舒服", "期待", "好玩", "满足",
        )

        val NEGATIVE_WORDS = listOf(
            "难过", "烦", "生气", "讨厌", "累", "害怕", "委屈", "伤心", "哭", "崩溃", "受不了",
        )

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

        /**
         * F2：事件重要度打分。**纯函数**，不碰 Context / 文件 / 网络。
         * 基线 1，封顶 5；who=pet 恒等于 1（她自己说的话不算主人的事）。
         */
        fun scoreEvent(text: String, who: String): Pair<Int, String> {
            if (who == "pet") return Pair(1, emoOf(text))
            var s = 1
            if (FEELING_WORDS.any { text.contains(it) }) s += 2
            if (SELF_WORDS.any { text.contains(it) }) s += 1
            // 具体时间 / 数字（F2 里「今天/明天/下周/点/号/元」与阿拉伯数字算同一档，只加一次）
            if (TIME_WORDS.any { text.contains(it) } || text.any { it.isDigit() }) s += 1
            var q = 0
            var e = 0
            for (c in text) {
                if (c == '!' || c == '！') e++
                if (c == '?' || c == '？') q++
            }
            if (q >= 2 || e >= 2) s += 1
            if (text.length > 80) s += 1
            return Pair(s.coerceIn(1, 5), emoOf(text))
        }

        /** F1 的 emo 字段：命中正向词表 → 开心；命中负向词表 → 难过；都没有 → 平常 */
        fun emoOf(text: String): String {
            if (POSITIVE_WORDS.any { text.contains(it) }) return "开心"
            if (NEGATIVE_WORDS.any { text.contains(it) }) return "难过"
            return "平常"
        }

        /** 顺带把命中的关键词当 tags 存下来，方便人工排查记忆为什么被记 */
        fun tagsOf(text: String): List<String> {
            val out = ArrayList<String>()
            for (w in FEELING_WORDS) if (text.contains(w)) out.add(w)
            for (w in SELF_WORDS) if (text.contains(w)) out.add(w)
            return out
        }
    }
}
