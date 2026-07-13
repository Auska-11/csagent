package com.smartcs.smart_cs_agent.agent.handler.core;

import com.smartcs.smart_cs_agent.agent.action.AgentAction;
import com.smartcs.smart_cs_agent.agent.handler.api.ActionExecutionResult;
import com.smartcs.smart_cs_agent.agent.handler.api.ActionHandler;
import com.smartcs.smart_cs_agent.agent.handler.api.ActionHandlingContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Action 类型到 Handler 的不可变插件注册表。
 *
 * <p>Spring 自动注入所有 ActionHandler Bean。重复类型在启动时失败，未知 Action 在执行前明确失败，
 * 避免“某个 if 没覆盖到”后 Runtime 静默空转直到超时。</p>
 */
@Component
public class ActionHandlerRegistry {

    private final Map<Class<? extends AgentAction>, ActionHandler<? extends AgentAction>> handlers;

    @Autowired
    public ActionHandlerRegistry(List<ActionHandler<? extends AgentAction>> handlers) {
        this((Collection<ActionHandler<? extends AgentAction>>) handlers);
    }

    /** 纯 Java 测试可直接传入 Handler 集合。 */
    public ActionHandlerRegistry(Collection<ActionHandler<? extends AgentAction>> handlers) {
        Map<Class<? extends AgentAction>, ActionHandler<? extends AgentAction>> index = new LinkedHashMap<>();
        if (handlers != null) {
            for (ActionHandler<? extends AgentAction> handler : handlers) {
                ActionHandler<? extends AgentAction> duplicate = index.putIfAbsent(handler.actionType(), handler);
                if (duplicate != null) {
                    throw new IllegalArgumentException(
                            "Duplicate ActionHandler for " + handler.actionType().getName()
                    );
                }
            }
        }
        this.handlers = Collections.unmodifiableMap(index);
    }

    public ActionExecutionResult execute(AgentAction action, ActionHandlingContext context) {
        return invoke(requireHandler(action), action, context);
    }

    public Map<String, Object> describe(AgentAction action) {
        return describeWith(requireHandler(action), action);
    }

    public int size() {
        return handlers.size();
    }

    /** 返回将处理该 Action 的稳定 Handler 标识，供 Trace 使用。 */
    public String handlerId(AgentAction action) {
        return requireHandler(action).getClass().getSimpleName();
    }

    private ActionHandler<? extends AgentAction> requireHandler(AgentAction action) {
        if (action == null) {
            throw new IllegalArgumentException("Action cannot be null");
        }
        ActionHandler<? extends AgentAction> handler = handlers.get(action.getClass());
        if (handler == null) {
            throw new ActionHandlerNotFoundException(action.getClass().getName());
        }
        return handler;
    }

    /** 泛型强转只集中在注册表边界，具体 Handler 仍保持编译期类型安全。 */
    @SuppressWarnings("unchecked")
    private <A extends AgentAction> ActionExecutionResult invoke(ActionHandler<?> rawHandler,
                                                                  AgentAction rawAction,
                                                                  ActionHandlingContext context) {
        ActionHandler<A> handler = (ActionHandler<A>) rawHandler;
        return handler.handle((A) rawAction, context);
    }

    @SuppressWarnings("unchecked")
    private <A extends AgentAction> Map<String, Object> describeWith(ActionHandler<?> rawHandler,
                                                                     AgentAction rawAction) {
        ActionHandler<A> handler = (ActionHandler<A>) rawHandler;
        return handler.describe((A) rawAction);
    }
}
