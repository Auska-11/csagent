package com.smartcs.smart_cs_agent.eval.baseline;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartcs.smart_cs_agent.agent.context.ChatRequest;
import com.smartcs.smart_cs_agent.agent.context.ChatResponse;
import com.smartcs.smart_cs_agent.agent.context.ExecutionContext;
import com.smartcs.smart_cs_agent.agent.context.TraceEvent;
import com.smartcs.smart_cs_agent.agent.llm.LlmExecutionGuard;
import com.smartcs.smart_cs_agent.capability.api.CapabilityCall;
import com.smartcs.smart_cs_agent.capability.api.CapabilityGateway;
import com.smartcs.smart_cs_agent.capability.api.CapabilityResult;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 基线 Agent：朴素单循环 Function Calling 编排（76 号计划 3.4 节）。
 *
 * <p>循环：用户消息 → 模型（带工具清单）→ 若返回工具调用则经同一 {@link CapabilityGateway}
 * 执行、结果回填给模型继续生成，直到模型给出最终文本。与 Workflow 侧刻意保持的差异只有
 * 编排本身：没有状态机、Skill 白名单、显式确认门控和模板降级；保留的是任何合格实现都该有的
 * 步数/时间预算（公平原则：基线不弱智化，只去掉编排机制）。</p>
 *
 * <p>公平性设计：
 * <ul>
 *   <li>同一个 Spring {@code ChatLanguageModel} Bean（同模型同采样参数）；
 *   <li>工具 schema 由 {@link BaselineToolCatalog} 从 MCP catalog 逐字导出；
 *   <li>Trace 事件词汇与 Workflow Runtime 完全一致（tool_called/tool_observed/llm_*），
 *       因此 Monitor 分类和场景断言两侧同口径；
 *   <li>副作用幂等键按"每次调用唯一"生成——Workflow 侧的会话级稳定键依赖状态机
 *       （同一流程内重复提交被去重），单循环没有等价的会话语义，按调用唯一是
 *       无编排下合格工程师的自然写法，这一差异属于被测变量而非实现缺陷。</li>
 * </ul></p>
 */
@Component
public class BaselineFunctionCallingAgent {

    /** 无响应文本时的兜底回复；模型返回空轮（既无文本也无工具调用）视为失败。 */
    private static final String PURPOSE = "baseline_function_calling";

    private final ChatLanguageModel chatModel;
    private final LlmExecutionGuard executionGuard;
    private final CapabilityGateway capabilityGateway;
    private final BaselineToolCatalog toolCatalog;
    private final BaselineSystemPrompt systemPrompt;
    private final BaselineConversationStore conversationStore;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final long turnBudgetMs;
    private final int maxIterations;

    public BaselineFunctionCallingAgent(ChatLanguageModel chatModel,
                                        LlmExecutionGuard executionGuard,
                                        CapabilityGateway capabilityGateway,
                                        BaselineToolCatalog toolCatalog,
                                        BaselineSystemPrompt systemPrompt,
                                        BaselineConversationStore conversationStore,
                                        @Value("${smartcs.eval.baseline.turn-budget-ms:30000}") long turnBudgetMs,
                                        @Value("${smartcs.eval.baseline.max-iterations:8}") int maxIterations) {
        if (turnBudgetMs <= 0 || maxIterations <= 0) {
            throw new IllegalArgumentException("Baseline turn-budget-ms and max-iterations must be positive");
        }
        this.chatModel = chatModel;
        this.executionGuard = executionGuard;
        this.capabilityGateway = capabilityGateway;
        this.toolCatalog = toolCatalog;
        this.systemPrompt = systemPrompt;
        this.conversationStore = conversationStore;
        this.turnBudgetMs = turnBudgetMs;
        this.maxIterations = maxIterations;
    }

