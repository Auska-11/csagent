package com.smartcs.smart_cs_agent.agent.context;

import com.smartcs.smart_cs_agent.session.api.SessionRecord;
import com.smartcs.smart_cs_agent.session.api.SessionStore;
import com.smartcs.smart_cs_agent.session.memory.InMemorySessionStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
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
 * ConversationStore 只管理一轮会话的加载、锁和保存，真正的数据存储由 SessionStore SPI 提供。
 * 默认是 InMemorySessionStore；smartcs.persistence.mode=jdbc 时会自动切换为 JdbcSessionStore。
 *
 * 【后续扩展】
 * 新增 Redis 或远程 Session 服务时实现 SessionStore 即可，不需要替换 Agent 入口。
 */
@Component
public class ConversationStore {

    /**
     * 持久化 SPI。ConversationStore 不知道底层是 Map、MySQL 还是其他服务。
     */
    private final SessionStore sessionStore;

    /** Workflow 私有状态的版本校验、逐级迁移和保存版本由这个注册表统一负责。 */
    private final WorkflowStateMigrationRegistry workflowStateMigrationRegistry;

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

    /** Spring 生产构造函数，会注入当前配置选中的 SessionStore 实现。 */
    @Autowired
    public ConversationStore(
            SessionStore sessionStore,
            WorkflowStateMigrationRegistry workflowStateMigrationRegistry) {
        if (sessionStore == null) {
            throw new IllegalArgumentException("sessionStore cannot be null");
        }
        if (workflowStateMigrationRegistry == null) {
            throw new IllegalArgumentException("workflowStateMigrationRegistry cannot be null");
        }
        this.sessionStore = sessionStore;
        this.workflowStateMigrationRegistry = workflowStateMigrationRegistry;
    }

    /** 兼容不启动 Spring、只关心 v1 状态的 SessionStore 单元测试。 */
    public ConversationStore(SessionStore sessionStore) {
        this(sessionStore, new WorkflowStateMigrationRegistry(List.of()));
    }

    /**
     * 兼容大量不启动 Spring 的单元测试。
     *
     * <p>生产环境不会使用这个构造函数；测试默认得到一个全新的内存 SessionStore。</p>
     */
    public ConversationStore() {
        this(new InMemorySessionStore());
    }

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
        return executeSerially(request, state -> state);
    }

    /**
     * 保存会话状态。
     *
     * 这个兼容入口会先读取最新版本，再使用 CAS 保存。
     * 正常 Agent 主链路应优先使用 executeSerially，让加载、业务执行和保存处于同一把本地锁中。
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

        ReentrantLock lock = conversationLocks.computeIfAbsent(key, ignored -> new ReentrantLock());
        lock.lock();
        try {
            long expectedVersion = sessionStore.load(key).map(SessionRecord::version).orElse(0L);
            sessionStore.save(
                    key,
                    expectedVersion,
                    workflowStateMigrationRegistry.snapshotForPersistence(state)
            );
        } finally {
            lock.unlock();
        }
    }

    /**
     * 在会话级串行锁内执行完整的一轮处理。
     *
     * <p>不同 ConversationKey 使用不同锁，仍然可以并行；只有同一 owner 的同一会话会排队。
     * operation 成功后使用读取时的版本做 CAS 保存，调用方不需要手工管理版本。</p>
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
            LoadedConversation loaded = loadForTurn(key, request);
            ConversationState state = loaded.state();
            T result = operation.apply(state);
            sessionStore.save(
                    key,
                    loaded.version(),
                    workflowStateMigrationRegistry.snapshotForPersistence(state)
            );
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
        return sessionStore.load(key)
                .map(record -> restoreAndValidate(key, record))
                .orElse(null);
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
            SessionRecord record = sessionStore.load(key).orElse(null);
            return record != null && sessionStore.delete(key, record.version());
        } finally {
            lock.unlock();
            // 不在这里移除 lock：可能还有请求已经拿到了这把锁并正在等待。
            // 少量轻量 ReentrantLock 留在内存中比制造两把并发锁更安全。
        }
    }

    private LoadedConversation loadForTurn(ConversationKey key, ChatRequest request) {
        SessionRecord record = sessionStore.load(key).orElse(null);
        if (record == null) {
            return new LoadedConversation(
                    new ConversationState(request.getMessage(), request.getUserId()),
                    0L
            );
        }

        ConversationState state = restoreAndValidate(key, record);
        if (!state.getUserId().equals(request.getUserId())) {
            throw new IllegalArgumentException("Conversation state owner does not match request");
        }

        // 当前 Skill 仍有少量兼容代码从 ConversationState.getMessage() 读取本轮消息。
        state.startNewTurn(request.getMessage());
        return new LoadedConversation(state, record.version());
    }

    private ConversationState restoreAndValidate(ConversationKey key, SessionRecord record) {
        if (!key.equals(record.key())) {
            throw new IllegalStateException("SessionStore returned a record for the wrong key");
        }
        if (!key.userId().equals(record.snapshot().userId())) {
            throw new IllegalStateException("Session snapshot owner does not match storage key");
        }
        return workflowStateMigrationRegistry.restore(record.snapshot());
    }

    private record LoadedConversation(ConversationState state, long version) {
    }
}
