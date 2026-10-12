package com.smartcs.smart_cs_agent.workflow.bargain;

import com.smartcs.smart_cs_agent.agent.action.AskUserAction;
import com.smartcs.smart_cs_agent.agent.action.CancelWorkflowAction;
import com.smartcs.smart_cs_agent.agent.action.FinalAnswerAction;
import com.smartcs.smart_cs_agent.agent.action.HandoffAction;
import com.smartcs.smart_cs_agent.agent.context.TurnContext;
import com.smartcs.smart_cs_agent.channel.xianyu.XianyuItemFactService;
import com.smartcs.smart_cs_agent.channel.xianyu.XianyuItemFactService.ItemFacts;
import com.smartcs.smart_cs_agent.channel.xianyu.XianyuSellerRuleService;
import com.smartcs.smart_cs_agent.channel.xianyu.XianyuScenarioProperties.SellerRules;
import com.smartcs.smart_cs_agent.skill.CustomerServiceIntentRules;
import com.smartcs.smart_cs_agent.workflow.api.AgentWorkflow;
import com.smartcs.smart_cs_agent.workflow.api.RoutingContext;
import com.smartcs.smart_cs_agent.workflow.api.WorkflowDecision;
import com.smartcs.smart_cs_agent.workflow.api.WorkflowDescriptor;
import com.smartcs.smart_cs_agent.workflow.api.WorkflowMatchResult;
import com.smartcs.smart_cs_agent.workflow.core.GlobalInterruptDecision;
import com.smartcs.smart_cs_agent.workflow.core.GlobalInterruptPolicy;
import com.smartcs.smart_cs_agent.workflow.core.GlobalInterruptType;
import com.smartcs.smart_cs_agent.workflow.core.HandoffSummarySupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 受约束的多轮议价 Workflow（仅闲鱼会话）。
 *
 * <p>分工边界是本类存在的理由：<strong>报价、承诺与最终发给买家的话术全部由代码按
 * 卖家授权规则（smartcs.xianyu.scenario.seller-rules，可按商品用 item-rules 覆盖）
 * 确定性生成，不经模型措辞。</strong>模型曾负责“把结论说成人话”，但离线复现证明
 * 失控的模型输出（更低价格、“已改价/包邮”承诺）会原样到达买家，越过代码授权；
 * 交易承诺没有“措辞自由”可言，因此本 Workflow 的全部买家可见文案都是固定模板。</p>
 *
 * <p>多轮连续性：报价轮以 AskUserAction 收尾并保持 WAITING_USER 粘性，买家最近出价
 * 与我方最近一次有效报价保存在 {@link BargainState}（lastQuote 另有 taskState 镜像，
 * 暂停/完成清空命名空间后依然有效）。上一口价是当前有效承诺：买家继续砍价时重申，
 * 绝不改口报更高的价，也绝不按高于上一口价的价格成交。对话里的报价只是协商口径，
 * 最终成交价以拍下/改价后的订单为准，不等于修改平台挂牌价。</p>
 *
 * <p>不该成交就不成交，确认成交与首次报价过同一套校验：已知无货直接终态告知；
 * 商品事实未同步（价格未知）时不得凭全局底线价接受出价；买家简短确认时，库存、
 * 事实与授权任一失效都不按旧价收尾，一律暂停机器人转卖家本人确认（不建工单）。
 * 未授权的议价/包邮同样暂停转卖家确认，买家侧只收到固定话术，等卖家通过侧车人工
 * 模式接管。</p>
 *
 * <p>演示商城租户不进入本 Workflow（match 阶段直接 noMatch），保持原有回归行为。</p>
 */
@Component
public class BargainWorkflow implements AgentWorkflow {

    public static final String ID = "bargain";
    public static final String VERSION = "1.2.0";

    /** 高于商品咨询(0.97)与政策问答(0.98)，议价消息必须先于它们命中。 */
    private static final double MATCH_SCORE = 0.985;

    private final WorkflowDescriptor descriptor = new WorkflowDescriptor(
            ID,
            VERSION,
            "受约束的多轮议价与邮费协商（仅闲鱼）：报价与买家话术由代码按卖家授权确定性生成，不经模型措辞",
            170
    );

