package com.smartcs.smart_cs_agent.agent.handler.api;

import com.smartcs.smart_cs_agent.agent.context.TurnContext;
import com.smartcs.smart_cs_agent.skill.api.AgentSkillPackage;
import com.smartcs.smart_cs_agent.workflow.api.AgentWorkflow;

import java.util.Objects;

/**
 * Handler 执行某个 Action 时需要的只读框架上下文。
 *
 * <p>TurnContext 保存本轮状态和 Trace；Workflow 提供稳定 ID；Skill Package 提供 Prompt 和权限。
 * Handler 自己通过构造函数注入 ChatModel、Gateway 等执行依赖。</p>
 */
public final class ActionHandlingContext {

    private final TurnContext turnContext;
    private final AgentWorkflow workflow;
    private final AgentSkillPackage skillPackage;

    public ActionHandlingContext(TurnContext turnContext,
                                 AgentWorkflow workflow,
                                 AgentSkillPackage skillPackage) {
        this.turnContext = Objects.requireNonNull(turnContext, "turnContext cannot be null");
        this.workflow = Objects.requireNonNull(workflow, "workflow cannot be null");
        this.skillPackage = skillPackage;
    }

    public TurnContext getTurnContext() {
        return turnContext;
    }

    public AgentWorkflow getWorkflow() {
        return workflow;
    }

    public AgentSkillPackage getSkillPackage() {
        return skillPackage;
    }
}
