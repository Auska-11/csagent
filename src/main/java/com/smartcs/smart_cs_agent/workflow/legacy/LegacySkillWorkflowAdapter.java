package com.smartcs.smart_cs_agent.workflow.legacy;

import com.smartcs.smart_cs_agent.agent.context.TurnContext;
import com.smartcs.smart_cs_agent.skill.Skill;
import com.smartcs.smart_cs_agent.workflow.api.AgentWorkflow;
import com.smartcs.smart_cs_agent.workflow.api.RoutingContext;
import com.smartcs.smart_cs_agent.workflow.api.WorkflowDecision;
import com.smartcs.smart_cs_agent.workflow.api.WorkflowDescriptor;
import com.smartcs.smart_cs_agent.workflow.api.WorkflowMatchResult;

import java.util.Objects;

/**
 * 把旧版 {@link Skill} 暂时适配成新版 {@link AgentWorkflow}。
 *
 * <p>为什么需要 Adapter：如果一次性重写 CustomerServiceSkill、Runtime 和所有测试，出现回归时
 * 很难判断问题来自哪一层。这个类让 Runtime 可以先依赖 Workflow 协议，同时继续执行已经稳定的
 *旧 Skill 逻辑。待独立 Workflow 全部迁移完成后，本类会在清理阶段删除。</p>
 *
 * <p>它是迁移桥梁，不是最终业务实现；新功能不得继续添加到这个 Adapter 中。</p>
 */
public final class LegacySkillWorkflowAdapter implements AgentWorkflow {

    private static final String LEGACY_VERSION = "legacy-v1";

    private final Skill legacySkill;
    private final WorkflowDescriptor descriptor;

    public LegacySkillWorkflowAdapter(Skill legacySkill) {
        this.legacySkill = Objects.requireNonNull(legacySkill, "legacySkill cannot be null");
        this.descriptor = new WorkflowDescriptor(
                legacySkill.getName(),
                LEGACY_VERSION,
                "由旧 Skill 适配的过渡 Workflow：" + legacySkill.getClass().getSimpleName(),
                0
        );
    }

    @Override
    public WorkflowDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public WorkflowMatchResult match(RoutingContext context) {
        float legacyScore = legacySkill.canHandle(context.getMessage());

        // 旧接口没有强制限制分数范围。Adapter 在边界处做归一化，避免异常实现破坏新 Router。
        double normalizedScore = Math.max(0.0, Math.min(1.0, legacyScore));
        return new WorkflowMatchResult(
                normalizedScore,
                "旧 Skill.canHandle 匹配结果"
        );
    }

    @Override
    public WorkflowDecision nextAction(TurnContext context) {
        return WorkflowDecision.next(
                legacySkill.nextAction(context),
                "由旧 Skill.nextAction 生成，等待迁移为独立 Workflow"
        );
    }
}