    private final CustomerServiceIntentRules intentRules = new CustomerServiceIntentRules();
    private final GlobalInterruptPolicy interruptPolicy;
    private final XianyuItemFactService xianyuItemFactService;
    private final XianyuSellerRuleService sellerRuleService;

    @Autowired
    public BargainWorkflow(GlobalInterruptPolicy interruptPolicy,
                           XianyuItemFactService xianyuItemFactService,
                           XianyuSellerRuleService sellerRuleService) {
        this.interruptPolicy = interruptPolicy;
        this.xianyuItemFactService = xianyuItemFactService;
        this.sellerRuleService = sellerRuleService;
    }

    /** 兼容旧测试装配：不注入闲鱼服务时恒为 noMatch。 */
    public BargainWorkflow(GlobalInterruptPolicy interruptPolicy) {
        this(interruptPolicy, null, null);
    }

    @Override
    public WorkflowDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public WorkflowMatchResult match(RoutingContext context) {
        String message = normalize(context.getMessage());
        if (!claimsBargainMessage(message)) {
            return WorkflowMatchResult.noMatch("未命中议价或邮费协商表达");
        }
        if (!isXianyuSession(context)) {
            return WorkflowMatchResult.noMatch("议价协商当前仅面向闲鱼会话，商城演示保持原路由");
        }
        return new WorkflowMatchResult(MATCH_SCORE, "闲鱼会话命中议价、砍价或包邮/发货协商表达");
    }

    /**
     * WAITING 粘性期间只承接两类消息：议价/邮费协商本身，以及对已报价格的简短确认
     * （“好的/240成交/我拍了”）。其余消息（商品咨询、退货、订单物流……）拒接，
     * 让 Router 取消本任务后按首评逻辑重新路由。
     */
    @Override
    public boolean acceptsMessage(RoutingContext context) {
        String message = normalize(context.getMessage());
        return isAcknowledgment(message) || claimsBargainMessage(message);
    }

    /**
     * 这句话是否“议价/邮费协商”本身的完整门控（含投诉/政策/退货/订单排除）。
     * match 与 acceptsMessage 共用，保证恢复活跃任务与首次路由口径一致。
     */
    private boolean claimsBargainMessage(String message) {
        if (!isBargainMessage(message)) {
            return false;
        }
        if (intentRules.shouldHandoff(message)) {
            return false;
        }
        // “退货运费谁承担”这类政策问题即使带“运费”也归政策问答/退货流程，不在议价里答。
        if (intentRules.isKnowledgeRelatedMessage(message)) {
            return false;
        }
        if (intentRules.isReturnRelatedMessage(message)) {
            return false;
        }
        return !containsAny(message, "订单", "物流", "快递", "order", "tracking");
    }

    @Override
    public WorkflowDecision nextAction(TurnContext context) {
        context.getState().setActiveIntent("bargain");
        BargainState state = new BargainState(context.getState());
        String message = normalize(context.getMessage());

        WorkflowDecision interrupted = handleInterrupt(context, state);
        if (interrupted != null) {
            return interrupted;
        }

        // 承接收尾：等待买家回应期间，买家简短确认已报价格 → 与首次报价过同一套
        // 校验（库存、事实、授权）后才按 lastQuote 收尾，绝不按失效旧价成交。
        if (state.getStep() == BargainStep.WAITING_BUYER && isAcknowledgment(message)) {
            return acknowledgeDeal(context, state);
        }

        return switch (state.getStep()) {
            case START, WAITING_BUYER -> respond(context, state);
            case COMPLETED -> WorkflowDecision.next(
                    new FinalAnswerAction("这次的议价先到这里，您考虑好随时告诉我。"),
                    "议价 Workflow 已完成"
            );
            case CANCELLED -> WorkflowDecision.next(
                    new CancelWorkflowAction("本次议价已取消。"),
                    "议价 Workflow 已取消"
            );
        };
    }

