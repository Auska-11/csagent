package com.smartcs.smart_cs_agent.eval.scenario;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 场景评测用的可编程 ChatLanguageModel：默认返回固定客服回复，按 {@link FaultMode}
 * 注入失败或超时，保证默认测试与 CI 不依赖网络（76 号计划的 CI 策略）。
 *
 * <p>真实模型跑批（M4）不注册本类为业务模型，而是复用同一套静态 {@link FaultMode} 开关：
 * ScenarioComparisonRealModelEvaluationTest 用故障感知装饰器包住 OpenRouter 真实模型，
 * failure/timeout 对两侧被测方对称注入（公平原则第 2/8 条），NORMAL 时透传真实调用并节流。</p>
 *
 * <p>模式开关用静态变量而不是 Spring 作用域，是因为 MockMvc 请求与测试断言在同一个
 * 线程内串行执行，测试线程在发 HTTP 请求前设置、请求返回后复位即可；Runner 负责
 * 每轮结束把模式恢复为 NORMAL，避免故障泄漏到后续场景。</p>
 */
public class ScriptedFaultChatModel implements ChatLanguageModel {

    /** LLM 通道故障模式。 */
    public enum FaultMode {
        /** 正常返回固定回复。 */
        NORMAL,
        /** 模型直接抛异常，走 LLM_PROVIDER_ERROR 降级路径。 */
        FAILURE,
        /** 模型挂起直到 LlmExecutionGuard 超时，走 LLM_CALL_DEADLINE_EXCEEDED 降级路径。 */
        TIMEOUT
    }

    /**
     * 超时模式的挂起时长。只要大于测试上下文配置的 smartcs.llm.execution.call-timeout-ms
     * 即可触发 Guard 超时；Guard 会在超时后立刻放行请求线程，遗留的 sleep 线程是
     * 守护线程，不会拖慢测试收尾。
     */
    static final long TIMEOUT_SLEEP_MILLIS = 8_000;

    private static final AtomicReference<FaultMode> MODE = new AtomicReference<>(FaultMode.NORMAL);

    /**
     * 固定回复刻意写成合规客服话术：不承诺额外权益、引导平台内操作。
     * 它同时是闲聊类场景在 Fake LLM 下的回复基线。
     */
    static final String CANNED_REPLY =
            "您好，我是官方智能客服。退换货、退款等业务请直接在平台内提交申请，"
                    + "平台外的私下交易无法保障您的权益。请问还有什么可以帮您？";

    private final long timeoutSleepMillis;

    public ScriptedFaultChatModel() {
        this(TIMEOUT_SLEEP_MILLIS);
    }

    ScriptedFaultChatModel(long timeoutSleepMillis) {
        this.timeoutSleepMillis = timeoutSleepMillis;
    }

    public static void setMode(FaultMode mode) {
        MODE.set(mode == null ? FaultMode.NORMAL : mode);
    }

    public static FaultMode currentMode() {
        return MODE.get();
    }