    public ChatResponse chat(ChatRequest request) {
        List<TraceEvent> trace = new ArrayList<>();
        String conversationId = request.getConversationId();
        long deadlineNanos = System.nanoTime() + turnBudgetMs * 1_000_000L;

        trace.add(new TraceEvent("turn_started", 0, "基线单循环处理用户消息", Map.of(
                "orchestration", "baseline_function_calling",
                "messagePreview", preview(request.getMessage(), 120)
        )));

        // 全量历史策略：system prompt 不入库，每次调用时拼在最前。
        conversationStore.append(conversationId, UserMessage.from(request.getMessage()));
        List<ChatMessage> history = conversationStore.historyOf(conversationId);

        List<ToolSpecification> tools = toolCatalog.toolSpecifications();
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(systemPrompt.content()));
        messages.addAll(history);

        for (int iteration = 1; iteration <= maxIterations; iteration++) {
            long remainingNanos = deadlineNanos - System.nanoTime();
            trace.add(new TraceEvent("llm_called", iteration, "调用大模型：" + PURPOSE, Map.of(
                    "purpose", PURPOSE,
                    "iteration", iteration,
                    "messageCount", messages.size(),
                    "toolCount", tools.size()
            )));

            LlmExecutionGuard.ToolAwareInvocationResult invocation = executionGuard.generateWithTools(
                    chatModel, messages, tools, remainingNanos);
            if (!invocation.success()) {
                trace.add(new TraceEvent("llm_failed", iteration, "大模型调用失败，基线整轮失败", Map.of(
                        "purpose", PURPOSE,
                        "durationMs", invocation.durationMs(),
                        "budgetMs", invocation.budgetMs(),
                        "errorCode", invocation.errorCode()
                )));
                throw new BaselineTurnFailedException(
                        invocation.errorCode(),
                        "Baseline function-calling turn failed at iteration " + iteration
                );
            }

            AiMessage aiMessage = invocation.content();
            if (!aiMessage.hasToolExecutionRequests()) {
                String reply = aiMessage.text() == null || aiMessage.text().isBlank()
                        ? "（模型未返回内容）"
                        : aiMessage.text();
                conversationStore.append(conversationId, AiMessage.from(reply));
                trace.add(new TraceEvent("final_answer", iteration, "基线生成最终回复", Map.of(
                        "iterations", iteration,
                        "replyPreview", preview(reply, 160)
                )));
                return new ChatResponse(conversationId, request.getUserId(), reply, trace);
            }

            // 模型请求工具：把 AI 消息入列，逐个执行，结果以 Tool 消息回填，继续下一轮生成。
            messages.add(aiMessage);
            conversationStore.append(conversationId, aiMessage);
            for (ToolExecutionRequest toolRequest : aiMessage.toolExecutionRequests()) {
                ToolExecutionResultMessage resultMessage = executeToolCall(
                        request, conversationId, iteration, toolRequest, trace);
                messages.add(resultMessage);
                conversationStore.append(conversationId, resultMessage);
            }
        }

