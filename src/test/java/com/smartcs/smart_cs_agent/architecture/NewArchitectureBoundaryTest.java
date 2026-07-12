package com.smartcs.smart_cs_agent.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 新架构包的轻量依赖边界测试。
 *
 * <p>项目当前没有引入 ArchUnit。为了让 Phase 1 立即拥有自动门禁，本测试直接检查 Java import。
 * 它先保护最关键的新 SPI：Workflow API、Capability API 和 Skill Package API 不能反向依赖
 * Repository、Controller、旧 Tools 或具体 Adapter。边界稳定后可以换成 ArchUnit 的字节码级检查。</p>
 */
class NewArchitectureBoundaryTest {

    private static final Path MAIN_JAVA = Path.of("src/main/java");

    @Test
    void workflowApiShouldNotDependOnLegacyBusinessOrInfrastructurePackages() throws IOException {
        assertNoForbiddenImports(
                Path.of("com/smartcs/smart_cs_agent/workflow/api"),
                List.of(
                        "com.smartcs.smart_cs_agent.repository",
                        "com.smartcs.smart_cs_agent.tools",
                        "com.smartcs.smart_cs_agent.controller",
                        "com.smartcs.smart_cs_agent.capability.local"
                )
        );
    }

    @Test
    void capabilityApiShouldRemainIndependentFromImplementations() throws IOException {
        assertNoForbiddenImports(
                Path.of("com/smartcs/smart_cs_agent/capability/api"),
                List.of(
                        "com.smartcs.smart_cs_agent.repository",
                        "com.smartcs.smart_cs_agent.tools",
                        "com.smartcs.smart_cs_agent.controller",
                        "com.smartcs.smart_cs_agent.capability.local",
                        "com.smartcs.smart_cs_agent.capability.core"
                )
        );
    }

    @Test
    void skillPackageApiShouldNotDependOnLoaderRegistryOrBusinessImplementations() throws IOException {
        assertNoForbiddenImports(
                Path.of("com/smartcs/smart_cs_agent/skill/api"),
                List.of(
                        "com.smartcs.smart_cs_agent.repository",
                        "com.smartcs.smart_cs_agent.tools",
                        "com.smartcs.smart_cs_agent.controller",
                        "com.smartcs.smart_cs_agent.skill.loader",
                        "com.smartcs.smart_cs_agent.skill.registry",
                        "com.smartcs.smart_cs_agent.workflow.legacy",
                        "com.smartcs.smart_cs_agent.capability.local"
                )
        );
    }

    @Test
    void actionHandlerApiShouldRemainIndependentFromDefaultHandlersAndInfrastructure() throws IOException {
        assertNoForbiddenImports(
                Path.of("com/smartcs/smart_cs_agent/agent/handler/api"),
                List.of(
                        "com.smartcs.smart_cs_agent.agent.handler.defaults",
                        "com.smartcs.smart_cs_agent.agent.handler.core",
                        "com.smartcs.smart_cs_agent.repository",
                        "com.smartcs.smart_cs_agent.tools",
                        "com.smartcs.smart_cs_agent.controller",
                        "com.smartcs.smart_cs_agent.capability.local",
                        "com.smartcs.smart_cs_agent.workflow.returnapply"
                )
        );
    }

    @Test
    void coreKernelShouldNotImportConcreteBusinessWorkflows() throws IOException {
        List<Path> coreFiles = List.of(
                Path.of("com/smartcs/smart_cs_agent/agent/AgentRuntime.java"),
                Path.of("com/smartcs/smart_cs_agent/workflow/core/WorkflowRouter.java"),
                Path.of("com/smartcs/smart_cs_agent/workflow/core/WorkflowRegistry.java"),
                Path.of("com/smartcs/smart_cs_agent/capability/core/DefaultCapabilityGateway.java")
        );

        for (Path relativeFile : coreFiles) {
            Path sourceFile = MAIN_JAVA.resolve(relativeFile);
            String source = Files.readString(sourceFile);
            assertFalse(
                    source.contains("import com.smartcs.smart_cs_agent.workflow.orderquery"),
                    () -> sourceFile + " must not import OrderQueryWorkflow"
            );
            assertFalse(
                    source.contains("import com.smartcs.smart_cs_agent.workflow.productadvisory"),
                    () -> sourceFile + " must not import ProductAdvisoryWorkflow"
            );
            assertFalse(
                    source.contains("import com.smartcs.smart_cs_agent.workflow.returnapply"),
                    () -> sourceFile + " must not import ReturnApplyWorkflow"
            );
            assertFalse(
                    source.contains("import com.smartcs.smart_cs_agent.workflow.returnquery"),
                    () -> sourceFile + " must not import ReturnQueryWorkflow"
            );
            assertFalse(
                    source.contains("import com.smartcs.smart_cs_agent.workflow.warranty"),
                    () -> sourceFile + " must not import WarrantyQueryWorkflow"
            );
        }
    }

    private void assertNoForbiddenImports(Path relativePackage,
                                          List<String> forbiddenPackages) throws IOException {
        Path packagePath = MAIN_JAVA.resolve(relativePackage);
        try (var files = Files.walk(packagePath)) {
            for (Path sourceFile : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String source = Files.readString(sourceFile);
                for (String forbiddenPackage : forbiddenPackages) {
                    assertFalse(
                            source.contains("import " + forbiddenPackage),
                            () -> sourceFile + " must not import " + forbiddenPackage
                    );
                }
            }
        }
    }
}
