package com.smartcs.smart_cs_agent.agent.context;

/** Workflow 状态迁移器缺失、执行失败或生成了非法 payload。 */
public final class WorkflowStateMigrationException extends RuntimeException {

    public WorkflowStateMigrationException(String message) {
        super(message);
    }

    public WorkflowStateMigrationException(String message, Throwable cause) {
        super(message, cause);
    }
}
