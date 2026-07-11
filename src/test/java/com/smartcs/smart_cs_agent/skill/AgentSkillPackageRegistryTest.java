package com.smartcs.smart_cs_agent.skill;

import com.smartcs.smart_cs_agent.capability.api.CapabilityCatalog;
import com.smartcs.smart_cs_agent.capability.api.CapabilityDefinition;
import com.smartcs.smart_cs_agent.skill.api.AgentSkillDefinition;
import com.smartcs.smart_cs_agent.skill.api.AgentSkillPackage;
import com.smartcs.smart_cs_agent.skill.api.RiskLevel;
import com.smartcs.smart_cs_agent.skill.loader.AgentSkillLoadException;
import com.smartcs.smart_cs_agent.skill.registry.AgentSkillPackageRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Registry 测试关注“启动时拒绝错误配置”。
 * 这些错误如果拖到用户请求期间才暴露，可能表现成随机失败或越权调用，所以必须 fail fast。
 */
class AgentSkillPackageRegistryTest {

    private final CapabilityCatalog catalog = new CapabilityCatalog(List.of(
            new CapabilityDefinition("order_lookup", "查订单", Map.of(), Map.of()),
            new CapabilityDefinition("knowledge_search", "查知识", Map.of(), Map.of())
    ));

    @Test
    void shouldBuildImmutableIndexesBySkillAndWorkflow() {
        AgentSkillPackage skillPackage = packageOf("order-skill", "order_workflow", "order_lookup");

        AgentSkillPackageRegistry registry = new AgentSkillPackageRegistry(List.of(skillPackage), catalog);

        assertEquals(skillPackage, registry.findById("order-skill").orElseThrow());
        assertEquals(skillPackage, registry.findByWorkflowId("order_workflow").orElseThrow());
        assertEquals(1, registry.list().size());
        assertThrows(UnsupportedOperationException.class, () -> registry.list().add(skillPackage));
    }

    @Test
    void shouldRejectUnknownCapabilityDuringStartup() {
        AgentSkillPackage skillPackage = packageOf("danger-skill", "danger_workflow", "admin_delete_all");

        AgentSkillLoadException error = assertThrows(
                AgentSkillLoadException.class,
                () -> new AgentSkillPackageRegistry(List.of(skillPackage), catalog)
        );

        assertTrue(error.getMessage().contains("admin_delete_all"));
    }

    @Test
    void shouldRejectDuplicateSkillId() {
        AgentSkillPackage first = packageOf("order-skill", "first_workflow", "order_lookup");
        AgentSkillPackage second = packageOf("order-skill", "second_workflow", "knowledge_search");

        AgentSkillLoadException error = assertThrows(
                AgentSkillLoadException.class,
                () -> new AgentSkillPackageRegistry(List.of(first, second), catalog)
        );

        assertTrue(error.getMessage().contains("Duplicate Skill id"));
    }

    @Test
    void shouldRejectAmbiguousWorkflowBinding() {
        AgentSkillPackage first = packageOf("order-skill", "shared_workflow", "order_lookup");
        AgentSkillPackage second = packageOf("faq-skill", "shared_workflow", "knowledge_search");

        AgentSkillLoadException error = assertThrows(
                AgentSkillLoadException.class,
                () -> new AgentSkillPackageRegistry(List.of(first, second), catalog)
        );

        assertTrue(error.getMessage().contains("multiple Skill packages"));
    }

    private AgentSkillPackage packageOf(String id, String workflowId, String allowedCapability) {
        AgentSkillDefinition definition = new AgentSkillDefinition(
                id,
                "1.0.0",
                "测试 Skill",
                List.of("测试"),
                workflowId,
                10,
                Set.of(allowedCapability),
                "SKILL.md",
                List.of(),
                RiskLevel.READ_ONLY,
                ""
        );
        return new AgentSkillPackage(definition, "# 测试提示词", Map.of(), "unit-test");
    }
}
