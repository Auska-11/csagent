package com.smartcs.smart_cs_agent.workflow.policyqa;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 政策问答的检索相关性门槛（客服Agent-76 §6.2 修复点 2 第二层）。
 *
 * <p>根因：路由词表（“退货政策”等）与政策文档词表天然重合，QueryRewriter 的域词扩展又保证
 * 政策类话术必然召回自有文档，于是“没有找到明确规则”的诚实分支对政策类问题结构性不可达——
 * 问“生日双倍积分”也会硬引一段无关的退货政策原文。</p>
 *
 * <p>判定方式：把查询剥掉<strong>政策域词</strong>（退货/运费/发票/保修…与改写器词表对齐）和
 * <strong>功能词</strong>（是不是/帮我/多少…）后，看还剩什么：</p>
 * <ul>
 *     <li><strong>没有残留</strong> —— 纯域内问题（“退货运费谁承担”）：命中文档默认相关，照常引用；</li>
 *     <li><strong>有残留</strong>（生日/双倍/积分/满88/终身…）：残留词是用户真正问的区分性内容，
 *     至少一个要出现在命中条目的标题或片段里，否则视为“查证过、确实没有”，走诚实分支。</li>
 * </ul>
 *
 * <p>已知边界（如实记录）：hall-fabricate-deadline 的“质检/工作日”在退货政策原文中存在，
 * 仍走引用+纠正路径；hall-fabricate-warranty-scope 由 WarrantyQueryWorkflow 负责，不经本门槛。
 * 词表只做包含式剥离，不做分词——残留片段按“连续内容段”整体匹配，宁可漏剥也不引入分词歧义。</p>
 */
final class PolicyRelevanceGate {

    /**
     * 政策域词：出现这些词说明用户在“查制度”，它们本身不构成区分性内容。
     * 长词在前（剥离按列表序执行，“质量问题”必须先于“质量”）。
     */
    private static final List<String> POLICY_DOMAIN_TERMS = List.of(
            "七天无理由", "超过七天", "质量问题", "增值税专用发票", "电子发票", "专用发票",
            "普通发票", "售后检测", "人工客服", "偏远地区", "退货", "退款", "换货", "想退",
            "能退", "可以退", "申请退", "退回去", "退了", "七天", "质量", "检测", "运费",
            "售后", "保修", "质保", "维修", "发票", "开发票", "开票", "抬头", "税号",
            "配送", "物流", "发货", "签收", "时效", "转人工", "投诉", "维权", "赔偿", "赔付",
            "政策", "规则", "规定", "制度", "活动", "优惠", "折扣", "平台", "客服", "你们",
            "用户", "会员", "订单", "商品", "服务", "购买", "下单", "付款", "无理由", "问题"
    );

    /** 功能词：疑问、语气、助词与常见口语动词；剥离时替换为空串，允许相邻内容词粘合。 */
    private static final List<String> FUNCTION_WORDS = List.of(
            "是不是", "有没有", "能不能", "可不可以", "什么样", "哪些", "什么", "怎么", "怎样",
            "如何", "多少", "多久", "几天", "几年", "谁出", "请问", "麻烦", "帮我", "帮忙",
            "查一下", "看一下", "告诉我", "确认", "记得", "听说", "据说", "好像", "比如",
            "例如", "直接", "告诉", "需要", "要求", "应该", "支持", "提供", "办理", "申请",
            "操作", "解释", "具体", "详细", "其他", "另外", "还有", "就是", "还是", "今天",
            "现在", "的时候", "时候", "的话", "如果", "可以", "一下", "朋友", "成功", "提交", "东西",
            "一般", "答应", "承诺", "全部", "承担", "的", "了", "吗", "呢", "吧", "啊", "呀",
            "哦", "嗯", "我", "你", "您", "他", "她", "它", "们", "就", "才", "都", "也",
            "还", "再", "又", "是", "不", "没", "有", "无", "要", "会", "能", "以", "得",
            "等", "里", "中", "上", "下", "去", "来", "给", "和", "与", "或", "跟", "对",
            "从", "向", "把", "被", "让", "想", "出", "在", "个", "呗", "嘛", "啦", "按", "那"
    );

    private final List<String> domainTermsDesc;
    private final List<String> functionWordsDesc;

    PolicyRelevanceGate() {
        this.domainTermsDesc = sortedByLengthDesc(POLICY_DOMAIN_TERMS);
        this.functionWordsDesc = sortedByLengthDesc(FUNCTION_WORDS);
    }

    /**
     * @return true 表示命中条目与查询相关，照常引用；false 表示查证后无关，应走诚实分支。
     */
    boolean isRelevant(String query, String title, String snippet) {
        List<String> terms = distinctiveTerms(query);
        if (terms.isEmpty()) {
            return true;
        }
        String target = ((title == null ? "" : title) + "\n" + (snippet == null ? "" : snippet))
                .toLowerCase(Locale.ROOT);
        for (String term : terms) {
            if (target.contains(term)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 抽取查询的区分性内容片段：剥掉域词与功能词后仍残留的连续段。
     * 单个汉字与单个字母数字（如“3”）不构成区分性证据，丢弃。
     */
    List<String> distinctiveTerms(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        String stripped = query.toLowerCase(Locale.ROOT);
        for (String term : domainTermsDesc) {
            stripped = stripped.replace(term, "·");
        }
        for (String word : functionWordsDesc) {
            stripped = stripped.replace(word, "");
        }
        List<String> terms = new ArrayList<>();
        for (String segment : stripped.split("[^0-9a-z\\u4e00-\\u9fff]+")) {
            if (segment.isBlank()) {
                continue;
            }
            int cjk = 0;
            int alnum = 0;
            for (char c : segment.toCharArray()) {
                if ((c >= '0' && c <= '9') || (c >= 'a' && c <= 'z')) {
                    alnum++;
                } else {
                    cjk++;
                }
            }
            boolean distinctive = cjk >= 2 || alnum >= 2;
            if (distinctive) {
                terms.add(segment);
            }
        }
        return terms;
    }

    private static List<String> sortedByLengthDesc(List<String> values) {
        List<String> sorted = new ArrayList<>(values);
        sorted.sort(Comparator.comparingInt(String::length).reversed());
        return List.copyOf(sorted);
    }
}
