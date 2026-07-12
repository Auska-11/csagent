package com.smartcs.smart_cs_agent.session.api;

import com.smartcs.smart_cs_agent.agent.context.ConversationKey;
import com.smartcs.smart_cs_agent.agent.context.ConversationStateSnapshot;

import java.util.Optional;

/**
 * 会话持久化 SPI。
 *
 * <p>ConversationStore 负责一轮执行的锁和生命周期；SessionStore 只负责版本化读写。以后替换成
 * Redis、PostgreSQL 或远程 Session 服务时，不需要修改 Agent 和 Workflow。</p>
 */
public interface SessionStore {

    Optional<SessionRecord> load(ConversationKey key);

    /**
     * 使用乐观锁保存快照。
     *
     * @param expectedVersion 0 表示只能新建；大于 0 表示数据库中必须仍是该版本
     * @return 保存后的新版本记录
     * @throws SessionVersionConflictException 记录已经被另一个执行者修改
     */
    SessionRecord save(
            ConversationKey key,
            long expectedVersion,
            ConversationStateSnapshot snapshot
    );

    /** 仅当当前版本仍等于 expectedVersion 时删除；记录不存在时返回 false。 */
    boolean delete(ConversationKey key, long expectedVersion);
}
