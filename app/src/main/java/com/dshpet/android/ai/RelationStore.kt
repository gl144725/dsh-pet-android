package com.dshpet.android.ai

import android.content.Context
import com.dshpet.android.Diag
import org.json.JSONObject
import java.io.File

/**
 * F1 的 `filesDir/mem/relation.json` + F6 情绪/好感。
 *
 * {"affinity":50,"trust":50,"mood":"平静","mood_until":0,"last_talk":0,"days":0}
 *
 * 「有情绪」= mood 会跨消息保留（不是每条消息重置），并且有到期时间；
 * 情绪词表见 [MOODS]，注入 prompt 的格式固定为
 * 「你现在的情绪是「{mood}」，好感度 {affinity}/100。」（见 [moodLine]）。
 */
class RelationStore(private val ctx: Context) {

    var affinity: Int = 50
    var trust: Int = 50
    var mood: String = "平静"
    var moodUntil: Long = 0L
    var lastTalk: Long = 0L
    var days: Int = 0

    private val dir: File = File(ctx.filesDir, "mem")
    private val file: File = File(dir, "relation.json")
    private var loaded = false

    init {
        try {
            dir.mkdirs()
            load()
        } catch (t: Throwable) {
            Diag.record(ctx, "relation 加载失败", t)
        }
    }

    private fun load() {
        if (loaded) return
        loaded = true
        try {
            if (!file.isFile) return
            val o = JSONObject(file.readText())
            affinity = o.optInt("affinity", 50).coerceIn(0, 100)
            trust = o.optInt("trust", 50).coerceIn(0, 100)
            mood = o.optString("mood", "平静")
            moodUntil = o.optLong("mood_until", 0L)
            lastTalk = o.optLong("last_talk", 0L)
            days = o.optInt("days", 0)
        } catch (t: Throwable) {
            Diag.record(ctx, "relation 读取失败", t)
        }
    }

    fun save() {
        try {
            dir.mkdirs()
            val o = JSONObject().apply {
                put("affinity", affinity)
                put("trust", trust)
                put("mood", mood)
                put("mood_until", moodUntil)
                put("last_talk", lastTalk)
                put("days", days)
            }
            val tmp = File(dir, "relation.json.tmp")
            tmp.writeText(o.toString())
            if (!tmp.renameTo(file)) {
                file.delete()
                if (!tmp.renameTo(file)) file.writeText(o.toString())
            }
        } catch (t: Throwable) {
            Diag.record(ctx, "relation 写入失败", t)
        }
    }

    fun clear() {
        affinity = 50
        trust = 50
        mood = "平静"
        moodUntil = 0L
        lastTalk = 0L
        days = 0
        loaded = true
        try {
            file.delete()
        } catch (t: Throwable) {
            Diag.record(ctx, "relation 清空失败", t)
        }
    }

    // ------------------------------------------------------------ F6 事件

    /** 一次对话（用户戳她 / 外部消息）：好感 +1，封顶 100 */
    fun onTalk(now: Long) {
        load()
        if (lastTalk > 0L && (now - lastTalk) > 4L * 3600L) days += 1
        lastTalk = now
        if (affinity < 100) affinity += 1
        save()
    }

    /** 被夸：你真好 / 乖 / 厉害 / 喜欢你 → 好感 +5，情绪「开心」2 小时 */
    fun onPraised(now: Long) {
        load()
        affinity = (affinity + 5).coerceAtMost(100)
        mood = "开心"
        moodUntil = now + 2L * 3600L
        save()
    }

    /** 被凶：闭嘴 / 烦 / 滚 / 笨 → 好感 -3、信任 -2，情绪「委屈」3 小时 */
    fun onScolded(now: Long) {
        load()
        affinity = (affinity - 3).coerceAtLeast(0)
        trust = (trust - 2).coerceAtLeast(0)
        mood = "委屈"
        moodUntil = now + 3L * 3600L
        save()
    }

    /** 外部文本里带情绪，顺手调一下关系（没有文本时不影响） */
    fun reactToUserText(text: String, now: Long) {
        if (text.isBlank()) return
        if (isScolded(text)) onScolded(now) else if (isPraised(text)) onPraised(now)
    }

    // ------------------------------------------------------------ 情绪

    /**
     * 当前情绪。三种会改写 mood 的情况：
     *  1. 到期 → 回落「平静」；
     *  2. 超过 48 小时没说话 → 「有点想你」（1 小时）；
     *  3. 刚被夸/被凶 → 由 [onPraised]/[onScolded] 直接写入。
     */
    fun currentMood(now: Long): String {
        load()
        var changed = false
        if (moodUntil > 0L && now > moodUntil) {
            mood = "平静"
            moodUntil = 0L
            changed = true
        }
        if (lastTalk > 0L && (now - lastTalk) > LONELY_SECONDS && mood == "平静") {
            mood = "有点想你"
            moodUntil = now + 3600L
            changed = true
        }
        if (changed) save()
        return mood
    }

    /** 注入 prompt 的固定格式（F6），一个字都别改 */
    fun moodLine(now: Long): String =
        "你现在的情绪是「${currentMood(now)}」，好感度 $affinity/100。"

    fun isLonely(now: Long): Boolean = lastTalk > 0L && (now - lastTalk) > LONELY_SECONDS

    companion object {
        /** 情绪词表（6-8 个，够用就行，不做无限扩张） */
        val MOODS = listOf("平静", "开心", "委屈", "有点想你", "好奇", "黏人", "有点困", "得意")

        val PRAISE_WORDS = listOf("你真好", "真好", "乖", "厉害", "喜欢你", "谢谢", "可爱", "棒")
        val SCOLD_WORDS = listOf("闭嘴", "烦", "滚", "笨", "讨厌你", "别说了", "废物", "吵")

        const val LONELY_SECONDS = 48L * 3600L

        fun isPraised(text: String): Boolean = PRAISE_WORDS.any { text.contains(it) }

        fun isScolded(text: String): Boolean = SCOLD_WORDS.any { text.contains(it) }
    }
}
