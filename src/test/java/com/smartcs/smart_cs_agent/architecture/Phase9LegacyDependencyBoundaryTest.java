package com.smartcs.smart_cs_agent.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Phase 9 兼容层依赖门禁。
 *
 * <p>本测试记录“当前允许依赖旧类型的生产文件”，并禁止依赖继续扩散。每迁移一批调用方，
 * 都要同步缩小允许清单；空清单可以证明已删除的入口不会被悄悄引回生产代码。</p>
 *
 * <p>这里检查的是生产源码，不限制历史测试。历史测试会在对应兼容入口删除时一起迁移，
 * 这样每一批改造都能保持改动范围清晰。</p>
 */
class Phase9LegacyDependencyBoundaryTest {

    private static final Path MAIN_JAVA = Path.of("src/main/java");

    @Test
    void legacySkillWorkflowAdapterShouldNotGainNewProductionCallers() throws IOException {
        assertOnlyExpectedFilesImport(
                "com.smartcs.smart_cs_agent.workflow.legacy.LegacySkillWorkflowAdapter",
                Set.of()
        );
    }

    @Test
    void customerServiceSkillShouldNotGainNewProductionCallers() throws IOException {
        assertOnlyExpectedFilesImport(
                "com.smartcs.smart_cs_agent.skill.CustomerServiceSkill",
                Set.of()
        );
    }

    @Test
    void toolManagerShouldNotGainNewProductionCallers() throws IOException {
        assertOnlyExpectedFilesImport(
                "com.smartcs.smart_cs_agent.tools.ToolManager",
                Set.of()
        );
    }

    @Test
    void toolRegistryAdapterShouldRemainIsolatedBehindCompatibilityFacade() throws IOException {
        assertOnlyExpectedFilesImport(
                "com.smartcs.smart_cs_agent.tools.ToolRegistry",
                Set.of("com/smartcs/smart_cs_agent/capability/local/ToolRegistryCapabilityProvider.java")
        );
        assertOnlyExpectedFilesImport(
                "com.smartcs.smart_cs_agent.capability.local.ToolRegistryCapabilityProvider",
                Set.of()
        );
    }

    @Test
    void methodLevelCompatibilityEntrypointsShouldNotSpread() throws IOException {
        assertOnlyExpectedFilesContain(
                "ExecutionContext.legacy()",
                Set.of()
        );
        assertOnlyExpectedFilesContain(
                "legacyTestRegistry",
                Set.of()
        );
    }

    /**
     * 比较实际 import 文件与允许清单，而不是只断言“不能新增某个包”。
     *
     * <p>使用完全相等有两个好处：新增依赖会失败；迁移掉依赖但忘记缩小清单也会失败。
     * 因此测试本身会推动文档和架构状态同步更新。</p>
     */
    private void assertOnlyExpectedFilesImport(String fullyQualifiedType,
                                               Set<String> expectedRelativeFiles) throws IOException {
        assertOnlyExpectedFilesContain(
                "import " + fullyQualifiedType + ";",
                expectedRelativeFiles
        );
    }

    /** 方法调用没有 import 可查，所以复用同一套源码文件清单比较。 */
    private void assertOnlyExpectedFilesContain(String expectedText,
                                                Set<String> expectedRelativeFiles) throws IOException {
        Set<String> actualRelativeFiles;
        try (var files = Files.walk(MAIN_JAVA)) {
            actualRelativeFiles = files
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> contains(path, expectedText))
                    .map(MAIN_JAVA::relativize)
                    // 统一为 '/'，避免 Windows 与 macOS 的路径分隔符差异导致测试误报。
                    .map(path -> path.toString().replace('\\', '/'))
                    .collect(Collectors.toSet());
        }

        assertEquals(
                expectedRelativeFiles,
                actualRelativeFiles,
                () -> expectedText + " 的生产依赖清单发生变化；请确认这是迁移收缩而不是旧依赖扩散"
        );
    }

    private boolean contains(Path sourceFile, String expectedText) {
        try {
            return Files.readString(sourceFile).contains(expectedText);
        } catch (IOException error) {
            throw new IllegalStateException("无法读取生产源码：" + sourceFile, error);
        }
    }
}
