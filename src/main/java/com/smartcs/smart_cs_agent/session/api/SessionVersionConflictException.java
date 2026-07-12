package com.smartcs.smart_cs_agent.session.api;

import com.smartcs.smart_cs_agent.agent.context.ConversationKey;

/**
 * 乐观锁版本冲突。
 *
 * <p>它表示当前执行者读取状态后，另一个实例已经先保存了新版本。抛出异常比静默覆盖更安全，
 * 否则用户刚补充的槽位或 Workflow 状态可能被旧请求覆盖。</p>
 */
public final class SessionVersionConflictException extends RuntimeException {

    private final ConversationKey key;
    private final long expectedVersion;
    private final long actualVersion;

    public SessionVersionConflictException(
            ConversationKey key,
            long expectedVersion,
            long actualVersion) {
        super("Conversation session changed concurrently");
        this.key = key;
        this.expectedVersion = expectedVersion;
        this.actualVersion = actualVersion;
    }

    public ConversationKey getKey() {
        return key;
    }

    public long getExpectedVersion() {
        return expectedVersion;
    }

    public long getActualVersion() {
        return actualVersion;
    }
}
