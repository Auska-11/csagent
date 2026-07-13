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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Skill Package 与 Workflow 路由协调器。
 *
 * 【职责】
 * 1. 根据 Workflow ID 找到声明式 Agent Skill Package。
 * 2. 为 LLM Handler 解析 System Prompt 和按需 reference。
 * 3. 检查 manifest 中的 Capability allowlist。
 * 4. 把 Runtime 的路由请求委托给唯一 WorkflowRouter。
 *
 * <p>这里的 Skill 指 manifest + SKILL.md 组成的声明包，不再管理旧 Java Skill 实例。
 * 具体业务决策统一由 AgentWorkflow 实现。</p>
 */
@Component
public class SkillManager {

    private final SkillConfig skillConfig;
    private final AgentSkillPackageRegistry skillPackageRegistry;
    private final WorkflowRouter workflowRouter;

    /**
     * 不启动 Spring 的 Prompt/粗粒度路由单元测试可以只提供 SkillConfig。
     *
     * <p>registry 为 null 表示使用测试 System Prompt。生产环境由下面带 {@link Autowired}
     * 的构造函数注入真正的 AgentSkillPackageRegistry。</p>
     */
    public SkillManager(SkillConfig skillConfig) {
        this(skillConfig, null, null);
    }

    /**
     * 已接入 Skill Package、但只测试 Prompt/allowlist 的纯 Java 构造方式。
     */
    public SkillManager(SkillConfig skillConfig, AgentSkillPackageRegistry skillPackageRegistry) {
        this(skillConfig, skillPackageRegistry, null);
    }

    /**
     * Spring 生产构造函数：同时获得测试 Prompt fallback、Skill Package 注册表和正式 Router。
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
     * <p>生产路径会按需选择 reference；纯 Java Handler 测试没有 Registry 时，
     * 返回测试提供的 SkillConfig Prompt，避免测试启动完整 Spring 容器。</p>
     */
    public ResolvedSkillPrompt resolveSystemPrompt(AgentSkillPackage skillPackage,
                                                   String purpose,
                                                   String searchableText) {
        if (skillPackage == null) {
            return new ResolvedSkillPrompt("unbound-test", "test", skillConfig.getSystemPrompt(), List.of());
        }
        return skillPackage.resolvePrompt(purpose, searchableText);
    }

    /**
     * Skill Package 能力白名单检查。
     *
     * <p>null 只允许出现在不加载 manifest 的纯 Java 单元测试。当前生产 Workflow 均有 Package 绑定；
     * 成功选择 Package 后，未声明的调用一定被拒绝。</p>
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
     * Workflow 选择便捷入口。
     *
     * <p>所有运行时路由都必须委托正式 WorkflowRouter。Phase 9 删除了无 Router 时临时创建
     * Legacy Adapter 的分支，因此纯 Java 测试也必须显式装配真实 Router。</p>
     */
    public AgentWorkflow selectWorkflow(ChatRequest request, ConversationState state) {
        return routeWorkflow(request, state).getSelectedWorkflow();
    }

    /**
     * 返回包含候选分数和“首次选择/恢复”信息的完整 Workflow 路由结果。
     *
     * <p>不存在静默回退：如果调用方要执行 Runtime，就必须提供 Router。这样测试与生产使用同一条
     * Workflow 选择链，避免兼容分支长期只在测试里存活。</p>
     */
    public WorkflowRouteResult routeWorkflow(ChatRequest request, ConversationState state) {
        Objects.requireNonNull(request, "request cannot be null");
        Objects.requireNonNull(state, "state cannot be null");
        if (workflowRouter == null) {
            throw new IllegalStateException(
                    "WorkflowRouter is required for runtime routing; configure real AgentWorkflow instances"
            );
        }
        return workflowRouter.route(request, state);
    }

    /**
     * Multi-Agent 路由第一版。
     *
     * 当前真正的执行选择由 WorkflowRouter 完成；这里保留一个粗粒度“专家域”分类用于 Trace：
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
