package com.smartcs.smart_cs_agent.workflow.warranty;

import com.smartcs.smart_cs_agent.agent.action.CapabilityAction;
import com.smartcs.smart_cs_agent.agent.action.FinalAnswerAction;
import com.smartcs.smart_cs_agent.agent.context.CapabilityObservation;
import com.smartcs.smart_cs_agent.agent.context.Observation;
import com.smartcs.smart_cs_agent.agent.context.TurnContext;
import com.smartcs.smart_cs_agent.capability.api.CapabilityIds;
import com.smartcs.smart_cs_agent.capability.api.contracts.KnowledgeSearchHit;
import com.smartcs.smart_cs_agent.workflow.api.AgentWorkflow;
import com.smartcs.smart_cs_agent.workflow.api.RoutingContext;
import com.smartcs.smart_cs_agent.workflow.api.WorkflowDecision;
import com.smartcs.smart_cs_agent.workflow.api.WorkflowDescriptor;
import com.smartcs.smart_cs_agent.workflow.api.WorkflowMatchResult;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 保修政策查询 Workflow，也是本次架构改造的“零核心修改扩展证明”。
 *
 * <p>职责：识别保修问题、发出 {@code knowledge.search} Action，并把稳定的知识检索契约
 * 整理成用户回复。</p>
 *
 * <p>不负责：不读取 Markdown、不访问 Repository、不选择本地或 MCP Provider，也不修改 Router。</p>
 *
 * <p>调用链：</p>
 * <pre>
 * WorkflowRegistry 自动发现本 Bean
 *   -> WorkflowRouter 根据 match 分数选择
 *   -> CapabilityActionHandler 调用 CapabilityGateway
 *   -> 本 Workflow 读取 CapabilityObservation 并完成回复
 * </pre>
 *
 * <p>以后增加“安装指导”“会员规则”等只读 Skill，可以照着这个小类学习。</p>
 */
@Component
public class WarrantyQueryWorkflow implements AgentWorkflow {

    public static final String ID = "warranty_query";
    public static final String VERSION = "1.0.0";

    private final WorkflowDescriptor descriptor = new WorkflowDescriptor(
            ID,
            VERSION,
            "查询商品保修期限、范围和申请方式",
            180
    );

    @Override
    public WorkflowDescriptor descriptor() {
        return descriptor;
    }

    /**
     * 这里只做无副作用打分。明确退货/退款问题仍交给退货相关 Workflow，
     * 防止一句“质量问题我要退货”被保修查询抢走。
     */
    @Override
    public WorkflowMatchResult match(RoutingContext context) {
        String message = normalize(context.getMessage());
        if (containsAny(message, "退货", "退款", "换货")) {
            return WorkflowMatchResult.noMatch("退换货诉求不属于保修政策查询");
        }
        if (containsAny(message, "保修", "质保", "保修期", "质保期", "一年保修")) {
            return new WorkflowMatchResult(0.99, "命中明确保修/质保表达");
        }
        if (containsAny(message, "坏了能修", "故障能修", "维修多久")) {
            return new WorkflowMatchResult(0.94, "命中维修政策表达");
        }
        return WorkflowMatchResult.noMatch("未命中保修政策表达");
    }

    /**
     * 第一次进入时发出知识检索 Action；Handler 执行完后会在同一轮循环中写入 Observation，
     * 第二次进入时再根据结果完成回复。Workflow 本身完全不知道调用来自本地还是远程 MCP。
     */
    @Override
    public WorkflowDecision nextAction(TurnContext context) {
        CapabilityObservation observation = latestCapabilityObservation(context);
        if (observation == null) {
            return WorkflowDecision.next(
                    new CapabilityAction(
                            CapabilityIds.KNOWLEDGE_SEARCH,
                            Map.of("query", context.getMessage())
                    ),
                    "先检索正式保修政策，避免根据模型常识猜答案"
            );
        }

        if (!CapabilityIds.KNOWLEDGE_SEARCH.equals(observation.getCapabilityId())) {
            return WorkflowDecision.next(
                    new FinalAnswerAction("当前没有得到可用的保修政策查询结果，请稍后再试。"),
                    "收到的 Capability Observation 与预期契约不一致"
            );
        }

        if (!observation.isSuccess()) {
            String answer = observation.isBusinessRejected()
                    ? "知识库暂时没有找到明确的保修政策，建议补充商品型号或联系人工客服。"
                    : "保修政策查询服务暂时不可用，请稍后重试或联系人工客服。";
            return WorkflowDecision.next(
                    new FinalAnswerAction(answer),
                    "knowledge.search 未成功，不编造政策答案"
            );
        }

        KnowledgeSearchHit bestHit = firstHit(observation.getData());
        if (bestHit == null || bestHit.getSnippet().isBlank()) {
            return WorkflowDecision.next(
                    new FinalAnswerAction("暂时没有检索到可靠的保修政策，请补充商品型号或联系人工客服。"),
                    "knowledge.search 成功但没有可靠命中"
            );
        }

        String title = bestHit.getTitle().isBlank() ? "保修政策" : bestHit.getTitle();
        String answer = "根据客服知识库的《" + title + "》：\n"
                + bestHit.getSnippet()
                + "\n\n不同品牌或特殊商品可能有差异，最终以商品详情页和随箱保修说明为准。";
        return WorkflowDecision.next(
                new FinalAnswerAction(answer),
                "使用最高相关度知识条目生成确定性答复"
        );
    }

    private CapabilityObservation latestCapabilityObservation(TurnContext context) {
        Observation observation = context.getLatestObservationByType("capability_result");
        return observation != null && observation.getData() instanceof CapabilityObservation result
                ? result
                : null;
    }

    private KnowledgeSearchHit firstHit(Object data) {
        if (!(data instanceof List<?> rows) || rows.isEmpty()) {
            return null;
        }
        Object first = rows.get(0);
        return first instanceof KnowledgeSearchHit hit ? hit : null;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}
