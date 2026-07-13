package com.smartcs.smart_cs_agent.workflow;

import com.smartcs.smart_cs_agent.agent.action.FinalAnswerAction;
import com.smartcs.smart_cs_agent.agent.action.LLMAction;
import com.smartcs.smart_cs_agent.agent.context.ChatRequest;
import com.smartcs.smart_cs_agent.agent.context.ConversationState;
import com.smartcs.smart_cs_agent.agent.context.TurnContext;
import com.smartcs.smart_cs_agent.workflow.api.RoutingContext;
import com.smartcs.smart_cs_agent.workflow.general.GeneralCustomerServiceWorkflow;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 通用客服 Workflow 的纯决策测试，不启动 Spring，也不调用真实 LLM。 */
class GeneralCustomerServiceWorkflowTest {

    private final GeneralCustomerServiceWorkflow workflow = new GeneralCustomerServiceWorkflow();

    @Test
    void shouldKeepStableIdAndRemainBelowRouterThreshold() {
        TurnContext context = turn("你好");

        assertEquals("customer_service", workflow.descriptor().getId());
        assertTrue(workflow.match(new RoutingContext(context.getRequest(), context.getState())).getScore() < 0.25);
    }

    @Test
    void shouldGenerateOneGeneralReplyThenFinishFromObservation() {
        TurnContext context = turn("我有个问题");

        LLMAction llmAction = assertInstanceOf(LLMAction.class, workflow.nextAction(context).getAction());
        assertEquals("response_generation", llmAction.getPurpose());
        assertTrue(llmAction.getPrompt().contains("只追问一个最关键的问题"));
        assertTrue(llmAction.getPrompt().contains("不要声称已经查询订单"));

        context.addObservation("llm_response", "请问您想咨询订单、商品还是退换货？");
        FinalAnswerAction answer = assertInstanceOf(
                FinalAnswerAction.class,
                workflow.nextAction(context).getAction()
        );
        assertEquals("请问您想咨询订单、商品还是退换货？", answer.getAnswer());
        assertEquals("other", context.getState().getActiveIntent());
    }

    @Test
    void shouldMarkGreetingAsChitchatWithoutCallingBusinessCapability() {
        TurnContext context = turn("你好");

        assertInstanceOf(LLMAction.class, workflow.nextAction(context).getAction());

        assertEquals("chitchat", context.getState().getActiveIntent());
    }

    private TurnContext turn(String message) {
        ChatRequest request = new ChatRequest("general-workflow-test", "USER-001", message);
        return new TurnContext(request, new ConversationState(message, "USER-001"));
    }
}
