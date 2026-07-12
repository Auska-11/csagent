package com.smartcs.smart_cs_agent.architecture;

import com.smartcs.smart_cs_agent.agent.CustomerServiceAgent;
import com.smartcs.smart_cs_agent.agent.context.AuthenticatedPrincipal;
import com.smartcs.smart_cs_agent.agent.context.ChatRequest;
import com.smartcs.smart_cs_agent.agent.context.ChatResponse;
import com.smartcs.smart_cs_agent.agent.context.ConversationKey;
import com.smartcs.smart_cs_agent.agent.context.ConversationStore;
import com.smartcs.smart_cs_agent.skill.registry.AgentSkillPackageRegistry;
import com.smartcs.smart_cs_agent.workflow.core.WorkflowRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 最终扩展性验收：新增保修业务后，Package、Workflow 和能力调用都由现有 SPI 自动接入。
 *
 * <p>这不是只检查一个类是否能 new 出来，而是从真实 Agent 入口走完：</p>
 * <pre>
 * Skill Package Loader -> WorkflowRegistry -> Router -> Runtime
 *   -> Capability Handler -> Gateway -> Local Provider -> 知识库
 * </pre>
 *
 * <p>测试同时扫描核心源码，防止有人为了让用例通过，偷偷在 Runtime 或 Router 中加入
 * {@code if (warranty...)} 这样的业务特判。</p>
 */
@SpringBootTest
class WarrantySkillExtensionProofTest {

    @Autowired
    private AgentSkillPackageRegistry skillPackages;

    @Autowired
    private WorkflowRegistry workflows;

    @Autowired
    private CustomerServiceAgent agent;

    @Autowired
    private ConversationStore conversationStore;

    @Test
    void shouldAutoDiscoverPackageAndWorkflowWithoutManualCoreRegistration() {
        var skillPackage = skillPackages.findById("warranty-query").orElseThrow();

        assertEquals("warranty_query", skillPackage.getDefinition().getWorkflowId());
        assertEquals(Set.of("knowledge.search"), skillPackage.getDefinition().getAllowedCapabilities());
        assertNotNull(workflows.get("warranty_query"));
    }

    @Test
    void shouldCompleteWarrantyQuestionThroughRealAgentAndCapabilityChain() {
        String conversationId = "extension-proof-warranty";
        conversationStore.clear(ConversationKey.defaultTenant("USER-001", conversationId));

        ChatResponse response = agent.chatWithTrace(new ChatRequest(
                conversationId,
                "USER-001",
                "蓝牙耳机保修多久？",
                AuthenticatedPrincipal.authenticated("USER-001", Set.of("CUSTOMER"))
        ));

        assertTrue(response.getReply().contains("1 年"), response::getReply);
        assertTrue(hasTraceValue(response, "workflow_routed", "selectedWorkflow", "warranty_query"));
        assertTrue(hasTraceValue(response, "skill_package_selected", "skillId", "warranty-query"));
        assertTrue(hasTraceValue(response, "tool_observed", "capability", "knowledge.search"));
    }

    @Test
    void coreKernelFilesShouldContainNoWarrantySpecificRegistrationOrBranch() throws IOException {
        List<Path> coreFiles = List.of(
                Path.of("src/main/java/com/smartcs/smart_cs_agent/agent/AgentRuntime.java"),
                Path.of("src/main/java/com/smartcs/smart_cs_agent/workflow/core/WorkflowRouter.java"),
                Path.of("src/main/java/com/smartcs/smart_cs_agent/workflow/core/WorkflowRegistry.java"),
                Path.of("src/main/java/com/smartcs/smart_cs_agent/agent/handler/core/ActionHandlerRegistry.java"),
                Path.of("src/main/java/com/smartcs/smart_cs_agent/capability/core/DefaultCapabilityGateway.java")
        );

        for (Path coreFile : coreFiles) {
            String source = Files.readString(coreFile).toLowerCase();
            assertFalse(source.contains("warranty"), () -> coreFile + " contains warranty-specific code");
            assertFalse(source.contains("保修"), () -> coreFile + " contains 保修-specific code");
            assertFalse(
                    source.contains("workflow.warranty"),
                    () -> coreFile + " directly imports the warranty Workflow"
            );
        }
    }

    private boolean hasTraceValue(ChatResponse response,
                                  String eventType,
                                  String field,
                                  String expectedValue) {
        return response.getTrace().stream().anyMatch(event ->
                eventType.equals(event.getEventType())
                        && expectedValue.equals(event.getData().get(field))
        );
    }
}
