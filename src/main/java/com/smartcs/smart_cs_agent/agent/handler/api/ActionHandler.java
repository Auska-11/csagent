package com.smartcs.smart_cs_agent.agent.handler.api;

import com.smartcs.smart_cs_agent.agent.action.AgentAction;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一种 AgentAction 的可插拔执行器。
 *
 * @param <A> 本 Handler 唯一负责的 Action 类型
 */
public interface ActionHandler<A extends AgentAction> {

    /** Registry 使用这个类型建立 Action Class -> Handler 索引。 */
    Class<A> actionType();

    /** 执行 Action，并返回统一循环控制结果。 */
    ActionExecutionResult handle(A action, ActionHandlingContext context);

    /**
     * 生成 action_planned Trace 的可观察字段。
     * 默认只记录类型；具体 Handler 可以增加 capability、purpose 等字段。
     */
    default Map<String, Object> describe(A action) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("actionType", actionType().getSimpleName());
        return data;
    }
}
