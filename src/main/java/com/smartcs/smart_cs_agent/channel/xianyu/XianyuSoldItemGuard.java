package com.smartcs.smart_cs_agent.channel.xianyu;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 已售商品的“推进交易意图”守卫：库存已知为 0 时，买家的购买、议价、成交确认诉求
 * 由代码直接告知已售，不再报价、不引导确认成交。
 *
 * <p>为什么需要代码层判断：意图路由可能在议价、商品咨询、通用兜底之间飘移，
 * “库存 0 件”只作为事实写进 Prompt 时，模型可能翻译成“价格需要卖家确认”——
 * 买家会误以为还能购买。BargainWorkflow 已有库存 0 硬拦截；本守卫让商品咨询与
 * 通用兜底两条路由同口径。</p>
 *
 * <p>只拦截推进交易的意图：商品咨询（“支持蓝牙吗”“有什么瑕疵”）、售后求助
 * （“连不上蓝牙怎么办”）和闲聊照常走原流程——已售不等于对所有消息一律回已售。</p>
 *
 * <p>意图判定采用分层裁决，而不是“整句含售后词就一票否决”：
 * ① 先剥离“没有问题/没坏”这类否定故障表达——“没有问题我就要了”里嵌着的
 * “有问题”不是在报障，子串匹配会误伤购买意图；
 * ② 明确放弃购买（“不要了”“不买了”）与退货退款类信号仍然否决——它们本身
 * 就是交易的反方向；
 * ③ 问价先问对象，但只认明确的服务计价表达：服务动作与价格词之间仅允许封闭
 * 连接词（“修一下要多少钱”“维修费用是多少”“寄回运费多少钱”），此外的任何文字
 * 都不匹配——“维修过的这款耳机多少钱”问的是商品售价（“过”是维修历史），
 * “维修过也行我想买这件多少钱”带着独立购买信号，价格词照常拦截；剥离也只删
 * 计价表达本身，句中其余信号原样保留。“保修”不在服务动词表——“有保修吗？
 * 多少钱”的问价从句独立，问的仍是商品；
 * ④ 保修、维修、故障排查等政策/状态词不享有否决权——“有保修吗？多少钱”
 * 这类带明确问价/购买意图的消息按推进交易处理；反之，不含交易信号的消息
 * 本方法返回 false，售后求助、政策咨询自然交回原流程，不需要靠售后词表拦截。</p>
 */
public final class XianyuSoldItemGuard {

    /** 与 BargainWorkflow 同一句售出话术，三条路由对买家口径一致。 */
    public static final String SOLD_REPLY =
            "抱歉，这件商品刚刚售出了。您可以看看卖家主页还有没有类似的宝贝。";

    /** 否定故障表达：整体剥离后再做信号判定，避免“没有问题”被子串匹配成“有问题”。 */
    private static final Pattern NEGATED_FAULT_EXPRESSION = Pattern.compile(
            "没(有|什么|啥)?(问题|毛病|坏|故障)");

    /** 售后服务动作（正向语序全集）。裸“修”用 (?<![保包]) 排除“保修/包修”里的“修”。 */
    private static final String REMEDIATION_ACTIONS =
            "(?:维修|检修|返修|退回|寄回|邮回|检测|换货|换|(?<![保包])修)";

    /** 反向语序（“多少钱能修好”）只认维修类动作：“多少钱换个好点的”是升级换购，
     *  属于新交易，不能当服务计价剥离。 */
    private static final String REPAIR_ACTIONS =
            "(?:维修|检修|返修|检测|(?<![保包])修)";

    /** 问价词：服务计价表达与商品问价共用同一组价格词。 */
    private static final String PRICE_WORDS =
            "(?:多少钱|多少费用|多少|费用|收费|价钱)";

    /**
     * 服务动作与价格词之间允许出现的封闭连接词：“修一下要多少钱”“维修费用是
     * 多少”“寄回运费多少钱”“维修的话多少钱”都能连上。除此之外的任何文字——
     * 维修历史的“过”、定语的“的”、商品名（“这款耳机”）、购买意图（“我想买”）——
     * 都不允许出现在服务动作与价格词之间，杜绝“维修……多少钱”之间任意文字被
     * 整段吞掉（八轮评审复现：“维修过的这款耳机多少钱”“维修过也行我想买这件
     * 多少钱”都被误放行）。
     */
    private static final String REMEDIATION_GLUE =
            "(?:一下|一个|费用|运费|邮费|的话|下|个|好|费)?(?:需要|大概|大约|要|得|是|约|收)*";

