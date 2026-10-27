package com.dshpet.android.ai

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Mattermost 通道：只用 `java.net.HttpURLConnection` + `org.json`（Android 自带），
 * 不引任何第三方库（本仓库刻意零依赖，引了必然编译失败）。
 *
 * 搬自 Coopanion `Mattermost.kt`（203 行），接口与超时口径保持一致：
 * 服务器地址 / Bot Token / 频道 ID 全部由主人在设置页自己填，**代码里不写死任何值**。
 *
 * 五个方法（`AiCore` 只用这五个）：
 *  - [test]              GET /api/v4/users/me → 用户名（设置页「测试连接」）
 *  - [meId]              GET /api/v4/users/me → 自己的 user_id（过滤自己发的消息，否则自问自答死循环）
 *  - [latestCreateAt]    GET .../posts?per_page=1 → 最新一条的 create_at（首次启动建基线，不补历史）
 *  - [fetchNewMessages]  GET .../posts?since= → 新消息，按时间升序
 *  - [send]              POST /api/v4/posts → 是否成功
 *
 * 所有网络方法都是**同步阻塞**的，调用方必须在 `AiCore` 的 io 线程里调（那一条线程是唯一的网络出口）。
 */
object MattermostBridge {

    /** 所有请求超时统一 8 秒（对齐 Coopanion `Mattermost.kt:16`）。 */
    private const val TIMEOUT_MS = 8000

    /** 自己的 user id 缓存（key = url|token），轮询每 30 秒一次，不必每次都问。 */
    private var meKey = ""
    private var meIdCache = ""

    /** 最近收发（只在内存里，最多 10 条），给设置页的「MM 最近消息」用（F6）。 */
    private val recent = ArrayList<Triple<Long, String, String>>()

    /** 记一条收发：who 是「主人」或「她」。 */
    fun record(who: String, text: String) {
        synchronized(recent) {
            recent.add(Triple(System.currentTimeMillis(), who, text))
            while (recent.size > RECENT_CAP) recent.removeAt(0)
        }
    }

    /** 最近 10 条收发（时间升序），纯内存，不落盘 */
    fun recentLines(): List<Triple<Long, String, String>> = synchronized(recent) { ArrayList(recent) }

    private fun base(url: String): String = url.trim().trimEnd('/')

    private fun open(url: String, token: String, method: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            // 全仓库唯一出现 Authorization 头的地方：token 来自主人填的配置，不在这里
            setRequestProperty("Authorization", "Bearer " + token.trim())
            setRequestProperty("Accept", "application/json")
        }

    /** 读响应体：2xx 返回正文，否则抛异常（带服务端 message，方便界面显示原因）。 */
    private fun readBody(c: HttpURLConnection): String {
        val code = c.responseCode
        val stream = if (code in 200..299) c.inputStream else c.errorStream
        val txt = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        if (code !in 200..299) {
            var reason = ""
            try {
                reason = JSONObject(txt).optString("message")
            } catch (_: Exception) {
                reason = ""
            }
            throw IllegalStateException("HTTP " + code + (if (reason.isNotEmpty()) "：" + reason else ""))
        }
        return txt
    }

