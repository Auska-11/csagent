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

        assertEquals(4, packages.size());
        AgentSkillPackage skillPackage = packages.stream()
                .filter(candidate -> "customer-service".equals(candidate.getDefinition().getId()))
                .findFirst()
                .orElseThrow();
        assertEquals("customer-service", skillPackage.getDefinition().getId());
        assertEquals("1.0.0", skillPackage.getDefinition().getVersion());
        assertEquals("customer_service", skillPackage.getDefinition().getWorkflowId());
        assertTrue(skillPackage.getDefinition().allowsCapability("order.lookup"));
        assertTrue(skillPackage.getDefinition().allowsCapability("handoff.request"));
        assertEquals(4, skillPackage.getReferenceContents().size());
        assertTrue(skillPackage.getSystemPrompt().contains("客服 Skill 主策略"));
    }

    @Test
    void shouldLoadOnlyReferencesMatchingCurrentLlmPurposeOrText() {
        AgentSkillPackage skillPackage = loadCustomerServicePackage();

        // 意图识别只需要 intent.md，不应该把 persona、模板和政策全文全部注入。
        ResolvedSkillPrompt intentPrompt = skillPackage.resolvePrompt(
                "intent_recognition",
                "帮我识别这句话的意图：查一下订单 ORD-001"
        );
        assertEquals(List.of("references/intent.md"), intentPrompt.getLoadedReferences());
        assertTrue(intentPrompt.getContent().contains("意图分类定义"));
        assertFalse(intentPrompt.getContent().contains("角色与表达风格"));

        // 回复退货问题时，purpose 命中 persona/templates，文本关键词额外命中 policy。
        ResolvedSkillPrompt returnPrompt = skillPackage.resolvePrompt(
                "response_generation",
                "用户反馈质量问题并希望退货，请组织回复"
        );
        assertEquals(
                List.of(
                        "references/persona.md",
                        "references/policy.md",
                        "references/templates.md"
                ),
                returnPrompt.getLoadedReferences()
        );
        assertFalse(returnPrompt.getLoadedReferences().contains("references/intent.md"));
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
