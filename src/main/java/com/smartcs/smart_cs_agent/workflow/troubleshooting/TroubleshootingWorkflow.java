package com.smartcs.smart_cs_agent.workflow.troubleshooting;

import com.smartcs.smart_cs_agent.agent.action.AskUserAction;
import com.smartcs.smart_cs_agent.agent.action.CapabilityAction;
import com.smartcs.smart_cs_agent.agent.action.CancelWorkflowAction;
import com.smartcs.smart_cs_agent.agent.action.FinalAnswerAction;
import com.smartcs.smart_cs_agent.agent.action.HandoffAction;
import com.smartcs.smart_cs_agent.agent.action.LLMAction;
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
import com.smartcs.smart_cs_agent.workflow.core.GlobalInterruptDecision;
import com.smartcs.smart_cs_agent.workflow.core.GlobalInterruptPolicy;
import com.smartcs.smart_cs_agent.workflow.core.GlobalInterruptType;
import com.smartcs.smart_cs_agent.workflow.core.HandoffSummarySupport;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 商品使用与故障排查 Workflow。
 *
 * <p>只在 knowledge.search 返回可靠证据后发出 LLMAction；模型调用继续由统一 Handler 负责。</p>
 */
@Component
public class TroubleshootingWorkflow implements AgentWorkflow {

    public static final String ID = "troubleshooting";
    public static final String VERSION = "1.0.0";

    private static final String ORIGINAL_QUERY = "original_query";
    private static final String LAST_SEARCH_QUERY = "last_search_query";
    private static final String WAITING_DETAILS = "waiting_details";

    private final GlobalInterruptPolicy interruptPolicy;

    private final WorkflowDescriptor descriptor = new WorkflowDescriptor(
            ID,
            VERSION,
            "根据知识证据处理商品使用方法和故障排查",
            185
    );

    public TroubleshootingWorkflow(GlobalInterruptPolicy interruptPolicy) {
        this.interruptPolicy = interruptPolicy;
    }

    @Override
    public WorkflowDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public WorkflowMatchResult match(RoutingContext context) {
        String message = normalize(context.getMessage());
        if (containsAny(message, "保修多久", "保修期", "质保期", "能保修", "坏了能修")) {
            return WorkflowMatchResult.noMatch("保修政策由 WarrantyQueryWorkflow 处理");
        }
        if (containsAny(message, "我要退货", "申请退款", "退货进度", "退款进度")) {
            return WorkflowMatchResult.noMatch("实时售后任务不进入 RAG");
        }
        if (containsAny(
                message,
                "没声音", "一边响", "只剩左边", "只剩右边", "单边", "单侧", "配不上", "连不上",
                "断连", "麦克风",
                "充不进", "不充电", "掉电快", "续航异常", "消息不来", "收不到通知", "不同步",
                "快充协议", "多少瓦", "功率兼容", "发热", "烫手", "只能充电", "不能传输",
                "不能传文件", "电脑不识别", "怎么重置", "怎么配对"
        )) {
            return new WorkflowMatchResult(0.995, "命中商品使用或故障排查表达");
        }
        return WorkflowMatchResult.noMatch("未命中明确的商品故障或使用问题");
    }

    @Override
    public WorkflowDecision nextAction(TurnContext context) {
        context.getState().setActiveIntent("troubleshooting");

        WorkflowDecision interrupted = handleInterrupt(context);
        if (interrupted != null) {
            return interrupted;
        }

        Observation llmResponse = context.getLatestObservationByType("llm_response");
        if (llmResponse != null) {
            return WorkflowDecision.next(
                    new FinalAnswerAction(String.valueOf(llmResponse.getData())),
                    "故障排查回复已由统一 LLMActionHandler 生成"
            );
        }

        CapabilityObservation search = latestCapabilityObservation(context);
        if (search == null) {
            return search(context, buildQuery(context));
        }
        if (!CapabilityIds.KNOWLEDGE_SEARCH.equals(search.getCapabilityId()) || !search.isSuccess()) {
            return WorkflowDecision.next(
                    new FinalAnswerAction("故障知识检索暂时不可用。请停止高风险操作，并联系人工客服。"),
                    "knowledge.search 技术失败，不调用 LLM"
            );
        }

        List<KnowledgeSearchHit> evidence = reliableEvidence(search.getData());
        if (evidence.isEmpty()) {
            context.getState().setWorkflowState(ID, WAITING_DETAILS, true);
            return WorkflowDecision.next(
                    new AskUserAction(
                            "暂时没有检索到可靠排查依据。请补充商品品类、完整型号、指示灯状态和具体现象；"
                                    + "若有进水、异常发热或拆机情况，请停止操作并联系人工客服。"
                    ),
                    "统一相关性阈值未通过，不调用 LLM"
            );
        }

        return WorkflowDecision.next(
                new LLMAction(
                        "response_generation",
                        buildEvidencePrompt(currentSearchQuery(context), evidence),
                        Map.of("workflow", ID, "evidenceCount", evidence.size())
                ),
                "可靠知识证据已取得，交给统一 LLMActionHandler 生成分步骤答复"
        );
    }

    private WorkflowDecision search(TurnContext context, String query) {
        if (context.getState().getWorkflowState(ID, ORIGINAL_QUERY) == null) {
            context.getState().setWorkflowState(ID, ORIGINAL_QUERY, query);
        }
        context.getState().setWorkflowState(ID, LAST_SEARCH_QUERY, query);
        context.getState().setWorkflowState(ID, WAITING_DETAILS, false);
        Map<String, Object> arguments = new java.util.LinkedHashMap<>();
        arguments.put("query", query);
        arguments.put("knowledge_type", "TROUBLESHOOTING");
        String category = productCategory(query);
        if (!category.isBlank()) {
            arguments.put("product_category", category);
        }
        String model = productModel(query);
        if (!model.isBlank()) {
            arguments.put("product_model", model);
        }
        return WorkflowDecision.next(
                new CapabilityAction(CapabilityIds.KNOWLEDGE_SEARCH, arguments),
                "显式使用 TROUBLESHOOTING Profile 检索知识证据"
        );
    }

