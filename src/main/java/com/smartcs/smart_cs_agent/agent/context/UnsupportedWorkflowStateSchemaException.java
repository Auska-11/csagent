package com.smartcs.smart_cs_agent.agent.context;

/** 当前迁移注册表无法读取某个 Workflow 的状态版本。 */
public final class UnsupportedWorkflowStateSchemaException extends RuntimeException {

    public UnsupportedWorkflowStateSchemaException(
            String workflowId,
            int actualVersion,
            int currentVersion) {
        super("Unsupported workflow state schemaVersion " + actualVersion
                + " for " + workflowId + ", current " + currentVersion);
    }
}
