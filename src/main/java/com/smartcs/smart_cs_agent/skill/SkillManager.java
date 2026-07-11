package com.smartcs.smart_cs_agent.skill;

import com.smartcs.smart_cs_agent.config.SkillConfig;
import com.smartcs.smart_cs_agent.capability.api.CapabilityIds;
import com.smartcs.smart_cs_agent.agent.context.ChatRequest;
import com.smartcs.smart_cs_agent.agent.context.ConversationState;
import com.smartcs.smart_cs_agent.skill.api.AgentSkillPackage;
import com.smartcs.smart_cs_agent.skill.api.ResolvedSkillPrompt;
import com.smartcs.smart_cs_agent.skill.registry.AgentSkillPackageRegistry;
import com.smartcs.smart_cs_agent.workflow.api.AgentWorkflow;
import com.smartcs.smart_cs_agent.workflow.core.WorkflowRouteResult;
import com.smartcs.smart_cs_agent.workflow.core.WorkflowRouter;
import com.smartcs.smart_cs_agent.workflow.legacy.LegacySkillWorkflowAdapter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Skill 管理器
 *
 * 【职责】
 * 1. 管理所有注册的 Skill
 * 2. 获取当前活跃的 Skill
 * 3. 加载 System Prompt
 * 4. 后续支持多 Skill 路由
 *
 * 【当前设计】
 * 目前只有一个 CustomerServiceSkill，直接返回。
 * 后续做多 Agent 时，可以注册多个 Skill，根据消息选择。
 */
@Component
public class SkillManager {

    private final Map<String, Skill> skills = new HashMap<>();
    private final SkillConfig skillConfig;
    private final AgentSkillPackageRegistry skillPackageRegistry;
    private final WorkflowRouter workflowRouter;
    private String activeSkillName;

    /**
     * 兼容旧单元测试和不启动 Spring 的纯 Java 用法。
     *
     * <p>registry 为 null 表示使用旧的统一 System Prompt。生产环境由下面带 {@link Autowired}
     * 的构造函数注入真正的 AgentSkillPackageRegistry。</p>
     */
    public SkillManager(SkillConfig skillConfig) {
        this(skillConfig, null, null);
    }

    /**
     * 兼容已经接入 Skill Package、但没有启动完整 WorkflowRouter 的测试。
     */
    public SkillManager(SkillConfig skillConfig, AgentSkillPackageRegistry skillPackageRegistry) {
        this(skillConfig, skillPackageRegistry, null);
    }

    /**
     * Spring 生产构造函数：同时获得旧 Prompt 回滚入口、Skill Package 注册表和正式 Router。
     */
    @Autowired
    public SkillManager(SkillConfig skillConfig,
                        AgentSkillPackageRegistry skillPackageRegistry,
                        WorkflowRouter workflowRouter) {
        this.skillConfig = skillConfig;
        this.skillPackageRegistry = skillPackageRegistry;
        this.workflowRouter = workflowRouter;
    }

    /**
     * 注册 Skill
     *
     * @param skill Skill 实例
     */
    public void registerSkill(Skill skill) {
        skills.put(skill.getName(), skill);
        // 第一个注册的 Skill 默认为活跃 Skill
        if (activeSkillName == null) {
            activeSkillName = skill.getName();
        }
    }

    /**
     * 获取当前活跃的 Skill
     *
     * @return 当前 Skill
     */
    public Skill getActiveSkill() {
        if (activeSkillName == null || !skills.containsKey(activeSkillName)) {
            throw new IllegalStateException("No active skill registered");
        }
        return skills.get(activeSkillName);
    }

    /**
     * 设置活跃的 Skill
     *
     * @param skillName Skill 名称
     */
    public void setActiveSkill(String skillName) {
        if (!skills.containsKey(skillName)) {
            throw new IllegalArgumentException("Skill not found: " + skillName);
        }
        this.activeSkillName = skillName;
    }

    /**
     * 获取 System Prompt
     *
     * 从 SkillConfig 加载，内容来自 prompts/system-prompt.md 文件
     *
     * @return System Prompt 内容
     */
    public String getSystemPrompt() {
        return skillConfig.getSystemPrompt();
    }

    /**
     * 根据已选 Workflow 找到它绑定的 Agent Skill Package。
     *
     * <p>Package 和 Workflow 使用 manifest.workflow 绑定，而不是依赖 Java 类名。
     * 以后增加新业务时，Router 选出 Workflow 后即可自动找到对应 Prompt 与能力白名单。</p>
     */
    public Optional<AgentSkillPackage> findPackageForWorkflow(String workflowId) {
        if (skillPackageRegistry == null) {
            return Optional.empty();
        }
        return skillPackageRegistry.findByWorkflowId(workflowId);
    }

    /**
     * 组装某次 LLM 调用实际使用的 System Prompt。
     *
     * <p>新版路径会按需选择 reference；如果配置为 legacy 或测试没有 Registry，
     * 则返回原来的 prompts/system-prompt.md，保证回滚和已有单元测试不受影响。</p>
     */
    public ResolvedSkillPrompt resolveSystemPrompt(AgentSkillPackage skillPackage,
                                                   String purpose,
                                                   String searchableText) {
        if (skillPackage == null) {
            return new ResolvedSkillPrompt("legacy", "legacy", skillConfig.getSystemPrompt(), List.of());
        }
        return skillPackage.resolvePrompt(purpose, searchableText);
    }

