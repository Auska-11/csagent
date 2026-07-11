package com.smartcs.smart_cs_agent.workflow.core;

import com.smartcs.smart_cs_agent.skill.api.AgentSkillPackage;
import com.smartcs.smart_cs_agent.skill.registry.AgentSkillPackageRegistry;
import com.smartcs.smart_cs_agent.workflow.api.AgentWorkflow;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 系统内所有可执行 AgentWorkflow 的不可变注册表。
 *
 * <p>Spring 会把每个 {@code @Component AgentWorkflow} 自动组成 List 注入进来。
 * 新增 Workflow 只需增加实现类，无需在 Router 中手写 switch 或 if。</p>
 */
@Component
public class WorkflowRegistry {

    private final Map<String, AgentWorkflow> byId;
    private final List<AgentWorkflow> orderedWorkflows;

    @Autowired
    public WorkflowRegistry(List<AgentWorkflow> workflows,
                            AgentSkillPackageRegistry skillPackageRegistry) {
        this(workflows, skillPackageRegistry.list());
    }

    /** 测试构造函数：直接传 Workflow 和 Skill Package。 */
    public WorkflowRegistry(Collection<AgentWorkflow> workflows,
                            Collection<AgentSkillPackage> skillPackages) {
        Map<String, AgentWorkflow> index = new LinkedHashMap<>();
        if (workflows != null) {
            for (AgentWorkflow workflow : workflows) {
                String id = workflow.descriptor().getId();
                AgentWorkflow duplicate = index.putIfAbsent(id, workflow);
                if (duplicate != null) {
                    throw new IllegalArgumentException("Duplicate Workflow id: " + id);
                }
            }
        }

        // 按 priority 降序、ID 升序生成稳定快照。Router 评分相同时会沿用这个确定顺序。
        List<AgentWorkflow> ordered = new ArrayList<>(index.values());
        ordered.sort(
                Comparator.comparingInt((AgentWorkflow workflow) -> workflow.descriptor().getPriority())
                        .reversed()
                        .thenComparing(workflow -> workflow.descriptor().getId())
        );

        this.byId = Collections.unmodifiableMap(index);
        this.orderedWorkflows = List.copyOf(ordered);
        validateSkillBindings(skillPackages);
    }

    public AgentWorkflow get(String workflowId) {
        return byId.get(workflowId);
    }

    public AgentWorkflow require(String workflowId) {
        AgentWorkflow workflow = get(workflowId);
        if (workflow == null) {
            throw new IllegalStateException("Workflow not registered: " + workflowId);
        }
        return workflow;
    }

    public List<AgentWorkflow> list() {
        return orderedWorkflows;
    }

    private void validateSkillBindings(Collection<AgentSkillPackage> skillPackages) {
        if (skillPackages == null) {
            return;
        }
        for (AgentSkillPackage skillPackage : skillPackages) {
            String workflowId = skillPackage.getDefinition().getWorkflowId();
            if (!byId.containsKey(workflowId)) {
                throw new IllegalStateException(
                        "Skill '" + skillPackage.getDefinition().getId()
                                + "' references unregistered Workflow: " + workflowId
                );
            }
        }
    }
}