    /**
     * 买家确认成交的统一校验：确认分支不得绕过任何与首次报价相同的检查。
     * 复现过的缺陷是“报价 250 后库存热更新为 0，买家回‘好的’仍按 ¥250 收尾”、
     * “撤销议价授权后仍按旧价收尾”——确认必须是校验后的确认：
     * <ul>
     *     <li>库存已知为 0：报价已失效，终态告知售出，不成交、不转人工。</li>
     *     <li>商品事实缺失或价格未知：不得按历史报价成交，转卖家本人确认。</li>
     *     <li>lastQuote 失效（没有有效报价、高于现挂牌价、低于现底线价）或
     *         议价授权已撤销（bargainAccepted=false）：历史报价不再代表卖家承诺，
     *         转卖家本人确认收口。</li>
     * </ul>
     */
    private WorkflowDecision acknowledgeDeal(TurnContext context, BargainState state) {
        ItemFacts facts = currentXianyuItemFacts(context);
        SellerRules rules = sellerRulesFor(currentItemId(context));

        if (facts != null && facts.stockKnown() && !facts.inStock()) {
            state.transitionTo(BargainStep.COMPLETED);
            return WorkflowDecision.next(
                    new FinalAnswerAction("抱歉，这件商品刚刚售出了。您可以看看卖家主页还有没有类似的宝贝。"),
                    "确认成交时库存已知为 0，拒绝收尾并结束议价"
            );
        }

        BigDecimal lastQuote = state.getLastQuote();
        boolean quoteInvalid = lastQuote == null;
        if (!quoteInvalid && facts != null && facts.price().known()) {
            quoteInvalid = lastQuote.compareTo(facts.price().min()) > 0;
        }
        if (!quoteInvalid) {
            BigDecimal floor = rules.getFloorPrice();
            quoteInvalid = floor != null && lastQuote.compareTo(floor) < 0;
        }
        if (!quoteInvalid && !rules.isBargainAccepted()) {
            quoteInvalid = true;
        }

        if (quoteInvalid || facts == null || !facts.price().known()) {
            return sellerConfirmPause(
                    context,
                    state,
                    "刚才说的价格可能已经有变化了，我需要卖家本人跟您确认一下，请稍等。",
                    "确认成交时事实或授权校验未通过",
                    facts
            );
        }

        state.transitionTo(BargainStep.COMPLETED);
        return WorkflowDecision.next(
                new FinalAnswerAction(closingReply(lastQuote)),
                "买家确认按已报价格成交，库存与授权校验通过，议价收尾"
        );
    }

    private WorkflowDecision respond(TurnContext context, BargainState state) {
        ItemFacts facts = currentXianyuItemFacts(context);
        String itemId = currentItemId(context);
        SellerRules rules = sellerRulesFor(itemId);
        String message = normalize(context.getMessage());

        // 前置校验一：已知无货 → 不报价、不成交，直接终态告知。
        if (facts != null && facts.stockKnown() && !facts.inStock()) {
            state.transitionTo(BargainStep.COMPLETED);
            return WorkflowDecision.next(
                    new FinalAnswerAction("抱歉，这件商品刚刚售出了。您可以看看卖家主页还有没有类似的宝贝。"),
                    "库存已知为 0，拒绝报价并结束议价"
            );
        }

        // 前置校验二：商品事实未同步（无文档且无热更新、或价格未知）→ 不得凭全局底线价成交，
        // 记录买家出价后暂停机器人，转卖家本人确认。
        if (facts == null || !facts.price().known()) {
            BigDecimal missingFactsOffer = extractBuyerOffer(context.getMessage(), facts);
            if (missingFactsOffer != null) {
                state.setBuyerOffer(missingFactsOffer);
            }
            return sellerConfirmPause(
                    context,
                    state,
                    "这件商品的信息我这边还没同步，价格需要卖家本人确认，已经帮您转告，请稍等。",
                    "商品信息未同步",
                    facts
            );
        }

        boolean priceBargain = isPriceBargainMessage(message);
        String shippingText = shippingText(rules, message);
        BigDecimal offer = priceBargain ? extractBuyerOffer(context.getMessage(), facts) : null;
        Decision decision = priceBargain
                ? decide(rules, facts, offer, state.getLastQuote())
                : null;

        if (offer != null) {
            state.setBuyerOffer(offer);
        }
        if (decision != null && decision.quote() != null) {
            state.setLastQuote(decision.quote());
        }
        state.transitionTo(BargainStep.WAITING_BUYER);

        // 需卖家拍板：价格未授权，或包邮/发货超出授权 → 暂停机器人转卖家本人确认（不建工单）。
        boolean priceNeedsSeller = decision != null && decision.type() == Decision.Type.SELLER_CONFIRM;
        boolean shippingNeedsSeller = shippingText.contains("未获授权");
        if (priceNeedsSeller || shippingNeedsSeller) {
            return sellerConfirmPause(
                    context,
                    state,
                    sellerConfirmReply(decision, shippingNeedsSeller),
                    priceNeedsSeller ? "价格超出机器人授权" : "发货/包邮超出机器人授权",
                    facts
            );
        }

        // 确定性输出：买家最终收到的就是这段由代码按卖家授权构建的话术，不经模型措辞。
        // 复现过的缺陷是失控的模型输出（更低价格、“已改价/包邮”承诺）原样直达买家，
        // 越过代码授权；交易承诺没有“措辞自由”，AskUserAction 保持 WAITING_USER 粘性，
        // lastQuote 继续约束后续轮次的报价一致性。
        String authorizedReply = buildAuthorizedReply(decision, rules, shippingText);
        return WorkflowDecision.next(
                new AskUserAction(authorizedReply),
                "议价结论已按卖家授权规则计算，直接输出确定性话术"
        );
    }

