package com.smartcs.smart_cs_agent.testing;

import com.smartcs.smart_cs_agent.config.SkillConfig;
import com.smartcs.smart_cs_agent.agent.handler.core.ActionHandlerRegistry;
import com.smartcs.smart_cs_agent.agent.handler.defaults.AskUserActionHandler;
import com.smartcs.smart_cs_agent.agent.handler.defaults.CapabilityActionHandler;
import com.smartcs.smart_cs_agent.agent.handler.defaults.ConfirmActionHandler;
import com.smartcs.smart_cs_agent.agent.handler.defaults.FinalAnswerActionHandler;
import com.smartcs.smart_cs_agent.agent.handler.defaults.HandoffActionHandler;
import com.smartcs.smart_cs_agent.agent.handler.defaults.LlmActionHandler;
import com.smartcs.smart_cs_agent.capability.api.CapabilityGateway;
import com.smartcs.smart_cs_agent.capability.core.DefaultCapabilityGateway;
import com.smartcs.smart_cs_agent.capability.local.LocalHandoffCapabilityProvider;
import com.smartcs.smart_cs_agent.capability.local.LocalKnowledgeSearchCapabilityProvider;
import com.smartcs.smart_cs_agent.capability.local.LocalOrderCapabilityProvider;
import com.smartcs.smart_cs_agent.capability.local.LocalProductCatalogCapabilityProvider;
import com.smartcs.smart_cs_agent.capability.local.LocalReturnApplyCapabilityProvider;
import com.smartcs.smart_cs_agent.capability.local.LocalReturnLookupCapabilityProvider;
import com.smartcs.smart_cs_agent.capability.local.LocalUserProfileCapabilityProvider;
import com.smartcs.smart_cs_agent.domain.returnservice.ReturnApplicationService;
import com.smartcs.smart_cs_agent.repository.OrderRepository;
import com.smartcs.smart_cs_agent.repository.ProductRepository;
import com.smartcs.smart_cs_agent.repository.ReturnRepository;
import com.smartcs.smart_cs_agent.repository.UserRepository;
import com.smartcs.smart_cs_agent.skill.SkillManager;
import com.smartcs.smart_cs_agent.skill.registry.AgentSkillPackageRegistry;
import com.smartcs.smart_cs_agent.workflow.api.AgentWorkflow;
import com.smartcs.smart_cs_agent.workflow.core.GlobalInterruptPolicy;
import com.smartcs.smart_cs_agent.workflow.core.WorkflowRegistry;
import com.smartcs.smart_cs_agent.workflow.core.WorkflowRouter;
import com.smartcs.smart_cs_agent.workflow.core.WorkflowRouterProperties;
import com.smartcs.smart_cs_agent.tools.HandoffTools;
import com.smartcs.smart_cs_agent.tools.KnowledgeTools;
import dev.langchain4j.model.chat.ChatLanguageModel;

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

    /**
     * 为纯 Java Runtime 测试装配与 Spring 相同的一组默认 Handler。
     * 该工厂只位于测试源码，生产 Registry 不再保留手动兼容入口。
     */
    public static ActionHandlerRegistry actionHandlerRegistry(SkillManager skillManager,
                                                              ChatLanguageModel chatModel,
                                                              CapabilityGateway capabilityGateway) {
        return new ActionHandlerRegistry(List.of(
                new FinalAnswerActionHandler(),
                new AskUserActionHandler(),
                new ConfirmActionHandler(),
                new LlmActionHandler(skillManager, chatModel),
                new CapabilityActionHandler(capabilityGateway, skillManager),
                new HandoffActionHandler(capabilityGateway, skillManager)
        ));
    }

    /** 使用全新的内存 Repository 装配一套完整本地 Capability，适合只验证协议和目录的测试。 */
    public static CapabilityGateway localCapabilityGateway() {
        return localCapabilityGateway(
                new OrderRepository(),
                new ProductRepository(),
                new ReturnRepository(),
                new UserRepository(),
                new KnowledgeTools(),
                new HandoffTools()
        );
    }

    /**
     * 为需要检查 Repository 副作用或自定义知识文档的测试装配真实本地 Provider。
     *
     * <p>所有 Provider 与生产 Spring 装配相同，只省略了旧 ToolRegistry Adapter。参数保持显式，
     * 是为了让测试读者能看出哪些组件共享同一份内存状态。</p>
     */
    public static CapabilityGateway localCapabilityGateway(OrderRepository orderRepository,
                                                            ProductRepository productRepository,
                                                            ReturnRepository returnRepository,
                                                            UserRepository userRepository,
                                                            KnowledgeTools knowledgeTools,
                                                            HandoffTools handoffTools) {
        ReturnApplicationService returnApplicationService = new ReturnApplicationService(
                returnRepository,
                orderRepository,
                productRepository
        );
        return new DefaultCapabilityGateway(List.of(
                new LocalOrderCapabilityProvider(orderRepository, productRepository),
                new LocalProductCatalogCapabilityProvider(productRepository),
                new LocalReturnApplyCapabilityProvider(returnApplicationService),
                new LocalReturnLookupCapabilityProvider(returnRepository, orderRepository, productRepository),
                new LocalUserProfileCapabilityProvider(userRepository),
                new LocalKnowledgeSearchCapabilityProvider(knowledgeTools),
                new LocalHandoffCapabilityProvider(handoffTools)
        ));
    }
}
