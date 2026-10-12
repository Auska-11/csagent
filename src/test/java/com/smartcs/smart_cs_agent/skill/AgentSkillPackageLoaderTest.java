package com.smartcs.smart_cs_agent.skill;

import com.smartcs.smart_cs_agent.skill.api.AgentSkillPackage;
import com.smartcs.smart_cs_agent.skill.api.ResolvedSkillPrompt;
import com.smartcs.smart_cs_agent.skill.loader.AgentSkillLoadException;
import com.smartcs.smart_cs_agent.skill.loader.AgentSkillManifestParser;
import com.smartcs.smart_cs_agent.skill.loader.AgentSkillProperties;
import com.smartcs.smart_cs_agent.skill.loader.YamlAgentSkillLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Agent Skill Package 加载器的行为测试。
 *
 * <p>这组测试直接读取项目真实的 skills/customer-service，确保它不是一份“写了但程序不用”的文档。
 * 另外使用临时目录验证部署时挂载外部 Skill，以及引用文件缺失时能否尽早报错。</p>
 */
class AgentSkillPackageLoaderTest {

    @TempDir
    Path tempDirectory;

    @Test
    void shouldLoadRealCustomerServicePackageFromClasspath() {
        AgentSkillProperties properties = new AgentSkillProperties();
        properties.setSource("classpath");

        List<AgentSkillPackage> packages = new YamlAgentSkillLoader(
                properties,
                new AgentSkillManifestParser()
        ).load();

        assertEquals(11, packages.size());
        AgentSkillPackage skillPackage = packages.stream()
                .filter(candidate -> "customer-service".equals(candidate.getDefinition().getId()))
                .findFirst()
                .orElseThrow();
        assertEquals("customer-service", skillPackage.getDefinition().getId());
        assertEquals("1.0.0", skillPackage.getDefinition().getVersion());
        assertEquals("customer_service", skillPackage.getDefinition().getWorkflowId());
        assertTrue(skillPackage.getDefinition().getAllowedCapabilities().isEmpty());
        assertEquals(2, skillPackage.getReferenceContents().size());
        assertTrue(skillPackage.getSystemPrompt().contains("通用客服兜底策略"));

        AgentSkillPackage escalation = packages.stream()
                .filter(candidate -> "escalation".equals(candidate.getDefinition().getId()))
                .findFirst()
                .orElseThrow();
        assertEquals("escalation", escalation.getDefinition().getWorkflowId());
        assertEquals(Set.of("handoff.request"), escalation.getDefinition().getAllowedCapabilities());
        AgentSkillPackage trade = packages.stream()
                .filter(candidate -> "xianyu-trade".equals(candidate.getDefinition().getId()))
                .findFirst()
                .orElseThrow();
        assertEquals("xianyu_trade", trade.getDefinition().getWorkflowId());
        assertEquals(Set.of("handoff.request"), trade.getDefinition().getAllowedCapabilities());
    }

    @Test
    void shouldLoadOnlyReferencesMatchingCurrentLlmPurposeOrText() {
        AgentSkillPackage skillPackage = loadCustomerServicePackage();

        // 通用回复只加载角色与澄清模板，不再把业务意图表或政策资料注入兜底 LLM。
        ResolvedSkillPrompt generalPrompt = skillPackage.resolvePrompt(
                "response_generation",
                "用户只说我有个问题，请生成一个澄清问题"
        );
        assertEquals(
                List.of(
                        "references/persona.md",
                        "references/templates.md"
                ),
                generalPrompt.getLoadedReferences()
        );
        assertTrue(generalPrompt.getContent().contains("角色与表达风格"));
    }

    @Test
    void shouldSupportExternalDirectoryWithoutChangingRuntimeCode() throws IOException {
        Path packageDirectory = tempDirectory.resolve("faq-skill");
        Files.createDirectories(packageDirectory.resolve("references"));
        Files.writeString(packageDirectory.resolve("SKILL.md"), "# FAQ 主提示词");
        Files.writeString(packageDirectory.resolve("references/faq.md"), "# FAQ 资料");
        Files.writeString(packageDirectory.resolve("manifest.yaml"), minimalManifest("references/faq.md"));

        AgentSkillProperties properties = new AgentSkillProperties();
        properties.setSource("external-directory");
        properties.setExternalDirectory(tempDirectory.toString());

        List<AgentSkillPackage> packages = new YamlAgentSkillLoader(
                properties,
                new AgentSkillManifestParser()
        ).load();

        assertEquals(1, packages.size());
        assertEquals("faq-skill", packages.get(0).getDefinition().getId());
        assertTrue(packages.get(0).getSourceDescription().contains("manifest.yaml"));
    }

    @Test
    void shouldFailFastWhenManifestReferencesMissingFile() throws IOException {
        Path packageDirectory = tempDirectory.resolve("broken-skill");
        Files.createDirectories(packageDirectory);
        Files.writeString(packageDirectory.resolve("SKILL.md"), "# 主提示词");
        Files.writeString(packageDirectory.resolve("manifest.yaml"), minimalManifest("references/missing.md"));

        AgentSkillProperties properties = new AgentSkillProperties();
        properties.setSource("external-directory");
        properties.setExternalDirectory(tempDirectory.toString());

        AgentSkillLoadException error = assertThrows(
                AgentSkillLoadException.class,
                () -> new YamlAgentSkillLoader(properties, new AgentSkillManifestParser()).load()
        );
        assertTrue(error.getMessage().contains("references/missing.md"));
    }

    @Test
    void shouldKeepLegacyRollbackModeAvailable() {
        AgentSkillProperties properties = new AgentSkillProperties();
        properties.setSource("legacy");

        List<AgentSkillPackage> packages = new YamlAgentSkillLoader(
                properties,
                new AgentSkillManifestParser()
        ).load();

        assertTrue(packages.isEmpty());
    }

    private AgentSkillPackage loadCustomerServicePackage() {
        AgentSkillProperties properties = new AgentSkillProperties();
        return new YamlAgentSkillLoader(properties, new AgentSkillManifestParser()).load().stream()
                .filter(candidate -> "customer-service".equals(candidate.getDefinition().getId()))
                .findFirst()
                .orElseThrow();
    }

    private String minimalManifest(String referencePath) {
        return """
                id: faq-skill
                version: 1.0.0
                description: 测试外部 Skill
                workflow: faq_workflow
                priority: 10
                triggers:
                  - 常见问题
                allowed_capabilities:
                  - knowledge_search
                risk_level: read_only
                prompt: SKILL.md
                references:
                  - path: %s
                    always: true
                """.formatted(referencePath);
    }
}