    /**
     * 暂停机器人、转卖家本人确认：pauseOnly HandoffAction 跳过能力网关与工单创建，
     * Runtime 置 HANDED_OFF，侧车据此进入人工模式；买家收到固定话术（含已授权报价时
     * 会带上）。摘要只含买家可见信息，底线价绝不出现在这里。
     */
    private WorkflowDecision sellerConfirmPause(TurnContext context,
                                                BargainState state,
                                                String buyerReply,
                                                String reasonLabel,
                                                ItemFacts facts) {
        state.transitionTo(BargainStep.COMPLETED);
        Map<String, Object> pauseContext = new LinkedHashMap<>(state.snapshot());
        if (facts != null && !facts.itemId().isBlank()) {
            pauseContext.put("itemId", facts.itemId());
        }
        return WorkflowDecision.next(
                new HandoffAction(
                        "SELLER_CONFIRMATION_REQUIRED",
                        buildSellerConfirmSummary(context, state, reasonLabel, facts),
                        pauseContext,
                        true,
                        buyerReply
                ),
                reasonLabel + "：暂停机器人，转卖家本人确认"
        );
    }

    /** 卖家确认摘要：买家消息 + 出价/最近报价 + 商品，绝不含底线价。 */
    private String buildSellerConfirmSummary(TurnContext context,
                                             BargainState state,
                                             String reasonLabel,
                                             ItemFacts facts) {
        StringBuilder summary = new StringBuilder("闲鱼议价需要卖家本人确认（")
                .append(reasonLabel).append("）。");
        summary.append("买家消息：").append(compactText(context.getMessage()));
        if (state.getBuyerOffer() != null) {
            summary.append("；买家最近出价：").append(display(state.getBuyerOffer()));
        }
        if (state.getLastQuote() != null) {
            summary.append("；机器人最近报价：").append(display(state.getLastQuote()));
        }
        if (facts != null && !facts.itemId().isBlank()) {
            summary.append("；商品ID：").append(facts.itemId());
            if (!facts.title().isBlank()) {
                summary.append("（").append(facts.title()).append("）");
            }
        }
        return summary.toString();
    }

    /** 暂停时发给买家的固定话术：已授权的报价照说，未授权的部分只说做不了主。 */
    private String sellerConfirmReply(Decision decision, boolean shippingNeedsSeller) {
        StringBuilder reply = new StringBuilder();
        if (decision != null && decision.quote() != null) {
            reply.append(display(decision.quote())).append(" 可以出；");
        }
        if (shippingNeedsSeller) {
            reply.append("包邮/发货的事我做不了主，");
        } else {
            reply.append("价格的事我做不了主，");
        }
        reply.append("已经帮您转告卖家本人，请稍等确认。");
        return reply.toString();
    }

