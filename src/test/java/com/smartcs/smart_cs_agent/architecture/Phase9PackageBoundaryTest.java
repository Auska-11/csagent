package com.smartcs.smart_cs_agent.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Phase 9 单 Maven Module 的包边界门禁。
 *
 * <p>暂不拆 Maven Modules 不代表允许任意跨层依赖。这里保护未来可拆分所需的单向边界：核心只看
 * 契约，Workflow 不看具体 Provider，MCP 不看本地业务实现，基础数据层不反向依赖上层。</p>
 *
 * <p>测试只检查 Java import，不扫描注释和字符串，因此架构文档可以正常讲解历史类型。</p>
 */
class Phase9PackageBoundaryTest {

    private static final Path MAIN_JAVA = Path.of("src/main/java");
    private static final String BASE = "com.smartcs.smart_cs_agent.";

    @Test
    void agentCoreShouldNotDependOnBusinessOrTransportAdapters() throws IOException {
        assertNoImports(
                "com/smartcs/smart_cs_agent/agent",
                Set.of(
                        BASE + "repository.",
                        BASE + "domain.",
                        BASE + "tools.",
                        BASE + "capability.local.",
                        BASE + "mcp.",
                        BASE + "controller."
                )
        );
    }

    @Test
    void workflowsShouldUseCapabilityContractsInsteadOfImplementations() throws IOException {
        assertNoImports(
                "com/smartcs/smart_cs_agent/workflow",
                Set.of(
                        BASE + "repository.",
                        BASE + "domain.",
                        BASE + "tools.",
                        BASE + "capability.local.",
                        BASE + "mcp.",
                        BASE + "controller."
                )
        );
    }

    @Test
    void capabilityApiShouldNotDependOnGatewayOrProviderImplementations() throws IOException {
        assertNoImports(
                "com/smartcs/smart_cs_agent/capability/api",
                Set.of(
                        BASE + "capability.core.",
                        BASE + "capability.local.",
                        BASE + "capability.idempotency.",
                        BASE + "repository.",
                        BASE + "domain.",
                        BASE + "tools.",
                        BASE + "mcp.",
                        BASE + "controller.",
                        BASE + "workflow.",
                        BASE + "skill."
                )
        );
    }

    @Test
    void mcpAdapterShouldNotReachIntoLocalBusinessImplementations() throws IOException {
        assertNoImports(
                "com/smartcs/smart_cs_agent/mcp",
                Set.of(
                        BASE + "repository.",
                        BASE + "domain.",
                        BASE + "tools.",
                        BASE + "workflow.",
                        BASE + "skill.",
                        BASE + "capability.local.",
                        BASE + "controller."
                )
        );
    }

    private void assertNoImports(String sourceDirectory,
                                 Set<String> forbiddenPackagePrefixes) throws IOException {
        Path root = MAIN_JAVA.resolve(sourceDirectory);
        List<String> violations = new ArrayList<>();

        try (var files = Files.walk(root)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                for (String line : Files.readAllLines(file)) {
                    String trimmed = line.trim();
                    for (String forbiddenPrefix : forbiddenPackagePrefixes) {
                        if (trimmed.startsWith("import " + forbiddenPrefix)) {
                            violations.add(MAIN_JAVA.relativize(file) + " -> " + trimmed);
                        }
                    }
                }
            }
        }

        assertEquals(
                List.of(),
                violations,
                () -> sourceDirectory + " 出现反向依赖；请通过 API/Port 传递，不要直接引用实现层：\n"
                        + String.join("\n", violations)
        );
    }
}