    @Override
    public Response<AiMessage> generate(List<ChatMessage> messages) {
        FaultMode mode = MODE.get();
        switch (mode) {
            case FAILURE -> throw new IllegalStateException("injected-llm-failure");
            case TIMEOUT -> {
                try {
                    Thread.sleep(timeoutSleepMillis);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                // 超时后仍返回内容；正常情况下 Guard 已超时放弃，这行只是兜底。
                return Response.from(AiMessage.from(CANNED_REPLY));
            }
            default -> {
                return Response.from(AiMessage.from(CANNED_REPLY));
            }
        }
    }

    private static final Pattern ORDER_ID_PATTERN = Pattern.compile("ORD-\\d+");

    /**
     * 工具感知调用：基线 Function Calling Agent 走这条路径（Workflow 侧不传工具清单）。
     *
     * <p>NORMAL 模式下模拟一个"会用工具的模型"的确定性行为，用于结构测试：
     * <ul>
     *   <li>上一条消息是工具结果 → 把结果内容转述为最终回复（不再发工具调用）；</li>
     *   <li>政策/保修/发票/故障排查诉求 → knowledge_search（真实模型同样会优先查知识库，
     *       脚本里此分支必须先于订单/退货分支，避免“这个订单的商品有保修吗”被订单分支劫持）；</li>
     *   <li>消息里有订单号 → order_lookup；提到退货/退款且有订单号 → return_apply（直接提交，
     *       模拟无确认门控的单循环行为，正是对照实验要测量的差异）；</li>
     *   <li>其他订单诉求 → order_list-by-user（与 BaselineToolCatalog 导出的工具名一字不差）；</li>
     *   <li>商品咨询（有没有/多少钱/价格/推荐）→ product_search；</li>
     *   <li>其余 → 纯文本回复。</li>
     * </ul>
     * 这套脚本是测试夹具，不是能力声明：真实模型下的对照数字由 M4 跑批产出。</p>
     */
    @Override
    public Response<AiMessage> generate(List<ChatMessage> messages, List<ToolSpecification> toolSpecifications) {
        FaultMode mode = MODE.get();
        switch (mode) {
            case FAILURE -> throw new IllegalStateException("injected-llm-failure");
            case TIMEOUT -> {
                try {
                    Thread.sleep(timeoutSleepMillis);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                return Response.from(AiMessage.from(CANNED_REPLY));
            }
            default -> {
                return Response.from(scriptedFunctionCall(messages));
            }
        }
    }

    private AiMessage scriptedFunctionCall(List<ChatMessage> messages) {
        ChatMessage last = messages.get(messages.size() - 1);
        if (last instanceof ToolExecutionResultMessage toolResult) {
            // 工具结果已回填：转述为最终回复，结束本轮循环。
            return AiMessage.from("根据查询结果回复如下：\n" + toolResult.text());
        }

        String userMessage = last instanceof UserMessage user && user.singleText() != null
                ? user.singleText()
                : "";

        if (userMessage.contains("没声音") || userMessage.contains("充不进")
                || userMessage.contains("故障") || userMessage.contains("排查")
                || userMessage.contains("政策") || userMessage.contains("保修")
                || userMessage.contains("发票")) {
            return toolCall("knowledge_search", "{\"query\":\"" + escape(userMessage) + "\"}");
        }

        Matcher orderId = ORDER_ID_PATTERN.matcher(userMessage);

        if (orderId.find()) {
            String id = orderId.group();
            if (userMessage.contains("退货") || userMessage.contains("退款")) {
                return toolCall("return_apply",
                        "{\"order_id\":\"" + id + "\",\"reason\":\"质量问题\"}");
            }
            return toolCall("order_lookup", "{\"order_id\":\"" + id + "\"}");
        }
        if (userMessage.contains("退货") || userMessage.contains("退款")) {
            return AiMessage.from("好的，请先提供需要退货的订单号。");
        }
        if (userMessage.contains("继续")) {
            // “继续”且历史里有工具结果 → 转述历史结果；用于验证基线把全量会话历史传给了模型。
            String priorResult = lastToolResultBefore(messages);
            if (priorResult != null) {
                return AiMessage.from("继续为您说明：\n" + priorResult);
            }
        }
        if (userMessage.contains("订单")) {
            return toolCall("order_list-by-user", "{}");
        }
        if (userMessage.contains("有没有") || userMessage.contains("多少钱")
                || userMessage.contains("价格") || userMessage.contains("推荐")) {
            return toolCall("product_search", "{\"query\":\"" + escape(userMessage) + "\"}");
        }
        return AiMessage.from(CANNED_REPLY);
    }

    private AiMessage toolCall(String name, String arguments) {
        return AiMessage.from(List.of(ToolExecutionRequest.builder()
                .id("scripted-" + name + "-" + System.nanoTime())
                .name(name)
                .arguments(arguments)
                .build()));
    }

    private String lastToolResultBefore(List<ChatMessage> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof ToolExecutionResultMessage toolResult) {
                return toolResult.text();
            }
        }
        return null;
    }

    private String escape(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
