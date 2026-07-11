package com.smartcs.smart_cs_agent.workflow.api;

/**
 * Workflow 的稳定说明信息。
 *
 * <p>职责：告诉 Router 和调试界面“这是什么 Workflow、版本是多少、优先级多高”。
 * 它不保存某个用户的运行状态；用户状态属于后续的 WorkflowSession。</p>
 *
 * <p>为什么把描述信息单独建模：如果名称、版本和优先级散落在实现类的多个方法里，
 * Skill Loader、Router、Trace 和测试很容易各自维护一份并发生不一致。</p>
 */
public final class WorkflowDescriptor {

    private final String id;
    private final String version;
    private final String description;
    private final int priority;

    public WorkflowDescriptor(String id, String version, String description, int priority) {
        this.id = requireText(id, "Workflow id cannot be empty");
        this.version = requireText(version, "Workflow version cannot be empty");
        this.description = description == null ? "" : description;
        this.priority = priority;
    }

    public String getId() {
        return id;
    }

    public String getVersion() {
        return version;
    }

    public String getDescription() {
        return description;
    }

    public int getPriority() {
        return priority;
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }
}
