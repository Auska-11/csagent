package com.smartcs.smart_cs_agent.agent.context;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

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
 * 第一版先用内存 Map 保存会话状态，并使用 ConversationKey 隔离租户和用户。
 * 这适合学习和演示，重启后数据仍会丢失。
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
     * 内存会话表：(tenantId, userId, conversationId) -> ConversationState
     *
     * 使用 ConcurrentHashMap 是为了比普通 HashMap 更适合 Web 请求场景。
     * 当前项目不是高并发生产系统，但这样做更稳妥。
     */
    private final Map<ConversationKey, ConversationState> conversations = new ConcurrentHashMap<>();

    /**
     * 每个完整 ConversationKey 一把独立锁。
     *
     * <p>不能只依赖 ConcurrentHashMap：它只能保证 Map 自身操作安全，不能保证
     * “读取状态 -> 执行多步 Workflow -> 保存状态”这一整段业务事务不被另一个请求插入。</p>
     *
     * <p>锁和状态必须使用同一种 key。如果状态按复合键隔离，锁却仍然只按 conversationId，
     * 不同用户碰巧使用同名会话时就会无故互相阻塞。</p>
     */
    private final Map<ConversationKey, ReentrantLock> conversationLocks = new ConcurrentHashMap<>();

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
        ConversationKey key = ConversationKey.from(request);
        return conversations.compute(key, (conversationKey, existingState) -> {
            if (existingState == null) {
                return new ConversationState(request.getMessage(), request.getUserId());
            }

            // 复合键已经隔离 owner；这里再检查一次 Map 中状态是否满足不变量。
            // 如果未来持久化适配器把错误用户的数据装进这个 key，可以尽早失败而不是继续污染数据。
            if (!existingState.getUserId().equals(request.getUserId())) {
                throw new IllegalArgumentException(
                        "Conversation state owner does not match storage key"
                );
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
    public void save(ConversationKey key, ConversationState state) {
        if (key == null) {
            throw new IllegalArgumentException("conversation key cannot be null");
        }
        if (state == null) {
            throw new IllegalArgumentException("conversation state cannot be null");
        }
        if (!key.userId().equals(state.getUserId())) {
            throw new IllegalArgumentException("Conversation state owner does not match storage key");
        }
        conversations.put(key, state);
    }

    /**
     * 在会话级串行锁内执行完整的一轮处理。
     *
     * <p>不同 conversationId 使用不同锁，仍然可以并行；只有同一会话的请求会排队。
     * operation 成功后统一保存，调用方不需要忘记 save。</p>
     */
    public <T> T executeSerially(ChatRequest request, Function<ConversationState, T> operation) {
        if (request == null) {
            throw new IllegalArgumentException("request cannot be null");
        }
        if (operation == null) {
            throw new IllegalArgumentException("conversation operation cannot be null");
        }

        ConversationKey key = ConversationKey.from(request);
        ReentrantLock lock = conversationLocks.computeIfAbsent(key, ignored -> new ReentrantLock());
        lock.lock();
        try {
            ConversationState state = getOrCreate(request);
            T result = operation.apply(state);
            save(key, state);
            return result;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 读取指定会话状态。
     *
     * 这个方法不会自动创建新会话，适合只读接口使用。
     * 例如前端 Memory 面板想查看当前会话记忆：
     * - 如果会话存在，就返回当前状态；
     * - 如果会话不存在，就让 Controller 返回“会话不存在”。
     */
    public ConversationState get(ConversationKey key) {
        if (key == null) {
            return null;
        }
        return conversations.get(key);
    }

    /**
     * 清空指定会话。
     *
     * 后续做“新建会话”或“重置对话”按钮时会用到。
     */
    public boolean clear(ConversationKey key) {
        if (key == null) {
            return false;
        }

        // clear 也要进入同一把锁。如果一轮 Workflow 正在执行，clear 会等它保存完成后再删除；
        // 否则可能出现“用户刚清空，旧请求又在 finally 前把状态保存回来”的竞态。
        ReentrantLock lock = conversationLocks.computeIfAbsent(key, ignored -> new ReentrantLock());
        lock.lock();
        try {
            return conversations.remove(key) != null;
        } finally {
            lock.unlock();
            // 不在这里移除 lock：可能还有请求已经拿到了这把锁并正在等待。
            // 少量轻量 ReentrantLock 留在内存中比制造两把并发锁更安全。
        }
    }
}