    /**
     * Skill Package 能力白名单检查。
     *
     * <p>null 代表旧模式，为兼容旧链路暂时放行。只要成功选择了新版 Package，
     * 未在 manifest.allowed_capabilities 中声明的调用就会被拒绝。</p>
     */
    public boolean isCapabilityAllowed(AgentSkillPackage skillPackage, String capabilityId) {
        return skillPackage == null
                || skillPackage.getDefinition().allowsCapability(CapabilityIds.canonicalize(capabilityId));
    }

    /** 返回启动时加载的不可变 Package 列表，主要供健康检查和调试界面使用。 */
    public List<AgentSkillPackage> getLoadedPackages() {
        return skillPackageRegistry == null ? List.of() : skillPackageRegistry.list();
    }

    /**
     * 获取所有注册的 Skill 名称
     *
     * @return Skill 名称列表
     */
    public List<String> getSkillNames() {
        return new ArrayList<>(skills.keySet());
    }

    /**
     * 根据消息选择最合适的 Skill（后续多 Skill 路由用）
     *
     * @param message 用户消息
     * @return 最合适的 Skill
     */
    public Skill selectSkill(String message) {
        Skill bestSkill = null;
        float bestScore = 0.0f;

        for (Skill skill : skills.values()) {
            float score = skill.canHandle(message);
            if (score > bestScore) {
                bestScore = score;
                bestSkill = skill;
            }
        }

        return bestSkill != null ? bestSkill : getActiveSkill();
    }

    /**
     * 过渡期的 Workflow 选择入口。
     *
     * <p>这是保留给旧调用方的便捷方法。生产环境内部已经委托正式 WorkflowRouter，
     * Router 会读取 ConversationState 恢复未完成任务；没有 Spring Router 的纯 Java 旧测试
     * 才会回退到 LegacySkillWorkflowAdapter。</p>
     */
    public AgentWorkflow selectWorkflow(ChatRequest request, ConversationState state) {
        return routeWorkflow(request, state).getSelectedWorkflow();
    }

    /**
     * 返回包含候选分数和“首次选择/恢复”信息的完整 Workflow 路由结果。
     *
     * <p>生产环境委托 WorkflowRouter；旧单元测试没有 Router 时，仍用 Legacy Adapter，
     * 但也会写入 activeWorkflow 状态，从而让 Runtime 的生命周期代码保持一致。</p>
     */
    public WorkflowRouteResult routeWorkflow(ChatRequest request, ConversationState state) {
        Objects.requireNonNull(request, "request cannot be null");
        Objects.requireNonNull(state, "state cannot be null");
        if (workflowRouter != null) {
            return workflowRouter.route(request, state);
        }

        AgentWorkflow legacyWorkflow = new LegacySkillWorkflowAdapter(selectSkill(request.getMessage()));
        boolean resumed = state.hasActiveNonTerminalWorkflow()
                && legacyWorkflow.descriptor().getId().equals(state.getActiveWorkflowId());
        state.startOrResumeWorkflow(
                legacyWorkflow.descriptor().getId(),
                legacyWorkflow.descriptor().getVersion()
        );
        return new WorkflowRouteResult(
                legacyWorkflow,
                resumed,
                1.0,
                resumed ? "测试/回滚模式恢复 Legacy Workflow" : "测试/回滚模式选择 Legacy Workflow",
                List.of()
        );
    }

    /**
     * Multi-Agent 路由第一版。
     *
     * 当前系统仍然复用 CustomerServiceSkill 执行具体动作，
     * 但会先把用户消息路由到一个“专家域”：
     * - pre_sales_agent：售前咨询，商品、价格、库存、推荐
     * - after_sales_agent：售后处理，订单、物流、退换货、政策
     * - complaint_agent：投诉/人工/赔偿/维权
     * - general_agent：通用客服兜底
     *
     * 这样做的价值是先把路由决策做成可观察 Trace，
     * 后续要拆真正独立 Agent 时，边界已经清楚。
     */
    public AgentRouteResult routeAgent(String message) {
        String text = message == null ? "" : message.toLowerCase(Locale.ROOT);

        if (containsAny(text, "投诉", "赔偿", "维权", "人工", "真人", "差评", "客服经理")) {
            return new AgentRouteResult(
                    "complaint",
                    "complaint_agent",
                    "命中投诉、人工或赔偿类表达，优先交给投诉/人工路由",
                    0.95
            );
        }

        if (containsAny(text, "退货", "退款", "换货", "售后", "订单", "物流", "快递", "发货", "签收", "保修", "发票")) {
            return new AgentRouteResult(
                    "after_sales",
                    "after_sales_agent",
                    "命中订单、物流、退换货或政策类表达，交给售后路由",
                    0.88
            );
        }

        if (containsAny(text, "商品", "产品", "价格", "多少钱", "库存", "有货", "推荐", "耳机", "手表", "充电器", "数据线", "蓝牙")) {
            return new AgentRouteResult(
                    "pre_sales",
                    "pre_sales_agent",
                    "命中商品咨询、价格、库存或推荐类表达，交给售前路由",
                    0.86
            );
        }

        return new AgentRouteResult(
                "general",
                "general_agent",
                "未命中明确专家域，使用通用客服路由兜底",
                0.55
        );
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
