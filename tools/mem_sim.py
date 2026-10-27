#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
mem_sim.py —— 把 ai/ 下四个「纯函数」用 Python 复刻一遍，灌 1000 条模拟事件跑通，
用真实数字回答三个问题：

  1. 衰减曲线长什么样（半衰期 30 天是否合理）
  2. 「会串」的检索 top-4 到底能不能命中真正相关的那条印象
  3. 去重阈值 0.6 会不会把不相干的两件事误合并

对应 Kotlin 实现（口径必须一致，改一处要改两处）：
  EventStore.scoreEvent            → score_event()
  ImpressionStore.decay            → decay()
  ImpressionStore.scoreOf          → score_of()
  ImpressionStore.impFromSources   → imp_from_sources()   （Math.round，不是 ceil）
  ImpressionStore.forgetDaysAfter  → FORGET_DAYS_BY_IMP   （imp → 天数映射）
  ImpressionStore.shouldForget     → should_forget()
  TextSim.bigrams / overlapOf      → bigrams() / overlap_of()
  ImpressionStore.DEDUPE_THRESHOLD → DEDUPE_THRESHOLD = 0.6

跑法：python3 tools/mem_sim.py
"""

import math
import random

# ---------------------------------------------------------------- 常量（与 Kotlin 一致）

FEELING_WORDS = ["喜欢", "讨厌", "最爱", "受不了", "烦死", "开心", "难过", "生气", "害怕", "想要"]
SELF_WORDS = ["我的", "我要", "我想", "我在", "我最近"]
TIME_WORDS = ["今天", "明天", "下周", "点", "号", "元"]
POSITIVE_WORDS = ["开心", "喜欢", "高兴", "太好了", "爱", "棒", "舒服", "期待", "好玩", "满足"]
NEGATIVE_WORDS = ["难过", "烦", "生气", "讨厌", "累", "害怕", "委屈", "伤心", "哭", "崩溃", "受不了"]

PUNCT = set("，。、；：？！…—－·「」『』（）()【】[]{}<>《》\"'“”‘’,.;:?!~|/\\-_=+*&^%$#@`")

RECALL_MIN_SCORE = 5.0
DEDUPE_THRESHOLD = 0.6
PROACTIVE_COOLDOWN_DAYS = 7.0
# F5 淡忘口径（与 Kotlin `ImpressionStore.forgetDaysAfter` 同步）：
# 越重要记得越久。None = 永不遗忘。
FORGET_DAYS_BY_IMP = {5: None, 4: 180.0, 3: 90.0, 2: 30.0, 1: 7.0}

# 旧口径只留作对照（`imp*0.6 + 0.3*decay < 2.0 && 天数 > 30`），证明它在这个系统里几乎不触发
OLD_FORGET_DAYS = 30.0
OLD_FORGET_MIN_SCORE = 2.0


# ---------------------------------------------------------------- 纯函数复刻

def score_event(text, who):
    """F2：谁的哪句话值得记。她自己的话恒定 1 分，不污染主人的印象。"""
    if who == "pet":
        return 1, emo_of(text)
    s = 1
    if any(w in text for w in FEELING_WORDS):
        s += 2
    if any(w in text for w in SELF_WORDS):
        s += 1
    if any(w in text for w in TIME_WORDS) or any(ch.isdigit() for ch in text):
        s += 1
    q = text.count("?") + text.count("？")
    e = text.count("!") + text.count("！")
    if q >= 2 or e >= 2:
        s += 1
    if len(text) > 80:
        s += 1
    return max(1, min(5, s)), emo_of(text)


def emo_of(text):
    if any(w in text for w in POSITIVE_WORDS):
        return "开心"
    if any(w in text for w in NEGATIVE_WORDS):
        return "难过"
    return "平常"


def decay(days_since_last):
    """半衰期 30 天：0.5 ^ (天数 / 30)"""
    if days_since_last <= 0.0:
        return 1.0
    return math.pow(0.5, days_since_last / 30.0)


def score_of(imp, days_since_last, overlap):
    """F4 检索分（放大 10 倍口径）：imp*6 + 30*衰减 + 20*关键词重合"""
    return imp * 6.0 + 30.0 * decay(days_since_last) + 20.0 * overlap


def effective_score(imp, days_since_last):
    """旧 F5 淡忘尺子（0..3.4）：imp*0.6 + 0.3*衰减 —— 只留作对照，正式口径已换成天数映射"""
    return imp * 0.6 + 0.3 * decay(days_since_last)


def forget_days_after(imp):
    """F5：imp → 多久没提就淡忘（天数）；None = 永不遗忘"""
    return FORGET_DAYS_BY_IMP.get(max(1, min(5, imp)), 7.0)


def should_forget(imp, days_since_last):
    """F5：直接按 imp 查天数，不再用「有效分 < 阈值」那套（那套 imp>=4 永不触发）"""
    limit = forget_days_after(imp)
    if limit is None:
        return False
    return days_since_last > limit


def old_should_forget(imp, days_since_last):
    """对照用：旧的 effectiveScore + 阈值口径"""
    return days_since_last > OLD_FORGET_DAYS and effective_score(imp, days_since_last) < OLD_FORGET_MIN_SCORE


def imp_from_sources(imps):
    """F3：新印象的 imp = 来源事件 imp 均值的四舍五入（对齐 Kotlin `Math.round`）

    曾经是 ceil —— 来源本来就筛过 imp>=3，均值 3.x 一向上取整全变 4，
    于是印象永远停在 imp=4，配合旧阈值就是「什么都不会忘」。
    """
    if not imps:
        return 1
    # Python 的 round() 是银行家舍入（3.5 → 4 但 2.5 → 2），Java 的 Math.round 是 floor(x+0.5)，
    # 这里显式写成 floor(mean + 0.5) 才能和 Kotlin 逐位一致。
    return max(1, min(5, int(math.floor(sum(imps) / len(imps) + 0.5))))


def _is_word_char(c):
    return (not c.isspace()) and c not in PUNCT


def bigrams(s):
    """字级 2-gram；单字退化为单字集合"""
    clean = "".join(c for c in s if _is_word_char(c))
    if not clean:
        return set()
    if len(clean) == 1:
        return {clean}
    return {clean[i:i + 2] for i in range(len(clean) - 1)}


def overlap_of(a, b):
    """Jaccard：|A∩B| / |A∪B|"""
    if not a or not b:
        return 0.0
    inter = len(a & b)
    union = len(a) + len(b) - inter
    if union <= 0:
        return 0.0
    return inter / union


def overlap(a, b):
    return overlap_of(bigrams(a), bigrams(b))


# ---------------------------------------------------------------- 模拟数据

# 40 个「话题」，每个话题一组内容词。同一话题的不同说法共享词，不同话题尽量不共享，
# 这样才能分别测「该合并的合并了吗」和「不该合并的有没有被误合并」。
TOPIC_WORDS = [
    ["项目", "上线", "加班"], ["猫咪", "疫苗", "兽医"], ["健身", "深蹲", "腰疼"],
    ["咖啡", "拿铁", "失眠"], ["考试", "复习", "焦虑"], ["搬家", "纸箱", "腰酸"],
    ["代码", "重构", "报错"], ["旅行", "机票", "酒店"], ["吉他", "和弦", "练习"],
    ["火锅", "毛肚", "排队"], ["房租", "涨价", "合同"], ["跑步", "配速", "膝盖"],
    ["论文", "查重", "导师"], ["相机", "镜头", "夜景"], ["牙医", "补牙", "麻药"],
    ["面试", "简历", "offer"], ["菜谱", "番茄", "炖牛肉"], ["地铁", "早高峰", "挤"],
    ["雨伞", "忘带", "淋湿"], ["牛奶", "过期", "冰箱"], ["瑜伽", "拉伸", "呼吸"],
    ["显示器", "护眼", "色准"], ["生日", "蛋糕", "蜡烛"], ["洗衣机", "漏水", "维修"],
    ["股票", "跌了", "心态"], ["剧本杀", "推理", "凶手"], ["滑雪", "摔跤", "护具"],
    ["养鱼", "换水", "水草"], ["记账", "预算", "超支"], ["颈椎", "按摩", "僵硬"],
    ["演唱会", "抢票", "黄牛"], ["做饭", "糊锅", "外卖"], ["护照", "签证", "材料"],
    ["手办", "到货", "快递"], ["感冒", "嗓子", "喝水"], ["钢琴", "考级", "练习曲"],
    ["露营", "帐篷", "蚊虫"], ["摄影", "修图", "调色"], ["狗粮", "遛狗", "打疫苗"],
    ["英语", "听力", "单词"],
]

FILLERS = ["", "啊", "呢", "了", "吧", "呀", "哦"]


def make_text(rng, words, rich=True):
    """用话题词 + 可选的情绪/第一人称/时间词拼一句话。

    注意：话题词**保持原顺序**（真实说话时「项目上线」这种复合词不会被打散），
    只有 25% 的概率会被打乱 —— 这正是 2-gram 该失手的地方，留着才能测出真实分布。
    """
    picked = rng.sample(words, rng.randint(2, min(3, len(words))))
    picked.sort(key=lambda w: words.index(w))
    if rng.random() < 0.25:
        rng.shuffle(picked)
    parts = []
    if rich and rng.random() < 0.45:
        parts.insert(0, rng.choice(SELF_WORDS))
    parts.extend(picked)
    if rich and rng.random() < 0.35:
        parts.append(rng.choice(FEELING_WORDS))
    if rich and rng.random() < 0.3:
        parts.append(rng.choice(TIME_WORDS))
    return "".join(parts) + rng.choice(FILLERS)


def make_impression(rng, words):
    """话题的「印象」：模型提炼出的规范化短句（取前两个词 + 稳定后缀）"""
    return words[0] + words[1] + "的事"


def simulate(n_events=1000, seed=20251028):
    rng = random.Random(seed)
    now = 180 * 86400  # 以「秒」为单位的当前时刻
    span = 180 * 86400  # 事件铺满过去 180 天

    # ---- 1) 造 1000 条事件，每条带 ground-truth 话题
    # 话题是「一阵一阵」的：同时只有 6 个话题在活跃，每 60 条事件换掉 3 个。
    # 这样印象的 last 会自然散在 180 天里（而不是全都新鲜），衰减才有意义。
    events = []
    active = rng.sample(range(len(TOPIC_WORDS)), 6)
    idle = [i for i in range(len(TOPIC_WORDS)) if i not in active]
    for i in range(n_events):
        if i > 0 and i % 60 == 0:
            rng.shuffle(active)
            for _ in range(3):
                if active and idle:
                    active.pop()
                    active.append(idle.pop())
        ti = rng.choice(active)
        t = int(span * i / n_events) + rng.randrange(60)
        # 六成是「有信息量」的话（带第一人称/情绪/时间词），四成是随口一句 —— 这样
        # imp 才会真的散在 1..5，而不是全挤在 4-5（全挤在高分的话淡忘永远不会触发）
        text = make_text(rng, TOPIC_WORDS[ti], rich=rng.random() < 0.6)
        imp, emo = score_event(text, "user")
        events.append({"t": t, "topic": ti, "text": text, "imp": imp, "emo": emo})

    # 事件里 imp>=3 的才参与提炼（F3 的门槛），每个话题聚成一条印象
    imp_events = [e for e in events if e["imp"] >= 3]

    impressions = {}  # topic -> {text, imp, mentions, last, born}
    for e in imp_events:
        ti = e["topic"]
        cur = impressions.get(ti)
        if cur is None:
            impressions[ti] = {
                "text": make_impression(rng, TOPIC_WORDS[ti]),
                "imp": imp_from_sources([e["imp"]]),
                # 旧口径的 imp（ceil），只为了在 [4] 里做 A/B 对照，不参与其他计算
                "imp_ceil": e["imp"],
                "src_imp": [e["imp"]],
                "mentions": 1,
                "last": e["t"],
                "born": e["t"],
            }
        else:
            cur["mentions"] += 1
            cur["last"] = max(cur["last"], e["t"])
            cur["src_imp"].append(e["imp"])
            # 上次提炼的 imp 与这批来源一起重算 —— 对齐 impFromSources 的「来源均值 round」
            cur["imp"] = imp_from_sources(cur["src_imp"])
            # 旧口径逐字复刻（A/B 对照用）：逐条配对平均后向上取整
            cur["imp_ceil"] = max(1, min(5, int(math.ceil((cur["imp_ceil"] + e["imp"]) / 2))))

    imp_list = list(impressions.items())

    # ---- 2) 检索 top-4 命中率
    # 拿事件当「用户刚说的话」，看真正相关的那条印象有没有进 top-4。
    # 只统计「这个话题确实存下过印象」的提问 —— 没存过的，检索再准也命中不了，
    # 把它算进去是在测噪声而不是测算法。
    imp_topics = {ti for ti, _ in imp_list}
    queries = [e for e in rng.sample(events, 400) if e["topic"] in imp_topics][:200]
    hits = 0
    scored_any = 0
    for q in queries:
        qb = bigrams(q["text"])
        scored = []
        for ti, x in imp_list:
            d = max(0.0, (now - x["last"]) / 86400.0)
            s = score_of(x["imp"], d, overlap_of(qb, bigrams(x["text"])))
            scored.append((s, ti))
        scored.sort(key=lambda p: -p[0])
        top4 = [ti for s, ti in scored[:4] if s >= RECALL_MIN_SCORE]
        if top4:
            scored_any += 1
        if q["topic"] in top4:
            hits += 1
    hit_rate = hits / len(queries)

    # ---- 3) 去重误合并率
    # 3a. 不该合并的：不同话题的印象两两算重叠率，超过 0.6 就是误合并
    false_merge = 0
    pairs = 0
    for i in range(len(imp_list)):
        for j in range(i + 1, len(imp_list)):
            pairs += 1
            if overlap(imp_list[i][1]["text"], imp_list[j][1]["text"]) > DEDUPE_THRESHOLD:
                false_merge += 1
    false_merge_rate = false_merge / pairs if pairs else 0.0

    # 3b. 该合并的：同一件事、模型两次提炼措辞略有不同，重叠率是否过 0.6
    # 现实里模型对同一件事会换个说法（后缀不同 / 换掉一个词），所以三种变体都造：
    #   · 只差后缀（几乎重复）      → 应该合并
    #   · 换掉一个词（同义改写）    → 合并与否都能接受
    true_pairs = 0
    true_merge = 0
    suffix_pairs = 0
    suffix_merge = 0
    for words in TOPIC_WORDS:
        base = words[0] + words[1]
        variants = [
            base + "的事",       # 只差后缀
            base + "这件事",     # 只差后缀
            base + "那次",       # 只差后缀
            words[0] + words[2] + "的事",  # 换掉一个词
        ]
        for i in range(len(variants)):
            for j in range(i + 1, len(variants)):
                true_pairs += 1
                hit = overlap(variants[i], variants[j]) > DEDUPE_THRESHOLD
                if hit:
                    true_merge += 1
                if i < 3 and j < 3:  # 两个都是「只差后缀」
                    suffix_pairs += 1
                    if hit:
                        suffix_merge += 1
    true_merge_rate = true_merge / true_pairs if true_pairs else 0.0
    suffix_merge_rate = suffix_merge / suffix_pairs if suffix_pairs else 0.0

    # 3c. 近乎重复（模型两次提炼只差一个语气词/标点）：这种必须合并，否则印象会无限堆积
    near_pairs = 0
    near_merge = 0
    for words in TOPIC_WORDS:
        base = words[0] + words[1] + "的事"
        for tail in ["了", "啊", "呀", "吧", "！"]:
            near_pairs += 1
            if overlap(base, base + tail) > DEDUPE_THRESHOLD:
                near_merge += 1
    near_merge_rate = near_merge / near_pairs if near_pairs else 0.0

    # ---- 4) 淡忘：按 F5 新口径（imp → 天数映射）跑一遍，看 180 天后能忘掉几条
    # 同时用旧口径（effectiveScore < 2.0）算一遍作对照，把「旧口径几乎不触发」钉在数字上
    forgotten = 0
    forgotten_old = 0
    min_eff = 99.0
    forgotten_by_imp = {}
    for ti, x in imp_list:
        d = max(0.0, (now - x["last"]) / 86400.0)
        if should_forget(x["imp"], d):
            forgotten += 1
            forgotten_by_imp[x["imp"]] = forgotten_by_imp.get(x["imp"], 0) + 1
        # 旧口径 = 旧的 imp（ceil）+ 旧公式，两个变量一起还原成改之前的样子
        if old_should_forget(x["imp_ceil"], d):
            forgotten_old += 1
        min_eff = min(min_eff, effective_score(x["imp_ceil"], d))

    # ---- 5) 主动开口候选数（F8：imp>=3 且 7 天没提过）
    proactive = 0
    for ti, x in imp_list:
        d = max(0.0, (now - x["last"]) / 86400.0)
        if x["imp"] >= 3 and d > PROACTIVE_COOLDOWN_DAYS:
            proactive += 1

    return {
        "events": len(events),
        "imp_events": len(imp_events),
        "impressions": len(imp_list),
        "hit_rate": hit_rate,
        "scored_any": scored_any / len(queries),
        "false_merge_rate": false_merge_rate,
        "true_merge_rate": true_merge_rate,
        "suffix_merge_rate": suffix_merge_rate,
        "near_merge_rate": near_merge_rate,
        "forgotten": forgotten,
        "forgotten_old": forgotten_old,
        "forgotten_by_imp": forgotten_by_imp,
        "min_effective_score": min_eff,
        "imp_dist": {k: sum(1 for _, x in imp_list if x["imp"] == k) for k in range(1, 6)},
        "imp_dist_old": {k: sum(1 for _, x in imp_list if x["imp_ceil"] == k) for k in range(1, 6)},
        "proactive": proactive,
    }


def main():
    r = simulate()

    print("=" * 66)
    print("mem_sim.py —— AI 记忆层四个纯函数的真实跑数（1000 条模拟事件）")
    print("=" * 66)
    print(f"事件 {r['events']} 条 → 其中 imp>=3 的 {r['imp_events']} 条 → 提炼出印象 {r['impressions']} 条")

    print("\n[1] 衰减曲线（半衰期 30 天，decay = 0.5^(天/30)）")
    samples = [0, 7, 30, 90, 180]
    vals = [decay(d) for d in samples]
    for d, v in zip(samples, vals):
        print(f"    {d:>3} 天 → {v:.4f}   检索分里贡献 {30 * v:5.2f} 分 / 30")

    print("\n[2] 检索 top-4 命中率（F4：imp*6 + 30*衰减 + 20*2gram重叠）")
    print(f"    命中率 {r['hit_rate'] * 100:.1f}%（200 次随机提问里 {int(round(r['hit_rate'] * 200))} 次"
          f"真正相关的那条印象进了 top-4）")
    baseline = min(1.0, 4.0 / max(1, r["impressions"]))
    print(f"    有返回率 {r['scored_any'] * 100:.1f}%（过 RECALL_MIN_SCORE=5.0 的比例）")
    print(f"    随机基线 {baseline * 100:.1f}%（{r['impressions']} 条印象里瞎取 4 条），"
          f"实测是它的 {r['hit_rate'] / baseline:.1f} 倍")

    print("\n[3] 去重（阈值 0.6，Jaccard 2-gram）")
    print(f"    误合并率 {r['false_merge_rate'] * 100:.1f}%  ← 不同话题被并成同一条（越低越好）")
    print(f"    近乎重复合并率 {r['near_merge_rate'] * 100:.1f}%  ← 只多一个语气词，必须合并")
    print(f"    改写后合并率 {r['true_merge_rate'] * 100:.1f}%  ← 同一件事换了说法")
    print("    说明：0.6 这个阈值对短句偏严，改写过的同一件事会被当成两条。")
    print("          这是有意的取舍 —— 宁可留两条重复，也不要把两件事糊成一条。")

    print("\n[4] 淡忘与主动开口（180 天后，口径 = imp → 天数映射）")
    n_imp = max(1, r["impressions"])
    print("    口径：imp5 永不遗忘 / imp4 >180 天 / imp3 >90 天 / imp2 >30 天 / imp1 >7 天")
    print(f"    淡忘 {r['forgotten']} 条 / 共 {r['impressions']} 条"
          f"（{r['forgotten'] / n_imp * 100:.1f}%）")
    dist = " ".join(f"imp{k}:{v}" for k, v in r["imp_dist"].items() if v)
    print(f"    印象重要性分布 {dist}")
    by_imp = " ".join(f"imp{k}:{v}" for k, v in sorted(r["forgotten_by_imp"].items()))
    print(f"    淡忘明细 {by_imp if by_imp else '（无）'}")
    dist_old = " ".join(f"imp{k}:{v}" for k, v in r["imp_dist_old"].items() if v)
    print(f"    改前对照（imp=ceil 配对平均 + effectiveScore<{OLD_FORGET_MIN_SCORE} 且 >"
          f"{OLD_FORGET_DAYS:.0f} 天）：淡忘 {r['forgotten_old']} 条"
          f"（{r['forgotten_old'] / n_imp * 100:.1f}%），imp 分布 {dist_old}，"
          f"最低有效分 {r['min_effective_score']:.3f} > 阈值 → imp>=4 永不触发")
    print(f"    → 改后 {r['forgotten']} 条 vs 改前 {r['forgotten_old']} 条")
    print(f"    主动开口候选 {r['proactive']} 条（imp>=3 且 7 天没提过）")

    print("\n结论：")
    print(f"  · 衰减 30 天砍半、90 天剩 1/8 —— 「上个月的事」还模糊记得，「半年前的事」基本忘光。")
    print(f"  · 检索命中率 {r['hit_rate'] * 100:.1f}%，说明按分数取而不是按时间取，确实串得起来。")
    print(f"  · 误合并 {r['false_merge_rate'] * 100:.1f}%：0.6 的阈值不会把不相干的事糊在一起；")
    print(f"    代价是改写过的重复会留下两条，靠 F5 淡忘（按 imp 天数归档）慢慢清掉。")
    print(f"  · 淡忘 {r['forgotten'] / n_imp * 100:.1f}%（旧口径 {r['forgotten_old'] / n_imp * 100:.1f}%）"
          f"—— impFromSources 改 round、阈值改 imp→天数后，「会忘」才真的会忘。")
    print("=" * 66)


if __name__ == "__main__":
    main()
