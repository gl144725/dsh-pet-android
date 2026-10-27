package com.dshpet.android

import android.content.Context

/** 极简本地偏好（尺寸 / 是否常驻），不依赖任何第三方库 */
object Prefs {
    private const val FILE = "dsh_pet"
    private const val KEY_SIZE = "size_dp"
    private const val KEY_RUNNING = "running"
    private const val KEY_KEEP_ALIVE = "keep_alive"
    private const val KEY_KEEP_ALIVE_ASKED = "keep_alive_asked"
    private const val KEY_SOUND = "sound_on"
    private const val KEY_PETS = "pet_count"
    private const val KEY_BUBBLE = "bubble_on"
    private const val KEY_EDGE_HIDE = "edge_hide"
    private const val KEY_AI_ON = "ai_on"
    private const val KEY_AI_TTS = "ai_tts"

    const val DEFAULT_SIZE_DP = 150
    const val MIN_SIZE_DP = 80
    const val MAX_SIZE_DP = 360
    const val MIN_PETS = 1
    const val MAX_PETS = 3

    private fun sp(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun sizeDp(ctx: Context): Int = sp(ctx).getInt(KEY_SIZE, DEFAULT_SIZE_DP)

    fun setSizeDp(ctx: Context, dp: Int) {
        sp(ctx).edit().putInt(KEY_SIZE, dp.coerceIn(MIN_SIZE_DP, MAX_SIZE_DP)).apply()
    }

    fun running(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_RUNNING, false)

    fun setRunning(ctx: Context, v: Boolean) {
        sp(ctx).edit().putBoolean(KEY_RUNNING, v).apply()
    }

    /** 点击音效开关，默认开启 */
    fun soundOn(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_SOUND, true)

    fun setSoundOn(ctx: Context, v: Boolean) {
        sp(ctx).edit().putBoolean(KEY_SOUND, v).apply()
    }

    /** 桌宠只数：同时放 1~3 只，默认 1 */
    fun petCount(ctx: Context): Int = sp(ctx).getInt(KEY_PETS, 1).coerceIn(MIN_PETS, MAX_PETS)

    fun setPetCount(ctx: Context, n: Int) {
        sp(ctx).edit().putInt(KEY_PETS, n.coerceIn(MIN_PETS, MAX_PETS)).apply()
    }

    /** 碎碎念（头顶台词气泡），默认开启 */
    fun bubbleOn(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_BUBBLE, true)

    fun setBubbleOn(ctx: Context, v: Boolean) {
        sp(ctx).edit().putBoolean(KEY_BUBBLE, v).apply()
    }

    /** 贴边躲藏（松手吸附到屏幕边缘，闲置后只露半个身子），默认开启 */
    fun edgeHideOn(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_EDGE_HIDE, true)

    fun setEdgeHideOn(ctx: Context, v: Boolean) {
        sp(ctx).edit().putBoolean(KEY_EDGE_HIDE, v).apply()
    }

    /** 后台保活：从「最近任务」隐藏 + 被系统回收后自动回来（默认开启） */
    fun keepAlive(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_KEEP_ALIVE, true)

    fun setKeepAlive(ctx: Context, v: Boolean) {
        sp(ctx).edit().putBoolean(KEY_KEEP_ALIVE, v).apply()
    }

    /** 电池优化豁免是否已经自动问过一次（只问一次，不反复弹系统弹窗） */
    fun keepAliveAsked(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_KEEP_ALIVE_ASKED, false)

    fun setKeepAliveAsked(ctx: Context, v: Boolean) {
        sp(ctx).edit().putBoolean(KEY_KEEP_ALIVE_ASKED, v).apply()
    }

    /** AI 层总开关，默认关闭：没配 Key 之前桌宠行为和以前完全一样 */
    fun aiOn(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_AI_ON, false)

    fun setAiOn(ctx: Context, v: Boolean) {
        sp(ctx).edit().putBoolean(KEY_AI_ON, v).apply()
    }

    /** 语音朗读开关（本期只存值，发声留下一票） */
    fun aiTts(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_AI_TTS, false)

    fun setAiTts(ctx: Context, v: Boolean) {
        sp(ctx).edit().putBoolean(KEY_AI_TTS, v).apply()
    }
}