    /** 买家确认成交后的收尾话术：重申价格，引导拍下不付款、等改价。 */
    private String closingReply(BigDecimal lastQuote) {
        String priceText = lastQuote != null
                ? display(lastQuote)
                : "刚才说好的价格";
        return "好嘞，那就按 " + priceText
                + " 来。您先拍下不要付款，我让卖家同步改好价格您再支付。";
    }

    /**
     * 议价决策矩阵（全部在代码层完成）。未配置的授权一律视为“没有”，宁可转卖家确认：
     * <ul>
     *     <li>未开启 bargainAccepted：任何出价都转卖家确认。</li>
     *     <li>出价 ≥ 挂牌价：按挂牌价口径成交，不涉及议价授权。</li>
     *     <li>出价 ≥ 底线价：在授权范围内，直接答应。</li>
     *     <li>出价低于底线/未给价：按“挂牌价-单次让价”还价（不低于底线）；
     *         没有可计算的让价空间时，重申上一口价或按底线价作为授权报价。</li>
     * </ul>
     * 返回的 Decision 只含买家可见的价格，不含底线价本身。
     */
    private Decision decide(SellerRules rules, ItemFacts facts, BigDecimal offer, BigDecimal lastQuote) {
        BigDecimal listPrice = facts != null && facts.price().known() ? facts.price().min() : null;

        // 历史报价过期钳制：商品改价（热更新）或规则调整后，低于现底线/高于现挂牌价的
        // lastQuote 视为失效，按现价重新计算，避免恢复后改口或报出高于挂牌的价。
        BigDecimal floor = rules.getFloorPrice();
        if (lastQuote != null) {
            if (floor != null && lastQuote.compareTo(floor) < 0) {
                lastQuote = null;
            } else if (listPrice != null && lastQuote.compareTo(listPrice) > 0) {
                lastQuote = null;
            }
        }

        // 上一口价钳制：已报出的价是当前有效承诺，本轮成交价不高于它——买家在已同意
        // 240 之后改口“300卖吗”，也只按 240 成交，绝不收比承诺更高的价。
        if (offer != null && lastQuote != null && offer.compareTo(lastQuote) > 0) {
            offer = lastQuote;
        }

        // 出价不低于挂牌价：这不是议价，按在售价成交即可。
        if (offer != null && listPrice != null && offer.compareTo(listPrice) >= 0) {
            return Decision.accept(offer);
        }

        if (!rules.isBargainAccepted()) {
            return Decision.sellerConfirm();
        }

        BigDecimal counter = null;
        if (listPrice != null && rules.getAllowedDiscount() != null) {
            BigDecimal candidate = listPrice.subtract(rules.getAllowedDiscount());
            if (floor != null && candidate.compareTo(floor) < 0) {
                candidate = floor;
            }
            // 让价后反而高于挂牌价说明配置没有实际让价空间，不产生还价。
            counter = candidate.compareTo(listPrice) < 0 ? candidate : null;
        }

        if (offer != null) {
            if (floor != null && offer.compareTo(floor) >= 0) {
                return Decision.accept(offer);
            }
            if (counter != null && offer.compareTo(counter) >= 0) {
                return Decision.accept(offer);
            }
            if (counter != null) {
                return counterNotAboveLastQuote(counter, lastQuote);
            }
            if (floor != null) {
                return counterNotAboveLastQuote(floor, lastQuote);
            }
            return Decision.sellerConfirm();
        }

        // 未给价：先重申上一口价（连续砍价不继续让），再考虑授权还价，最后转卖家确认。
        if (lastQuote != null) {
            return Decision.counter(lastQuote, true);
        }
        if (counter != null) {
            return Decision.counter(counter, false);
        }
        if (floor != null) {
            return Decision.counter(floor, false);
        }
        return Decision.sellerConfirm();
    }

