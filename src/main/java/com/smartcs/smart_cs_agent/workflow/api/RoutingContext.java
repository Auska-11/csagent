package com.smartcs.smart_cs_agent.workflow.api;

import com.smartcs.smart_cs_agent.agent.context.ChatRequest;
import com.smartcs.smart_cs_agent.agent.context.ConversationState;

import java.util.Objects;

/**
 * Router 做首次匹配时需要的只读上下文。
 *
 * <p>同时提供 ChatRequest 和 ConversationState 是为了支持多轮任务：最新一句话可能只有
 * “质量问题”，但会话状态能说明用户上一轮正在填写退货原因。</p>
 *
 * <p>本类不提供工具执行方法，避免 Router 在选择 Workflow 时产生业务副作用。</p>
 */
public final class RoutingContext {

    private final ChatRequest request;
    private final ConversationState state;

    public RoutingContext(ChatRequest request, ConversationState state) {
        this.request = Objects.requireNonNull(request, "request cannot be null");
        this.state = Objects.requireNonNull(state, "state cannot be null");
    }

    public ChatRequest getRequest() {
        return request;
    }

    public ConversationState getState() {
        return state;
    }

    public String getMessage() {
        return request.getMessage();
    }
}
