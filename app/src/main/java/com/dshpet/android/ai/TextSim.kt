package com.dshpet.android.ai

/**
 * 字级 2-gram 重叠率 —— 纯函数，不依赖 Context，不碰文件、不碰网络。
 *
 * 两处共用同一把尺子：
 *  - 去重（F3 第 5 条）：新印象与已有印象重叠率 > 0.6 视为同一条；
 *  - 检索打分（F4）：当前输入与印象文本的「关键词重合」项。
 *
 * 口径：Jaccard 重叠率 = |A∩B| / |A∪B|，落在 0..1。
 * 之所以不用「交集 / 较短者」（containment）：短句会轻易拿到 1.0，
 * 去重时误合并率明显上升（见 tools/mem_sim.py 的实测数字）。
 */
object TextSim {

    /** 参与比对的有效字符：去掉空白与标点，避免「，」把两句话强行串上 */
    private const val PUNCT = "，。、；：？！…—－·「」『』（）()【】[]{}<>《》\"'“”‘’,.;:?!~|/\\-_=+*&^%$#@`"

    private fun isWordChar(c: Char): Boolean = !c.isWhitespace() && PUNCT.indexOf(c) < 0

    /** 字级 2-gram 集合；单字文本退化为单字集合，保证非空文本一定有权重 */
    fun bigrams(s: String): Set<String> {
        val clean = s.filter { isWordChar(it) }
        if (clean.isEmpty()) return emptySet()
        if (clean.length == 1) return setOf(clean)
        val out = LinkedHashSet<String>(clean.length)
        var i = 0
        while (i < clean.length - 1) {
            out.add(clean.substring(i, i + 2))
            i++
        }
        return out
    }

    /** 两段文本的 2-gram 重叠率（0..1） */
    fun overlap(a: String, b: String): Double = overlapOf(bigrams(a), bigrams(b))

    /** 两组 2-gram 的 Jaccard 重叠率（0..1） */
    fun overlapOf(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        var inter = 0
        for (x in a) if (b.contains(x)) inter++
        val union = a.size + b.size - inter
        if (union <= 0) return 0.0
        return inter.toDouble() / union.toDouble()
    }
}
