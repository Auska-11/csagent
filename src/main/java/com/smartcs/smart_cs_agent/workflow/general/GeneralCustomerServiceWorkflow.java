package com.smartcs.smart_cs_agent.workflow.general;

import com.smartcs.smart_cs_agent.agent.action.FinalAnswerAction;
import com.smartcs.smart_cs_agent.agent.action.LLMAction;
import com.smartcs.smart_cs_agent.agent.context.Observation;
import com.smartcs.smart_cs_agent.agent.context.TurnContext;
import com.smartcs.smart_cs_agent.workflow.api.AgentWorkflow;
import com.smartcs.smart_cs_agent.workflow.api.RoutingContext;
import com.smartcs.smart_cs_agent.workflow.api.WorkflowDecision;
import com.smartcs.smart_cs_agent.workflow.api.WorkflowDescriptor;
import com.smartcs.smart_cs_agent.workflow.api.WorkflowMatchResult;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 通用客服兜底 Workflow。
 *
 * <p>职责：为闲聊、感谢和无法可靠归类的模糊输入生成简洁回复或澄清问题。</p>
 * <p>不负责：不查询订单、商品、退货、用户资料或政策，也不创建人工工单。上述请求必须由独立
 * Workflow 接管，避免通用 LLM 绕过能力授权和业务状态机。</p>
 *
 * <p>调用链：</p>
 * <pre>
 * WorkflowRouter 没有找到可靠的专业 Workflow
 *   -> GeneralCustomerServiceWorkflow
 *   -> LLMActionHandler 使用 customer-service Skill Package 组装 Prompt
 *   -> 本 Workflow 读取 llm_response Observation
 *   -> FinalAnswerAction
 * </pre>
 *
 * <p>稳定 ID 必须保持 {@code customer_service}，因为 Skill Package manifest 和已有会话都通过这个
 * ID 绑定 Workflow。Phase 9 只替换实现，不破坏这个外部契约。</p>
 */
@Component
public class GeneralCustomerServiceWorkflow implements AgentWorkflow {

    public static final String ID = "customer_service";
    public static final String VERSION = "1.0.0";

    /**
     * 分数故意低于 Router 默认阈值 0.25。
     *
     * <p>这使它成为真正的 fallback：任何可靠的专业 Workflow 都可以胜出；如果所有候选都不可靠，
     * Router 再按 fallbackWorkflowId 明确选择本 Workflow。</p>
     */
    private static final double FALLBACK_SCORE = 0.20;

    private final WorkflowDescriptor descriptor = new WorkflowDescriptor(
            ID,
            VERSION,
            "处理闲聊、感谢和无法可靠归类的通用客服输入",
            0
    );

    @Override
    public WorkflowDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public WorkflowMatchResult match(RoutingContext context) {
        return new WorkflowMatchResult(FALLBACK_SCORE, "保留为通用客服兜底候选");
    }

    /**
     * 第一次进入时请求 LLM 生成回复；Handler 写入 Observation 后，Runtime 会在同一轮再次调用本方法，
     * 第二次再输出最终答案。Workflow 不直接调用模型，因此超时、降级和 Trace 仍由统一 Handler 管理。
     */
    @Override
    public WorkflowDecision nextAction(TurnContext context) {
        Observation llmResponse = context.getLatestObservationByType("llm_response");
        if (llmResponse != null) {
            return WorkflowDecision.next(
                    new FinalAnswerAction(String.valueOf(llmResponse.getData())),
                    "通用客服回复已经生成"
            );
        }

        context.getState().setActiveIntent(isChitchat(context.getMessage()) ? "chitchat" : "other");
        return WorkflowDecision.next(
                new LLMAction(
                        "response_generation",
                        buildGeneralResponsePrompt(context.getMessage()),
                        Map.of("workflow", ID)
                ),
                "专业 Workflow 均未可靠命中，生成通用回复或澄清问题"
        );
    }

    private String buildGeneralResponsePrompt(String message) {
        return """
                请处理下面这条不需要调用业务工具的通用客服消息：
                - 如果是打招呼、感谢或礼貌闲聊，用中文友好、简洁地回复。
                - 如果诉求不明确，只追问一个最关键的问题，并提示可办理订单、商品、退换货、政策或人工客服。
                - 不要声称已经查询订单、修改数据、提交申请或创建工单。
                - 不要编造价格、库存、物流、退款进度或平台政策。

                用户消息：%s
                """.formatted(message == null ? "" : message);
    }

    private boolean isChitchat(String message) {
        String text = message == null ? "" : message.trim().toLowerCase();
        return text.contains("你好")
                || text.contains("您好")
                || text.contains("谢谢")
                || text.contains("感谢")
                || text.equals("hi")
                || text.equals("hello");
    }
}