    private String buildQuery(TurnContext context) {
        Object waiting = context.getState().getWorkflowState(ID, WAITING_DETAILS);
        Object lastSearch = context.getState().getWorkflowState(ID, LAST_SEARCH_QUERY);
        if (Boolean.TRUE.equals(waiting) && lastSearch != null) {
            return appendSupplement(lastSearch.toString(), context.getMessage());
        }
        return context.getMessage() == null ? "" : context.getMessage();
    }

    private String appendSupplement(String accumulatedQuery, String currentMessage) {
        String accumulated = accumulatedQuery == null ? "" : accumulatedQuery.trim();
        String current = currentMessage == null ? "" : currentMessage.trim();
        if (current.isBlank() || normalize(accumulated).contains(normalize(current))) {
            return accumulated;
        }
        return accumulated + "；补充信息：" + current;
    }

    private String currentSearchQuery(TurnContext context) {
        Object query = context.getState().getWorkflowState(ID, LAST_SEARCH_QUERY);
        return query == null ? context.getMessage() : query.toString();
    }

    private List<KnowledgeSearchHit> reliableEvidence(Object data) {
        if (!(data instanceof List<?> rows)) {
            return List.of();
        }
        return rows.stream()
                .filter(KnowledgeSearchHit.class::isInstance)
                .map(KnowledgeSearchHit.class::cast)
                .filter(hit -> "TROUBLESHOOTING".equalsIgnoreCase(hit.getKnowledgeType())
                        || hit.getDocumentId().startsWith("troubleshooting/"))
                .filter(hit -> !hit.getSnippet().isBlank())
                .toList();
    }

    private String buildEvidencePrompt(String fullQuery, List<KnowledgeSearchHit> evidence) {
        StringBuilder prompt = new StringBuilder("""
                请根据下面的客服知识证据回答商品故障问题。

                强制约束：
                1. 只能使用证据中明确出现的事实和步骤，不得推断或补充知识库未出现的故障原因。
                2. 按编号输出可执行的分步骤排查方案。
                3. 每条关键建议标注对应知识标题或来源；结尾列出“知识来源”。
                4. 证据不足的部分明确说无法确认，并追问型号或现象。
                5. 涉及拆机、进水、焦味、冒烟、鼓包或异常发热时，提示立即停止操作、断开电源并联系人工；
                   不得指导用户自行拆机。

                完整故障描述：%s

                可靠证据：
                """.formatted(fullQuery == null ? "" : fullQuery));
        for (int i = 0; i < evidence.size(); i++) {
            KnowledgeSearchHit hit = evidence.get(i);
            prompt.append("\n[").append(i + 1).append("] 标题：").append(hit.getTitle())
                    .append("\n来源：")
                    .append(hit.getSource().isBlank() ? hit.getDocumentId() : hit.getSource())
                    .append("\n型号：").append(hit.getProductModel())
                    .append("\n内容：").append(hit.getSnippet())
                    .append("\n");
        }
        return prompt.toString();
    }

    private WorkflowDecision handleInterrupt(TurnContext context) {
        GlobalInterruptDecision interrupt = interruptPolicy.evaluate(context.getMessage());
        if (interrupt.getType() == GlobalInterruptType.CANCEL) {
            clearWaitingState(context);
            return WorkflowDecision.next(
                    new CancelWorkflowAction("本次故障排查已取消。"),
                    interrupt.getReason()
            );
        }
        if (interrupt.getType() == GlobalInterruptType.HANDOFF) {
            Map<String, Object> handoffContext = new LinkedHashMap<>(
                    context.getState().getWorkflowStateSnapshot(ID)
            );
            clearWaitingState(context);
            return WorkflowDecision.next(
                    new HandoffAction(
                            interrupt.getHandoffReasonCode(),
                            HandoffSummarySupport.build("商品故障排查", context, interrupt, handoffContext),
                            handoffContext
                    ),
                    interrupt.getReason()
            );
        }
        return null;
    }

    private void clearWaitingState(TurnContext context) {
        context.getState().setWorkflowState(ID, ORIGINAL_QUERY, null);
        context.getState().setWorkflowState(ID, LAST_SEARCH_QUERY, null);
        context.getState().setWorkflowState(ID, WAITING_DETAILS, null);
    }

    private CapabilityObservation latestCapabilityObservation(TurnContext context) {
        Observation observation = context.getLatestObservationByType("capability_result");
        return observation != null && observation.getData() instanceof CapabilityObservation result
                ? result
                : null;
    }

    private String productCategory(String query) {
        String text = normalize(query);
        if (containsAny(text, "耳机", "耳麦", "左右耳")) {
            return "headphones";
        }
        if (containsAny(text, "手表", "腕表")) {
            return "watch";
        }
        if (containsAny(text, "充电器", "充电头")) {
            return "charger";
        }
        if (containsAny(text, "数据线", "线材")) {
            return "cable";
        }
        return "";
    }

    private String productModel(String query) {
        String text = normalize(query);
        if (text.contains("pro max")) {
            return "Pro Max";
        }
        if (text.contains("s2")) {
            return "S2";
        }
        return "";
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
