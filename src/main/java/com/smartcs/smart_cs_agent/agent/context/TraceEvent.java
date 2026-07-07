package com.smartcs.smart_cs_agent.agent.context;

import java.util.Collections;
import java.util.Map;

/**
 * Agent 执行轨迹事件。
 *
 * 【为什么需要 TraceEvent？】
 * 用户最终只能看到一句回复，但开发者和面试官更关心：
 * - Agent 识别了什么意图？
 * - 计划了哪个 Action？
 * - 调用了哪个工具？
 * - 工具参数和结果是什么？
 * - 是否调用了 LLM？
 *
 * TraceEvent 就是把这些“中间过程”记录下来。
 * 它不会替代最终回复，而是帮助我们调试、演示和后续做评测。
 */
public class TraceEvent {

    /** 事件类型，例如 turn_started、action_planned、tool_called */
    private final String eventType;

    /** 当前是本轮第几步 Action，方便按执行顺序查看 */
    private final int step;

    /** 给人看的简短说明，前端调试面板可以直接展示 */
    private final String message;

    /** 结构化细节，例如工具名、参数、LLM 用途等 */
    private final Map<String, Object> data;

    /** 事件发生时间，使用毫秒时间戳 */
    private final long timestamp;

    public TraceEvent(String eventType, int step, String message, Map<String, Object> data) {
        this(eventType, step, message, data, System.currentTimeMillis());
    }

    public TraceEvent(String eventType, int step, String message, Map<String, Object> data, long timestamp) {
        this.eventType = eventType;
        this.step = step;
        this.message = message;
        this.data = data == null ? Collections.emptyMap() : data;
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
}
