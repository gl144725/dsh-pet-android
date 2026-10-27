package com.dshpet.android.ai

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.dshpet.android.Prefs

/**
 * F9：AI 设置页。单页、全代码构建（不新增任何 res 文件，沿用 app 现有配色），
 * 结构照着 `LinesActivity` 的路子：进来读文件 → 改 → 存 → 立刻生效（不用重启桌宠）。
 *
 * 这里刻意画得很「素」：AI 是能力层，不是主界面。配置错误时唯一的后果是
 * 「她变回原来的台词模式」，所以不需要花哨的状态机。
 */
class AiSettingsActivity : Activity() {

    private lateinit var cbAi: CheckBox
    private lateinit var etBase: EditText
    private lateinit var etKey: EditText
    private lateinit var etModel: EditText
    private lateinit var cbTts: CheckBox
    private lateinit var tvStatus: TextView

    /** 界面上没暴露人设编辑，但保存时必须原样带回去，别把用户手改的人设冲掉 */
    private var persona: String = AiConfig.DEFAULT_PERSONA

    private val cBg = Color.parseColor("#070D18")
    private val cCard = Color.parseColor("#16223B")
    private val cStroke = Color.parseColor("#3C5385")
    private val cAccent = Color.parseColor("#4E6BFF")
    private val cText = Color.parseColor("#EEF3FF")
    private val cSub = Color.parseColor("#9DB1D5")
    private val cHint = Color.parseColor("#7A8BAE")

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val cfg = AiConfig.load(this)
        persona = cfg.persona

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
            setBackgroundColor(cBg)
        }

        root.addView(title("AI 设置"))
        root.addView(sub("给她接上大模型。不填 Key 就完全离线，桌宠行为一点不变。"))

        cbAi = CheckBox(this).apply {
            text = "启用 AI（关掉 = 只用本地台词）"
            setTextColor(cText)
            textSize = 15f
            isChecked = Prefs.aiOn(this@AiSettingsActivity)
            setPadding(0, dp(10), 0, dp(2))
        }
        root.addView(cbAi)

        tvStatus = TextView(this).apply {
            setTextColor(cHint)
            textSize = 12f
            setLineSpacing(dp(2).toFloat(), 1f)
        }
        root.addView(tvStatus, lp(6))

        root.addView(section("接口"))
        etBase = input(root, "API 地址", cfg.apiBase, false)
        etKey = input(root, "API Key（只存在手机上，不进 git）", cfg.apiKey, true)
        etModel = input(root, "模型名", cfg.apiModel, false)

        root.addView(section("说话"))
        cbTts = CheckBox(this).apply {
            text = "语音朗读（本期只存开关，暂不发声）"
            setTextColor(cText)
            textSize = 15f
            isChecked = cfg.ttsOn || Prefs.aiTts(this@AiSettingsActivity)
            setPadding(0, dp(10), 0, dp(2))
        }
        root.addView(cbTts)

        root.addView(action("保存", true) { save() })
        root.addView(action("立刻提炼记忆", false) { consolidateNow() })
        root.addView(action("查看记忆", false) { showMemory() })
        root.addView(action("清空记忆", false) { clearMemory() })

        root.addView(
            sub(
                "配置文件：" + AiConfig.file(this).absolutePath + "\n" +
                    "记忆文件：files/mem/（events.jsonl / impressions.json / relation.json / archive.jsonl）\n" +
                    "提示：复制到电脑上改完再放回来，也能生效（每次说话都会重读配置）。",
            ),
            lp(18),
        )

        setContentView(
            ScrollView(this).apply {
                setBackgroundColor(cBg)
                addView(root)
            },
        )
        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    // ------------------------------------------------------------ 视图小工具

    private fun title(t: String): TextView = TextView(this).apply {
        text = t
        setTextColor(cText)
        textSize = 22f
        setPadding(0, 0, 0, dp(6))
    }

    private fun sub(t: String): TextView = TextView(this).apply {
        text = t
        setTextColor(cSub)
        textSize = 13f
        setLineSpacing(dp(3).toFloat(), 1f)
    }

    private fun section(t: String): TextView = TextView(this).apply {
        text = t
        setTextColor(cAccent)
        textSize = 13f
        setPadding(0, dp(20), 0, dp(4))
    }

    private fun lp(top: Int): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(top) }

    private fun input(root: LinearLayout, hintText: String, initial: String, password: Boolean): EditText {
        val et = EditText(this).apply {
            hint = hintText
            setHintTextColor(cHint)
            setTextColor(cText)
            textSize = 14f
            setSingleLine(true)
            setText(initial)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = round(cCard, cStroke, 10)
            inputType = if (password) {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            } else {
                InputType.TYPE_CLASS_TEXT
            }
        }
        root.addView(et, lp(10))
        return et
    }

    private fun action(label: String, primary: Boolean, onClick: () -> Unit): TextView {
        val tv = TextView(this).apply {
            text = label
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(if (primary) Color.WHITE else cText)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = round(if (primary) cAccent else cCard, if (primary) cAccent else cStroke, 12)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
        return tv.also { it.layoutParams = lp(12) }
    }

    private fun round(fill: Int, stroke: Int, radiusDp: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(fill)
            cornerRadius = dp(radiusDp).toFloat()
            setStroke(dp(1), stroke)
        }

    // ------------------------------------------------------------ 行为

    private fun save() {
        val cfg = AiConfig(
            apiBase = etBase.text.toString().trim().ifBlank { AiConfig.DEFAULT_BASE },
            apiKey = etKey.text.toString().trim(),
            apiModel = etModel.text.toString().trim().ifBlank { AiConfig.DEFAULT_MODEL },
            persona = persona,
            ttsOn = cbTts.isChecked,
            enabled = cbAi.isChecked,
        )
        val ok = AiConfig.save(this, cfg)
        Prefs.setAiOn(this, cbAi.isChecked)
        Prefs.setAiTts(this, cbTts.isChecked)
        refreshStatus()
        when {
            !ok -> toast("保存失败，看看上面日志 / 存储权限")
            cbAi.isChecked && !cfg.configured -> toast("已保存，但没填 API Key —— AI 还是不会启用")
            else -> toast("已保存")
        }
    }

    /** 手动强制跑一次提炼（跳过「24 小时 / 20 条」的门槛，用户在按钮上明确要求了） */
    private fun consolidateNow() {
        val cfg = AiConfig.load(this)
        if (!cfg.configured) {
            toast("先填 API Key 再提炼")
            return
        }
        toast("正在提炼…（几秒钟）")
        Thread {
            var msg = "没有可提炼的内容（事件不足 3 条）"
            try {
                val mem = MemoryCore(this)
                val n = mem.maybeConsolidate(AiClient(cfg), MemoryCore.sec(), true)
                msg = if (n > 0) "提炼完成，新增 $n 条印象" else "这次没提炼出新东西"
            } catch (t: Throwable) {
                msg = "提炼失败：" + (t.message ?: t.javaClass.simpleName)
            }
            val finalMsg = msg
            runOnUiThread {
                toast(finalMsg)
                refreshStatus()
            }
        }.start()
    }

    private fun showMemory() {
        val text = try {
            MemoryCore(this).dump()
        } catch (t: Throwable) {
            "读取失败：" + (t.message ?: t.javaClass.simpleName)
        }
        val tv = TextView(this).apply {
            setText(text)
            setTextColor(cText)
            textSize = 13f
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setBackgroundColor(cBg)
        }
        AlertDialog.Builder(this)
            .setTitle("她记得的事")
            .setView(ScrollView(this).apply { addView(tv) })
            .setPositiveButton("知道了", null)
            .show()
    }

    private fun clearMemory() {
        AlertDialog.Builder(this)
            .setTitle("清空记忆？")
            .setMessage("会删掉提炼出来的印象、全部对话原文，以及好感度/情绪记录。")
            .setNegativeButton("算了", null)
            .setPositiveButton("继续") { _, _ -> clearMemoryFinal() }
            .show()
    }

    private fun clearMemoryFinal() {
        AlertDialog.Builder(this)
            .setTitle("最后确认")
            .setMessage("清空后不可恢复（印象里那些「上次说的那个项目」会全部消失）。真的要清吗？")
            .setNegativeButton("取消", null)
            .setPositiveButton("清空") { _, _ ->
                try {
                    MemoryCore(this).clearAll()
                    toast("记忆已清空")
                } catch (t: Throwable) {
                    toast("清空失败：" + (t.message ?: t.javaClass.simpleName))
                }
                refreshStatus()
            }
            .show()
    }

    private fun refreshStatus() {
        val cfg = AiConfig.load(this)
        val on = Prefs.aiOn(this)
        val state = when {
            !on -> "AI：关闭（只用本地台词）"
            !cfg.configured -> "AI：开着，但没填 Key —— 等同关闭"
            else -> "AI：启用中 · " + AiClient.endpoint(cfg.apiBase)
        }
        val mem = try {
            val m = MemoryCore(this)
            "记忆：印象 " + m.imps.size() + " 条（归档 " + m.imps.archiveSize() + " 条）· 事件 " +
                m.events.count() + " 条 · 好感 " + m.relation.affinity + "/100"
        } catch (t: Throwable) {
            "记忆：读取失败"
        }
        tvStatus.text = state + "\n" + mem
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
