package com.smartcs.smart_cs_agent.testing;

import com.smartcs.smart_cs_agent.config.SkillConfig;
import com.smartcs.smart_cs_agent.skill.SkillManager;
import com.smartcs.smart_cs_agent.skill.registry.AgentSkillPackageRegistry;
import com.smartcs.smart_cs_agent.workflow.api.AgentWorkflow;
import com.smartcs.smart_cs_agent.workflow.core.GlobalInterruptPolicy;
import com.smartcs.smart_cs_agent.workflow.core.WorkflowRegistry;
import com.smartcs.smart_cs_agent.workflow.core.WorkflowRouter;
import com.smartcs.smart_cs_agent.workflow.core.WorkflowRouterProperties;

import java.util.Arrays;
import java.util.List;

/**
 * 纯 Java 测试的 Workflow 装配器。
 *
 * <p>Phase 9 删除了 Runtime 的 Legacy Skill 自动回退。测试如果要运行 Runtime，也应该像生产一样显式
 * 创建 WorkflowRegistry 和 WorkflowRouter。本类只减少重复样板，不提供另一套路由规则。</p>
 */
public final class WorkflowTestSupport {

    private WorkflowTestSupport() {
    }

    /**
     * 使用传入列表的第一个 Workflow 作为低分兜底，其余评分和粘性规则完全复用生产 Router。
     */
    public static SkillManager skillManager(SkillConfig skillConfig, AgentWorkflow... workflows) {
        return skillManager(skillConfig, null, workflows);
    }

    /** 与上面相同，但允许测试验证真实 Skill Package 绑定和能力白名单。 */
    public static SkillManager skillManager(SkillConfig skillConfig,
                                            AgentSkillPackageRegistry skillPackageRegistry,
                                            AgentWorkflow... workflows) {
        if (workflows == null || workflows.length == 0) {
            throw new IllegalArgumentException("At least one test Workflow is required");
        }

        List<AgentWorkflow> workflowList = Arrays.asList(workflows);
        WorkflowRegistry registry = new WorkflowRegistry(workflowList, List.of());
        WorkflowRouterProperties properties = new WorkflowRouterProperties();
        properties.setFallbackWorkflowId(workflowList.get(0).descriptor().getId());
        WorkflowRouter router = new WorkflowRouter(registry, properties, new GlobalInterruptPolicy());
        return new SkillManager(skillConfig, skillPackageRegistry, router);
    }
}