    /**
     * 还价不得高于上一口价：按公式算出的还价高于上一口价时，改为重申上一口价。
     * 复现缺陷是“第一轮已按 240 同意，第二轮‘200卖吗’又重新报出公式价 245”——
     * 带金额的还价和无金额的追问必须同样受上一口价约束。
     */
    private static Decision counterNotAboveLastQuote(BigDecimal counter, BigDecimal lastQuote) {
        if (lastQuote != null && counter.compareTo(lastQuote) > 0) {
            return Decision.counter(lastQuote, true);
        }
        return Decision.counter(counter, false);
    }

    /**
     * 从买家消息提取出价（BigDecimal，保留精度）。支持“250卖吗 / 200出 / 出260 /
     * 250块 / 220怎么样 / 纯数字 / 便宜50（按挂牌价折算）”。提取不到返回 null，
     * 由决策矩阵按“未给价”处理。
     */
    BigDecimal extractBuyerOffer(String rawMessage, ItemFacts facts) {
        if (rawMessage == null) {
            return null;
        }
        String text = rawMessage.trim();

        Matcher bare = BARE_NUMBER.matcher(text);
        if (bare.matches()) {
            return positive(bare.group(1));
        }

        // “便宜50/让20/少30块”是让价幅度，不是绝对出价；折算成 挂牌价-幅度。
        // 必须先于其它模式判断，否则“便宜50块”会被“50块”误读为出价 50。
        Matcher discount = DISCOUNT_PATTERN.matcher(text);
        if (discount.find()) {
            BigDecimal amount = positive(discount.group(1));
            if (amount == null || facts == null || !facts.price().known()) {
                return null;
            }
            BigDecimal implied = facts.price().min().subtract(amount);
            return implied.compareTo(BigDecimal.ZERO) > 0 ? implied : null;
        }

        for (Pattern pattern : OFFER_PATTERNS) {
            Matcher matcher = pattern.matcher(text);
            if (matcher.find()) {
                return positive(matcher.group(1));
            }
        }
        return null;
    }

    /** 卖家已授权的发货/包邮口径；未授权的字段只能转卖家确认，绝不虚构。 */
    private String shippingText(SellerRules rules, String message) {
        boolean asksFreeShipping = containsAny(message, "包邮", "免运费", "包个邮", "运费", "邮费");
        boolean asksShipping = containsAny(message, "发货", "快递", "顺丰", "到付", "怎么发", "何时发", "什么时候发");
        if (!asksFreeShipping && !asksShipping) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        if (asksFreeShipping) {
            if (rules.getFreeShippingCondition().isEmpty()) {
                text.append("包邮：未获授权，不得承诺包邮，转卖家确认。");
            } else {
                text.append("包邮：卖家授权口径是“").append(rules.getFreeShippingCondition())
                        .append("”，只能按此表述，不得放宽。");
            }
        }
        if (asksShipping) {
            if (rules.getShippingArrangement().isEmpty()) {
                text.append("发货安排：未获授权，不得虚构发货方式或时效，转卖家确认。");
            } else {
                text.append("发货安排：卖家授权口径是“").append(rules.getShippingArrangement())
                        .append("”，只能按此表述。");
            }
        }
        return text.toString();
    }

    /**
     * 发给买家的确定性授权话术：议价全程唯一出口，不经模型措辞。
     * 模板只陈述代码决策给出的价格与卖家已授权的发货/包邮口径；
     * 未授权的字段只说“需要问卖家”，绝不虚构承诺。
     */
    private String buildAuthorizedReply(Decision decision, SellerRules rules, String shippingText) {
        StringBuilder reply = new StringBuilder();
        if (decision != null) {
            switch (decision.type()) {
                case ACCEPT -> reply.append("可以的，").append(display(decision.quote()))
                        .append(" 成交。您先拍下不要付款，我同步卖家改好价格您再支付。");
                case COUNTER -> {
                    if (decision.restated()) {
                        reply.append(display(decision.quote())).append(" 真的没有再让的空间了，您考虑一下。");
                    } else {
                        reply.append(display(decision.quote())).append(" 可以出，您接受的话就拍下，我这边跟卖家同步改价。");
                    }
                }
                case SELLER_CONFIRM -> reply.append("价格的事我做不了主，我这就把您的意思转告卖家，请稍等确认。");
            }
        }
        if (shippingText.contains("未获授权")) {
            reply.append("包邮和发货的事我需要先问下卖家，稍等。");
        } else {
            if (shippingText.contains("发货安排：") && !rules.getShippingArrangement().isEmpty()) {
                reply.append("发货安排：").append(rules.getShippingArrangement()).append("。");
            }
            if (shippingText.contains("包邮：") && !rules.getFreeShippingCondition().isEmpty()) {
                reply.append("包邮口径：").append(rules.getFreeShippingCondition()).append("。");
            }
        }
        if (reply.isEmpty()) {
            reply.append("我这就把您的意思转告卖家，请稍等确认。");
        }
        return reply.toString();
    }

