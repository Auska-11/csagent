package com.smartcs.smart_cs_agent.skill.loader;

import com.smartcs.smart_cs_agent.skill.api.AgentSkillLoader;
import com.smartcs.smart_cs_agent.skill.api.AgentSkillPackage;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * 根据配置从 classpath 或外部目录发现并加载 YAML Skill Package。
 *
 * <p>发现顺序会先排序，保证每次启动得到稳定顺序；虽然 Registry 不依赖顺序，稳定输出更方便
 * 测试和排查。Loader 每次 load 都创建完整快照，不把半解析对象暴露给运行时。</p>
 */
@Component
public class YamlAgentSkillLoader implements AgentSkillLoader {

    private static final String CLASSPATH_PATTERN = "classpath*:skills/*/manifest.yaml";

    private final AgentSkillProperties properties;
    private final AgentSkillManifestParser parser;
    private final ResourcePatternResolver resourceResolver;

    @Autowired
    public YamlAgentSkillLoader(AgentSkillProperties properties,
                                AgentSkillManifestParser parser) {
        this(properties, parser, new PathMatchingResourcePatternResolver());
    }

    /**
     * 允许测试传入自定义 ResourceResolver，不需要启动完整 Spring 容器。
     */
    public YamlAgentSkillLoader(AgentSkillProperties properties,
                                AgentSkillManifestParser parser,
                                ResourcePatternResolver resourceResolver) {
        this.properties = properties;
        this.parser = parser;
        this.resourceResolver = resourceResolver;
    }

    @Override
    public List<AgentSkillPackage> load() {
        String source = properties.getSource() == null
                ? "classpath"
                : properties.getSource().trim().toLowerCase(Locale.ROOT);

        return switch (source) {
            case "classpath" -> parseAll(findClasspathManifests());
            case "external-directory" -> parseAll(findExternalManifests());
            case "legacy" -> List.of();
            default -> throw new AgentSkillLoadException(
                    "Unsupported smartcs.skill.source '" + properties.getSource()
                            + "'. Expected classpath, external-directory or legacy"
            );
        };
    }

    private List<Resource> findClasspathManifests() {
        try {
            Resource[] resources = resourceResolver.getResources(CLASSPATH_PATTERN);
            return Arrays.stream(resources)
                    .sorted(Comparator.comparing(Resource::getDescription))
                    .toList();
        } catch (IOException e) {
            throw new AgentSkillLoadException("Cannot scan classpath Skill packages", e);
        }
    }

    private List<Resource> findExternalManifests() {
        String configuredDirectory = properties.getExternalDirectory();
        if (configuredDirectory == null || configuredDirectory.isBlank()) {
            throw new AgentSkillLoadException(
                    "smartcs.skill.external-directory is required when source=external-directory"
            );
        }

        Path root = Path.of(configuredDirectory).toAbsolutePath().normalize();
        if (!Files.isDirectory(root) || !Files.isReadable(root)) {
            throw new AgentSkillLoadException("External Skill directory is not readable: " + root);
        }

        try (Stream<Path> children = Files.list(root)) {
            return children
                    .filter(Files::isDirectory)
                    .map(child -> child.resolve("manifest.yaml"))
                    .filter(Files::isRegularFile)
                    .sorted()
                    .map(FileSystemResource::new)
                    .map(resource -> (Resource) resource)
                    .toList();
        } catch (IOException e) {
            throw new AgentSkillLoadException("Cannot scan external Skill directory: " + root, e);
        }
    }

    private List<AgentSkillPackage> parseAll(List<Resource> manifests) {
        List<AgentSkillPackage> packages = new ArrayList<>();
        for (Resource manifest : manifests) {
            packages.add(parser.parse(manifest));
        }
        return List.copyOf(packages);
    }
}
