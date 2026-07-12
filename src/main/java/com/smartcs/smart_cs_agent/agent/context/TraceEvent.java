package com.smartcs.smart_cs_agent.agent.context;

import com.smartcs.smart_cs_agent.observability.trace.TraceDataSanitizer;

import java.util.Map;

/**
 * Agent 执行轨迹事件。
 *
 * 【为什么需要 TraceEvent？】
 * 用户最终只能看到一句回复，但开发者和面试官更关心：
 * - Agent 识别了什么意图？
 * - 计划了哪个 Action？
 * - 调用了哪个工具？
 * - 调用了哪个工具，结果状态和安全摘要是什么？
 * - 是否调用了 LLM？
 *
 * TraceEvent 就是把这些“中间过程”记录下来。
 * 它不会替代最终回复，也不会保存原始 Prompt、用户消息全文或凭据；
 * 它只提供经过统一清洗的调试、演示和评测信息。
 */
public class TraceEvent {

    /** 事件类型，例如 turn_started、action_planned、tool_called */
    private final String eventType;

    /** 当前是本轮第几步 Action，方便按执行顺序查看 */
    private final int step;

    /** 给人看的简短说明，前端调试面板可以直接展示 */
    private final String message;

    /** 结构化细节；构造时会转换成脱敏、限长且不可变的深拷贝 */
    private final Map<String, Object> data;

    /** 事件发生时间，使用毫秒时间戳 */
    private final long timestamp;

    public TraceEvent(String eventType, int step, String message, Map<String, Object> data) {
        this(eventType, step, message, data, System.currentTimeMillis());
    }

    public TraceEvent(String eventType, int step, String message, Map<String, Object> data, long timestamp) {
        this.eventType = eventType;
        this.step = step;
        this.message = TraceDataSanitizer.sanitizeMessage(message);
        // TraceEvent 是统一写入边界：无论事件来自 Runtime、内置 Handler 还是扩展插件，
        // 都只能保存经过脱敏、限长和不可变深拷贝后的可观测副本。
        this.data = TraceDataSanitizer.sanitizeData(data);
        this.timestamp = timestamp;
    }

    public String getEventType() {
        return eventType;
    }

    public int getStep() {
        return step;
    }

    public String getMessage() {
        return message;
    }

    public Map<String, Object> getData() {
        return data;
    }

    public long getTimestamp() {
        return timestamp;
    }

    /** 调试器直接打印事件时，也只会看到已经清洗过的安全快照。 */
    @Override
    public String toString() {
        return "TraceEvent{" +
                "eventType='" + eventType + '\'' +
                ", step=" + step +
                ", message='" + message + '\'' +
                ", data=" + data +
                ", timestamp=" + timestamp +
                '}';
    }
}