    private WorkflowDecision handleInterrupt(TurnContext context, BargainState state) {
        GlobalInterruptDecision interrupt = interruptPolicy.evaluate(context.getMessage());
        if (interrupt.getType() == GlobalInterruptType.CANCEL) {
            state.transitionTo(BargainStep.CANCELLED);
            return WorkflowDecision.next(
                    new CancelWorkflowAction("本次议价已取消。"),
                    interrupt.getReason()
            );
        }
        if (interrupt.getType() == GlobalInterruptType.HANDOFF) {
            Map<String, Object> handoffContext = new LinkedHashMap<>(state.snapshot());
            state.transitionTo(BargainStep.CANCELLED);
            return WorkflowDecision.next(
                    new HandoffAction(
                            interrupt.getHandoffReasonCode(),
                            HandoffSummarySupport.build("议价", context, interrupt, handoffContext),
                            handoffContext
                    ),
                    interrupt.getReason()
            );
        }
        return null;
    }

    private boolean isXianyuSession(RoutingContext context) {
        return xianyuItemFactService != null && xianyuItemFactService.isXianyuConversation(
                context.getRequest().getTenantId(),
                context.getRequest().getConversationId()
        );
    }

    private ItemFacts currentXianyuItemFacts(TurnContext context) {
        if (xianyuItemFactService == null) {
            return null;
        }
        return xianyuItemFactService.currentItemFacts(context.getRequest().getConversationId()).orElse(null);
    }

    private String currentItemId(TurnContext context) {
        if (xianyuItemFactService == null) {
            return null;
        }
        return xianyuItemFactService.currentItemId(context.getRequest().getConversationId()).orElse(null);
    }

    /** 按商品取卖家规则：item-rules 优先，缺省回落全局 seller-rules。 */
    private SellerRules sellerRulesFor(String itemId) {
        if (sellerRuleService == null) {
            return new SellerRules();
        }
        return sellerRuleService.forItem(itemId);
    }

    /** 议价/砍价/包邮/发货协商的入口信号。 */
    private boolean isBargainMessage(String message) {
        return isPriceBargainMessage(message) || isShippingMessage(message);
    }

    private boolean isPriceBargainMessage(String message) {
        return containsAny(
                message,
                "便宜", "砍价", "议价", "最低", "底价", "让点", "让一点", "优惠", "打折", "折扣",
                "少点", "少一点", "再让", "出吗", "出不出", "卖吗", "卖不卖", "拿走", "能出", "可出"
        ) || hasOfferNumber(message);
    }

    private boolean isShippingMessage(String message) {
        return containsAny(
                message,
                "包邮", "免运费", "包个邮", "运费", "邮费", "发货", "快递", "顺丰", "到付"
        );
    }

    /**
     * 买家对已报价格的简短承接确认：整句（去标点/语气词后）完全匹配成交类或应答类短语。
     * 全匹配保证“可以便宜点吗”“220可以吗”这类继续砍价/疑问句不会误判为成交确认。
     */
    private boolean isAcknowledgment(String message) {
        if (message == null || message.isEmpty() || isPriceBargainMessage(message)) {
            return false;
        }
        String compact = message.replaceAll("[\\s。．.!！？?，,、~～啊呀哦嘞了呢嘛]", "");
        if (compact.isEmpty() || compact.length() > 12) {
            return false;
        }
        return ACK_DEAL.matcher(compact).matches()
                || ACK_SHORT.matcher(compact).matches();
    }

