package com.smartcs.smart_cs_agent.agent.context;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话状态存储
 *
 * 【为什么需要 ConversationStore？】
 * 如果每次用户发消息都 new 一个 ConversationState，
 * Agent 就记不住上一轮正在做什么。
 *
 * 例如：
 * 1. 用户：我要退货
 * 2. Agent：请提供订单号
 * 3. 用户：ORD-004
 *
 * 第 3 步里，用户只说了订单号。
 * 如果没有 ConversationStore，系统不知道这个订单号是在补充退货流程。
 *
 * 【当前实现】
 * 第一版先用内存 Map 保存会话状态。
 * 这适合学习和演示，重启后数据会丢失。
 *
 * 【后续扩展】
 * 如果要做成更接近生产的系统，可以把这个类替换成：
 * - RedisConversationStore
 * - MySqlConversationStore
 * - FileConversationStore
 */
@Component
public class ConversationStore {

    /**
     * 内存会话表：conversationId -> ConversationState
     *
     * 使用 ConcurrentHashMap 是为了比普通 HashMap 更适合 Web 请求场景。
     * 当前项目不是高并发生产系统，但这样做更稳妥。
     */
    private final Map<String, ConversationState> conversations = new ConcurrentHashMap<>();

    /**
     * 根据请求获取会话状态。
     *
     * 如果 conversationId 已存在，说明这是同一个会话的下一轮消息，
     * 我们复用旧状态，并把当前用户消息加入历史。
     *
     * 如果 conversationId 不存在，说明这是一个新会话，
     * 我们创建新的 ConversationState。
     */
    public ConversationState getOrCreate(ChatRequest request) {
        return conversations.compute(request.getConversationId(), (conversationId, existingState) -> {
            if (existingState == null) {
                return new ConversationState(request.getMessage(), request.getUserId());
            }

            // 过渡阶段：当前 Skill 仍然从 ConversationState.getMessage() 读取当前消息。
            // 后续引入 TurnContext 后，会改成从 ChatRequest 读取当前消息。
            existingState.startNewTurn(request.getMessage());
            return existingState;
        });
    }

    /**
     * 保存会话状态。
     *
     * 目前 getOrCreate 返回的是同一个对象，理论上已经在 Map 里了；
     * 这里仍然保留 save 方法，是为了让上层代码表达完整生命周期：
     * 读取状态 -> 执行一轮 -> 保存状态。
     */
    public void save(String conversationId, ConversationState state) {
        conversations.put(conversationId, state);
    }

    /**
     * 读取指定会话状态。
     *
     * 这个方法不会自动创建新会话，适合只读接口使用。
     * 例如前端 Memory 面板想查看当前会话记忆：
     * - 如果会话存在，就返回当前状态；
     * - 如果会话不存在，就让 Controller 返回“会话不存在”。
     */
    public ConversationState get(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return null;
        }
        return conversations.get(conversationId);
    }

    /**
     * 清空指定会话。
     *
     * 后续做“新建会话”或“重置对话”按钮时会用到。
     */
    public void clear(String conversationId) {
        conversations.remove(conversationId);
    }
}