    /**
     * 明确的服务计价表达：“维修多少钱”“修一下要多少钱”“多少钱能修好”。只删除
     * 命中的计价表达本身，句中其余部分原样保留——“维修多少钱？这件还在吗”里
     * “还在吗”照常触发拦截；反向语序只允许 能/才/可以 连接。
     */
    private static final Pattern REMEDIATION_PRICE_QUESTION = Pattern.compile(
            REMEDIATION_ACTIONS + REMEDIATION_GLUE + PRICE_WORDS
                    + "|" + PRICE_WORDS + "(?:可以|能|才)?" + REPAIR_ACTIONS + "(?:一下|好|个)?");

    /** 明确放弃购买的信号：出现即不算推进交易，交回原流程处理。 */
    private static final List<String> DECLINE_SIGNALS = List.of(
            "不要了", "不买了", "不想要", "不拍了"
    );

    /** 退货/退款类信号：交易的反方向（售后资金诉求），交回原流程处理。 */
    private static final List<String> RETURN_SIGNALS = List.of(
            "退货", "退款", "退换", "退一下"
    );

    /** 购买与成交确认信号：问价也算——已售商品不再报价，报挂牌价会引导购买。 */
    private static final List<String> PURCHASE_SIGNALS = List.of(
            "还在吗", "还有吗", "还有货", "有货吗", "卖了吗", "卖了没", "卖不卖", "在售",
            "出吗", "出不出", "想要", "想买", "要买", "能买", "可以买", "怎么买", "购买",
            "拍了", "拍下", "已拍", "能拍", "怎么拍", "拍吗", "下单", "付款", "付钱",
            "多少钱", "怎么卖", "什么价", "啥价", "价位", "价格", "几块", "库存", "有货",
            "成交", "要了", "拿下", "搞定了", "就这个价", "留着"
    );

    /** 议价信号：与 BargainWorkflow 的路由词表同源。“最低”必须带价格语境
     * （最低多少/最低价/最低能），否则“音量调到最低还是没声音”这类故障描述
     * 会被当成砍价——原先靠售后词一票否决兜着，否决权删除后由锚定保证。 */
    private static final List<String> BARGAIN_SIGNALS = List.of(
            "便宜", "砍价", "议价", "最低多少", "最低价", "最低能", "底价", "让点",
            "让一点", "再让", "优惠", "打折", "折扣", "少点", "少一点", "包邮",
            "免运费", "包个邮", "运费", "邮费", "降价", "降点"
    );

    /** 动词锚定的出价信号（“250卖吗”“出260”“220怎么样”“250可以成交”）。 */
    private static final List<Pattern> OFFER_SIGNALS = List.of(
            Pattern.compile("\\d+(?:\\.\\d+)?\\s*(?:块|元)?\\s*(?:出|卖|拿走|收)"),
            Pattern.compile("(?:出|给|降到|讲到|谈到)\\s*\\d+(?:\\.\\d+)?"),
            Pattern.compile("\\d+(?:\\.\\d+)?\\s*(?:块|元)?\\s*"
                    + "(?:怎么样|咋样|可以|行不|行么|好吗|能接受|接受吗|能卖|肯卖|卖吗|出吗)")
    );

    /** 纯数字消息在当前商品语境下按出价处理（与 BargainWorkflow 路由口径一致）。 */
    private static final Pattern BARE_OFFER = Pattern.compile("^\\d+(?:\\.\\d+)?$");

    /**
     * 这条消息是否在推进这件商品的交易（购买、议价、问价、成交确认）。
     * 已售商品命中时调用方应直接回复 {@link #SOLD_REPLY}，不再走 LLM。
     */
    public static boolean isDealAdvancingIntent(String rawMessage) {
        if (rawMessage == null) {
            return false;
        }
        String message = rawMessage.trim().toLowerCase(Locale.ROOT);
        if (message.isEmpty()) {
            return false;
        }
        // 否定故障表达与服务计价问句先剥离，再做否决与交易信号判定。
        String work = NEGATED_FAULT_EXPRESSION.matcher(message).replaceAll("");
        final String stripped = REMEDIATION_PRICE_QUESTION.matcher(work).replaceAll("");
        if (DECLINE_SIGNALS.stream().anyMatch(stripped::contains)) {
            return false;
        }
        if (RETURN_SIGNALS.stream().anyMatch(stripped::contains)) {
            return false;
        }
        if (PURCHASE_SIGNALS.stream().anyMatch(stripped::contains)
                || BARGAIN_SIGNALS.stream().anyMatch(stripped::contains)) {
            return true;
        }
        if (BARE_OFFER.matcher(stripped).matches()) {
            return true;
        }
        return OFFER_SIGNALS.stream().anyMatch(pattern -> pattern.matcher(stripped).find());
    }

    /** 当前商品事实是否已明确售出（库存已知为 0）。 */
    public static boolean isSoldOut(XianyuItemFactService.ItemFacts facts) {
        return facts != null && facts.stockKnown() && !facts.inStock();
    }

    private XianyuSoldItemGuard() {
    }
}