    /** 成交类承接：“成交 / 240成交 / 好的，240成交 / 我拍了 / 拍下了 / 就这个价 / 要了”。 */
    private static final Pattern ACK_DEAL = Pattern.compile(
            "(?:好的?|好嘞|行吧?|嗯+|哦+|ok)?"
                    + "(?:\\d+(?:\\.\\d+)?\\s*元?\\s*)?(?:就|按)?(?:这个价|成交)"
                    + "|我?拍了|拍下了?|已拍|已?下单了?|要了|搞定了?"
    );

    /** 应答类承接：“好的 / 好嘞 / 行 / 可以 / 没问题 / ok / 嗯”。 */
    private static final Pattern ACK_SHORT = Pattern.compile(
            "好的?|好嘞|好吧|行吧?|可以的?|没问题|同意|接受|收到|嗯+|哦+|ok|okay|成交"
    );

    private boolean hasOfferNumber(String message) {
        if (message == null) {
            return false;
        }
        for (Pattern pattern : GATING_PATTERNS) {
            if (pattern.matcher(message.trim()).find()) {
                return true;
            }
        }
        return BARE_NUMBER.matcher(message.trim()).matches();
    }

    private static final Pattern BARE_NUMBER = Pattern.compile("^\\s*(\\d+(?:\\.\\d+)?)\\s*$");

    /** “便宜50/让20块/少30”是让价幅度。 */
    private static final Pattern DISCOUNT_PATTERN =
            Pattern.compile("(?:便宜|少|让|降|减)\\s*(\\d+(?:\\.\\d+)?)\\s*(?:块|元)?");

    private static final List<Pattern> OFFER_PATTERNS = List.of(
            // “250卖吗”“200出”“260拿走”“300收”
            Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(?:块|元)?\\s*(?:出|卖|拿走|收)"),
            // “出260”“给250”“降到220”“讲到200”
            Pattern.compile("(?:出|给|降到|讲到|谈到)\\s*(\\d+(?:\\.\\d+)?)"),
            // “250块”“265元”
            Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(?:块|元)"),
            // “220怎么样”“240可以吗”“210行不行”
            Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(?:块|元)?\\s*(?:怎么样|咋样|可以吗|可以不|行不行|行么|好吗|能接受|接受吗)")
    );

    /**
     * 路由门控用的出价信号：只认“动词锚定”的出价（250卖吗/出260/220怎么样/纯数字），
     * 不含裸“N块/N元”——否则“这耳机100块的东西还在吗”会被误判成议价。
     */
    private static final List<Pattern> GATING_PATTERNS = List.of(
            OFFER_PATTERNS.get(0),
            OFFER_PATTERNS.get(1),
            OFFER_PATTERNS.get(3)
    );

    /** 报价/成交决策；quote 是买家可见价格，底线价不会出现在这里。 */
    private record Decision(Type type, BigDecimal quote, boolean restated) {

        enum Type { ACCEPT, COUNTER, SELLER_CONFIRM }

        static Decision accept(BigDecimal quote) {
            return new Decision(Type.ACCEPT, quote, false);
        }

        static Decision counter(BigDecimal quote, boolean restated) {
            return new Decision(Type.COUNTER, quote, restated);
        }

        static Decision sellerConfirm() {
            return new Decision(Type.SELLER_CONFIRM, null, false);
        }
    }

    private String display(BigDecimal price) {
        return price == null ? "" : "¥" + price.stripTrailingZeros().toPlainString();
    }

    private BigDecimal positive(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            BigDecimal value = new BigDecimal(raw);
            return value.compareTo(BigDecimal.ZERO) > 0 ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    /** 摘要用：压平空白，保留原文语义（不做任何改写）。 */
    private String compactText(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

    private boolean containsAny(String text, String... keywords) {
        if (text == null) {
            return false;
        }
        for (String keyword : keywords) {
            if (text.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
