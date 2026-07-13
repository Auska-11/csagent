package com.smartcs.smart_cs_agent.skill.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 从 manifest.yaml 解析得到的强类型 Skill 定义。
 *
 * <p><strong>它不执行任何业务逻辑。</strong>它只回答以下问题：</p>
 * <ul>
 *     <li>这个 Skill 是谁、版本是多少；</li>
 *     <li>它绑定哪个可执行 Workflow；</li>
 *     <li>它最多能调用哪些 Capability；</li>
 *     <li>主 Prompt 和参考资料在哪里；</li>
 *     <li>它属于什么风险级别。</li>
 * </ul>
 *
 * <p>真正决定“下一步做什么”的代码属于 AgentWorkflow。把两者拆开后，改话术和知识文件
 * 不需要改 Java 状态机，改状态机也不会让 Prompt 包承担执行职责。</p>
 */
public final class AgentSkillDefinition {

    private final String id;
    private final String version;
    private final String description;
    private final List<String> triggers;
    private final String workflowId;
    private final int priority;
    private final Set<String> allowedCapabilities;
    private final String promptPath;
    private final List<SkillReferenceDefinition> references;
    private final RiskLevel riskLevel;
    private final String confirmationPolicy;

    public AgentSkillDefinition(String id,
                                String version,
                                String description,
                                List<String> triggers,
                                String workflowId,
                                int priority,
                                Set<String> allowedCapabilities,
                                String promptPath,
                                List<SkillReferenceDefinition> references,
                                RiskLevel riskLevel,
                                String confirmationPolicy) {
        this.id = requireText(id, "skill id cannot be empty");
        this.version = requireText(version, "skill version cannot be empty");
        this.description = description == null ? "" : description.trim();
        this.triggers = immutableTextList(triggers);
        this.workflowId = requireText(workflowId, "workflow id cannot be empty");
        this.priority = priority;
        this.allowedCapabilities = immutableTextSet(allowedCapabilities);
        this.promptPath = requireText(promptPath, "prompt path cannot be empty");
        this.references = references == null
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(references));
        this.riskLevel = riskLevel == null ? RiskLevel.READ_ONLY : riskLevel;
        this.confirmationPolicy = confirmationPolicy == null ? "" : confirmationPolicy.trim();

        // 纯 Prompt Skill（例如闲聊和澄清兜底）不需要任何 Capability。
        // 空集合代表最小权限，而不是配置错误；真正要调用能力的 Workflow 仍会被 Handler allowlist 拒绝。
        if (this.riskLevel.mayCauseSideEffects() && this.confirmationPolicy.isBlank()) {
            throw new IllegalArgumentException(
                    "Side-effect skill must declare confirmation_policy: " + this.id
            );
        }
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

    public List<String> getTriggers() {
        return triggers;
    }

    public String getWorkflowId() {
        return workflowId;
    }

    public int getPriority() {
        return priority;
    }

    public Set<String> getAllowedCapabilities() {
        return allowedCapabilities;
    }

    public String getPromptPath() {
        return promptPath;
    }

    public List<SkillReferenceDefinition> getReferences() {
        return references;
    }

    public RiskLevel getRiskLevel() {
        return riskLevel;
    }

    public String getConfirmationPolicy() {
        return confirmationPolicy;
    }

    public boolean allowsCapability(String capabilityId) {
        return capabilityId != null && allowedCapabilities.contains(capabilityId);
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    private static List<String> immutableTextList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                result.add(value.trim());
            }
        }
        return Collections.unmodifiableList(result);
    }

    private static Set<String> immutableTextSet(Set<String> values) {
        if (values == null || values.isEmpty()) {
            return Set.of();
        }
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                result.add(value.trim());
            }
        }
        return Collections.unmodifiableSet(result);
    }
}
