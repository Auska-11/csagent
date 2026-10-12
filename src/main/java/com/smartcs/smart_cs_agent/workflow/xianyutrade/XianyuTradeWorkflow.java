package com.smartcs.smart_cs_agent.workflow.xianyutrade;

import com.smartcs.smart_cs_agent.agent.action.FinalAnswerAction;
import com.smartcs.smart_cs_agent.agent.action.HandoffAction;
import com.smartcs.smart_cs_agent.agent.context.TurnContext;
import com.smartcs.smart_cs_agent.channel.xianyu.XianyuItemFactService;
import com.smartcs.smart_cs_agent.channel.xianyu.XianyuSellerRuleService;
import com.smartcs.smart_cs_agent.channel.xianyu.XianyuSoldItemGuard;
import com.smartcs.smart_cs_agent.skill.CustomerServiceIntentRules;
import com.smartcs.smart_cs_agent.workflow.api.*;
import org.springframework.stereotype.Component;
import java.util.Map;

/** Xianyu transaction questions use seller authorization; no simulated order/return operations. */
@Component
public class XianyuTradeWorkflow implements AgentWorkflow {
    public static final String ID = "xianyu_trade";
    private final XianyuItemFactService facts;
    private final XianyuSellerRuleService rules;
    private final CustomerServiceIntentRules intents = new CustomerServiceIntentRules();
    private final WorkflowDescriptor descriptor = new WorkflowDescriptor(ID, "1.0.0",
            "闲鱼发货、配件、自提和售后沟通；未授权事项交卖家确认", 175);

    public XianyuTradeWorkflow(XianyuItemFactService facts, XianyuSellerRuleService rules) {
        this.facts = facts;
        this.rules = rules;
    }

    @Override public WorkflowDescriptor descriptor() { return descriptor; }

    @Override
    public WorkflowMatchResult match(RoutingContext context) {
        if (!facts.isXianyuConversation(context.getRequest().getTenantId(), context.getRequest().getConversationId())
                || intents.shouldHandoff(context.getMessage())) return WorkflowMatchResult.noMatch("非闲鱼交易问答或明确人工升级");
        String message = context.getMessage();
        boolean related = intents.isReturnRelatedMessage(message)
                || contains(message, "七天", "保修", "发票", "售后", "自提", "面交", "包装", "配件", "附件", "原盒")
                || (contains(message, "发货", "发出", "发的什么", "多久到", "物流", "快递")
                    && !contains(message, "便宜", "砍价", "议价", "块", "元", "包邮", "运费", "邮费"));
        return related ? new WorkflowMatchResult(0.994, "闲鱼交易安排或卖家确认事项")
                : WorkflowMatchResult.noMatch("不是闲鱼交易问答");
    }

    @Override
    public WorkflowDecision nextAction(TurnContext context) {
        context.getState().setActiveIntent(ID);
        String message = context.getMessage();
        String itemId = facts.currentItemId(context.getRequest().getConversationId()).orElseThrow();
        var item = facts.itemFacts(itemId).orElse(null);
        if (XianyuSoldItemGuard.isSoldOut(item) && XianyuSoldItemGuard.isDealAdvancingIntent(message)) {
            return answer(XianyuSoldItemGuard.SOLD_REPLY);
        }
        if (contains(message, "配件", "附件", "原盒") && item != null && !item.accessories().isBlank()) {
            return answer("这件商品的配件情况：" + item.accessories());
        }
        if (!intents.isReturnRelatedMessage(message) && !contains(message, "售后", "保修", "发票", "七天", "自提", "面交", "包装", "配件", "附件", "原盒")
                && contains(message, "发货", "发出", "发的什么", "多久到", "物流", "快递")) {
            String arrangement = rules.forItem(itemId).getShippingArrangement();
            if (!arrangement.isBlank()) return answer("卖家公布的发货安排：" + arrangement
                    + "。具体订单是否已发出、物流进度还需要卖家核对。");
        }
        return WorkflowDecision.next(new HandoffAction("SELLER_CONFIRM", "闲鱼交易事项需卖家核对：" + message,
                Map.of("itemId", itemId), true,
                "这个事项需要卖家本人核对，我先暂停自动回复，请稍等卖家确认。"), "没有授权的交易承诺交卖家确认");
    }

    private WorkflowDecision answer(String text) {
        return WorkflowDecision.next(new FinalAnswerAction(text), "按已发布商品信息或卖家授权回答");
    }

    private static boolean contains(String message, String... words) {
        for (String word : words) if (message != null && message.contains(word)) return true;
        return false;
    }
}