    /** GET /api/v4/users/me → 用户名。失败抛异常（给「测试连接」用）。 */
    fun test(url: String, token: String): String {
        require(url.isNotBlank()) { "服务器地址为空" }
        require(token.isNotBlank()) { "Token 为空" }
        var conn: HttpURLConnection? = null
        try {
            val c = open(base(url) + "/api/v4/users/me", token, "GET")
            conn = c
            val name = JSONObject(readBody(c)).optString("username")
            if (name.isEmpty()) throw IllegalStateException("返回里没有 username")
            return name
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    /** 拿自己的 user id（等同 getMe），带缓存。空串表示拿不到（此时不做过滤，宁可不回也不猜）。 */
    fun meId(url: String, token: String): String {
        val key = base(url) + "|" + token.trim()
        if (key == meKey && meIdCache.isNotEmpty()) return meIdCache
        var conn: HttpURLConnection? = null
        try {
            val c = open(base(url) + "/api/v4/users/me", token, "GET")
            conn = c
            val id = JSONObject(readBody(c)).optString("id")
            if (id.isNotEmpty()) {
                meKey = key
                meIdCache = id
            }
            return id
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * GET /api/v4/channels/{channelId}/posts?per_page=1
     * 拿频道里最新一条 post 的 create_at（毫秒），拿不到返回 0。
     * 用途：首次启动对齐「服务端时间」当轮询基线 —— 手机时钟快了/慢了都不会漏消息或重放历史。
     */
    fun latestCreateAt(url: String, token: String, channelId: String): Long {
        var conn: HttpURLConnection? = null
        return try {
            val c = open(
                base(url) + "/api/v4/channels/" + channelId.trim() + "/posts?per_page=1",
                token,
                "GET",
            )
            conn = c
            val posts = JSONObject(readBody(c)).optJSONObject("posts")
            var max = 0L
            if (posts != null) {
                val keys = posts.keys()
                while (keys.hasNext()) {
                    val o = posts.optJSONObject(keys.next()) ?: continue
                    val at = o.optLong("create_at", 0L)
                    if (at > max) max = at
                }
            }
            max
        } catch (e: Exception) {
            0L
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * GET /api/v4/channels/{channelId}/posts?since={since}
     * 返回 `(游标时间, postId, message)`，升序；跳过自己发的 post 和被编辑过的老消息。
     *
     * 游标时间取 `max(create_at, update_at)`：Mattermost 的 `since` 是拿 `update_at` 比的，
     * 只推 create_at 会让「编辑过的消息」每轮都回来一遍。
     */
    fun fetchNewMessages(
        url: String,
        token: String,
        channelId: String,
        since: Long,
    ): List<Triple<Long, String, String>> {
        val out = ArrayList<Triple<Long, String, String>>()
        val selfId = try {
            meId(url, token)
        } catch (e: Exception) {
            ""
        }
        var conn: HttpURLConnection? = null
        try {
            val c = open(
                base(url) + "/api/v4/channels/" + channelId.trim() + "/posts?since=" + since,
                token,
                "GET",
            )
            conn = c
            val posts = JSONObject(readBody(c)).optJSONObject("posts") ?: return out
            val keys = posts.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val o = posts.optJSONObject(k) ?: continue
                // 自己发的消息直接跳过（否则她会自己跟自己聊死循环）
                if (selfId.isNotEmpty() && o.optString("user_id") == selfId) continue
                val body = o.optString("message")
                if (body.isEmpty()) continue
                val createAt = o.optLong("create_at", 0L)
                val updateAt = o.optLong("update_at", createAt)
                // 编辑过的老消息不算新消息（否则改一次她就回一次）；它每轮被 skip 一次无害，
                // 新消息照样会进窗口（服务端 since 比的是 update_at）
                if (updateAt > createAt + 1000L) continue
                // 注意：这里只取，不记 recent —— 记「收到」由调用方在处理成功后记，
                // 否则正忙跳过的那条会在设置页里出现两遍（这一轮一次、下一轮一次）。
                out.add(Triple(maxOf(createAt, updateAt), o.optString("id", k), body))
            }
            out.sortBy { it.first }
            return out
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    /** POST /api/v4/posts，成功返回 true。失败不抛异常，交给调用方按 false 处理（不重试）。 */
    fun send(url: String, token: String, channelId: String, text: String): Boolean {
        if (text.isBlank() || channelId.isBlank()) return false
        var conn: HttpURLConnection? = null
        return try {
            val body = JSONObject().apply {
                put("channel_id", channelId.trim())
                put("message", text)
            }.toString()
            val c = open(base(url) + "/api/v4/posts", token, "POST")
            conn = c
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            readBody(c)
            record("她", text)
            true
        } catch (e: Exception) {
            false
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    /** 内存环形缓冲的容量（F6：最近 10 条收发） */
    const val RECENT_CAP = 10
}