        trace.add(new TraceEvent("turn_deadline_exceeded", maxIterations,
                "基线步数预算耗尽，整轮失败", Map.of(
                        "purpose", PURPOSE,
                        "maxIterations", maxIterations
                )));
        throw new BaselineTurnFailedException(
                "BASELINE_STEP_BUDGET_EXCEEDED",
                "Baseline function-calling loop exceeded max iterations: " + maxIterations
        );
    }

    /** 执行单个工具调用：名称映射 → 参数解析 → 同一 Gateway → 结果 JSON 回填给模型。 */
    private ToolExecutionResultMessage executeToolCall(ChatRequest request,
                                                       String conversationId,
                                                       int iteration,
                                                       ToolExecutionRequest toolRequest,
                                                       List<TraceEvent> trace) {
        String toolName = toolRequest.name() == null ? "" : toolRequest.name();
        String capabilityId = toolCatalog.toCapabilityId(toolName);
        if (capabilityId == null) {
            // 未知工具：不能崩掉整轮，把错误以工具结果形式反馈给模型让它自行纠正。
            Map<String, Object> unknown = Map.of(
                    "outcome", "TECHNICAL_FAILURE",
                    "errorCode", "UNKNOWN_TOOL",
                    "message", "Unknown tool: " + toolName
            );
            trace.add(new TraceEvent("tool_failed", iteration, "未知工具：" + toolName, Map.of(
                    "capability", toolName,
                    "errorCode", "UNKNOWN_TOOL"
            )));
            return ToolExecutionResultMessage.from(
                    toolRequest, serialize(unknown));
        }

        Map<String, Object> arguments = parseArguments(toolRequest.arguments());
        trace.add(new TraceEvent("tool_called", iteration, "调用工具：" + capabilityId, Map.of(
                "capability", capabilityId,
                "arguments", arguments
        )));

        long startedAtNanos = System.nanoTime();
        CapabilityResult<?> result;
        try {
            result = capabilityGateway.invoke(
                    new CapabilityCall(capabilityId, arguments),
                    buildExecutionContext(request, conversationId, iteration, capabilityId)
            );
        } catch (RuntimeException failure) {
            trace.add(new TraceEvent("tool_failed", iteration, "工具调用失败：" + capabilityId, Map.of(
                    "capability", capabilityId,
                    "durationMs", elapsedMillis(startedAtNanos),
                    "error", String.valueOf(failure.getClass().getSimpleName())
            )));
            Map<String, Object> errorPayload = Map.of(
                    "outcome", "TECHNICAL_FAILURE",
                    "errorCode", "CAPABILITY_PROVIDER_ERROR",
                    "message", "Capability invocation failed"
            );
            return ToolExecutionResultMessage.from(toolRequest, serialize(errorPayload));
        }

        long durationMs = elapsedMillis(startedAtNanos);
        Map<String, Object> observed = new LinkedHashMap<>();
        observed.put("capability", capabilityId);
        observed.put("durationMs", durationMs);
        observed.put("success", result.isSuccess());
        observed.put("outcome", result.getOutcome().name());
        observed.put("errorCode", result.getErrorCode());
        observed.put("providerId", result.getProviderId());
        observed.put("replayed", result.isReplayed());
        observed.put("messagePreview", preview(result.getMessage(), 200));
        trace.add(new TraceEvent("tool_observed", iteration, "工具返回结果：" + capabilityId, observed));

        return ToolExecutionResultMessage.from(toolRequest, serialize(toToolPayload(result)));
    }

    /**
     * 回填给模型的工具结果：outcome/success/errorCode/message/data。
     * 与 Workflow 侧 Handler 把结果写进 Observation 再进 Prompt 的信息量对齐。
     */
    private Map<String, Object> toToolPayload(CapabilityResult<?> result) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("outcome", result.getOutcome().name());
        payload.put("success", result.isSuccess());
        payload.put("errorCode", result.getErrorCode());
        payload.put("message", result.getMessage());
        payload.put("data", result.getData());
        return payload;
    }

    private ExecutionContext buildExecutionContext(ChatRequest request,
                                                   String conversationId,
                                                   int iteration,
                                                   String capabilityId) {
        // 幂等键按调用唯一：见类注释（会话级稳定键是状态机编排的特性）。
        String idempotencyKey = "baseline:" + conversationId
                + ":" + UUID.randomUUID() + ":" + capabilityId;
        return new ExecutionContext(
                conversationId + ":baseline-iter-" + iteration,
                request.getPrincipal(),
                request.getTenantId(),
                conversationId,
                idempotencyKey,
                Instant.now().plusSeconds(30)
        );
    }

    private Map<String, Object> parseArguments(String rawArguments) {
        if (rawArguments == null || rawArguments.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(rawArguments, new TypeReference<Map<String, Object>>() {
            });
        } catch (JsonProcessingException failure) {
            return Map.of();
        }
    }

    private String serialize(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException failure) {
            return "{\"outcome\":\"TECHNICAL_FAILURE\",\"errorCode\":\"RESULT_SERIALIZATION_FAILED\"}";
        }
    }

    private String preview(String text, int limit) {
        if (text == null) {
            return "";
        }
        String compact = text.replaceAll("\\s+", " ").trim();
        return compact.length() <= limit ? compact : compact.substring(0, limit) + "…";
    }

    private long elapsedMillis(long startedAtNanos) {
        return Math.max(0, (System.nanoTime() - startedAtNanos) / 1_000_000);
    }
}
