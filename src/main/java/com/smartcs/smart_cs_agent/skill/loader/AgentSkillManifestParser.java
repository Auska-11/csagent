package com.smartcs.smart_cs_agent.skill.loader;

import com.smartcs.smart_cs_agent.skill.api.AgentSkillDefinition;
import com.smartcs.smart_cs_agent.skill.api.AgentSkillPackage;
import com.smartcs.smart_cs_agent.skill.api.RiskLevel;
import com.smartcs.smart_cs_agent.skill.api.SkillReferenceDefinition;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 把一份 manifest.yaml 和它旁边的文件解析成强类型 AgentSkillPackage。
 *
 * <p>Parser 只负责“一份包”；发现有哪些包属于 Loader 的职责。这样单元测试可以直接把一个
 * 临时 manifest 交给 Parser，错误定位也会比所有逻辑挤在一个大类中更清楚。</p>
 */
@Component
public class AgentSkillManifestParser {

    private static final Pattern SKILL_ID_PATTERN = Pattern.compile("[a-z][a-z0-9-]{0,63}");
    private static final Pattern WORKFLOW_ID_PATTERN = Pattern.compile("[a-z][a-z0-9_-]{0,63}");
    private static final Pattern SEMVER_PATTERN = Pattern.compile(
            "[0-9]+\\.[0-9]+\\.[0-9]+(?:[-+][0-9A-Za-z.-]+)?"
    );

    /**
     * SafeConstructor 只解析普通 YAML 数据，不允许 YAML 通过类型标签实例化任意 Java 类。
     * 同时禁用重复 key，避免两段 allowed_capabilities 中后一段悄悄覆盖前一段。
     */
    private final Yaml yaml;

    public AgentSkillManifestParser() {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(50);
        options.setCodePointLimit(2_000_000);
        this.yaml = new Yaml(new SafeConstructor(options));
    }

    public AgentSkillPackage parse(Resource manifestResource) {
        if (manifestResource == null || !manifestResource.exists()) {
            throw new AgentSkillLoadException("Skill manifest does not exist: " + describe(manifestResource));
        }

        Map<String, Object> manifest = readYamlMap(manifestResource);
        try {
            String id = firstNonBlank(optionalString(manifest, "id"), optionalString(manifest, "name"));
            String version = requiredString(manifest, "version");
            String workflowId = requiredString(manifest, "workflow");
            validateIdentity(id, version, workflowId, manifestResource);

            List<String> triggers = stringList(manifest.get("triggers"), "triggers");
            Set<String> allowedCapabilities = new LinkedHashSet<>(
                    stringList(manifest.get("allowed_capabilities"), "allowed_capabilities")
            );
            List<SkillReferenceDefinition> references = referenceDefinitions(manifest.get("references"));
            RiskLevel riskLevel = RiskLevel.fromManifest(requiredString(manifest, "risk_level"));

            String promptPath = validatePackageRelativePath(requiredString(manifest, "prompt"), "prompt");
            String confirmationPolicy = optionalString(manifest, "confirmation_policy");

            AgentSkillDefinition definition = new AgentSkillDefinition(
                    id,
                    version,
                    optionalString(manifest, "description"),
                    triggers,
                    workflowId,
                    integerValue(manifest.get("priority"), 0, "priority"),
                    allowedCapabilities,
                    promptPath,
                    references,
                    riskLevel,
                    confirmationPolicy
            );

            String prompt = readRelativeText(manifestResource, promptPath, "prompt");
            Map<String, String> referenceContents = new LinkedHashMap<>();
            for (SkillReferenceDefinition reference : references) {
                String content = readRelativeText(manifestResource, reference.getPath(), "reference");
                referenceContents.put(reference.getPath(), content);
            }

            return new AgentSkillPackage(
                    definition,
                    prompt,
                    referenceContents,
                    manifestResource.getDescription()
            );
        } catch (AgentSkillLoadException e) {
            throw e;
        } catch (IllegalArgumentException e) {
            throw new AgentSkillLoadException(
                    "Invalid Skill manifest " + manifestResource.getDescription() + ": " + e.getMessage(),
                    e
            );
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readYamlMap(Resource resource) {
        try (Reader reader = new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8)) {
            Object loaded = yaml.load(reader);
            if (!(loaded instanceof Map<?, ?> rawMap)) {
                throw new AgentSkillLoadException(
                        "Skill manifest root must be a YAML object: " + resource.getDescription()
                );
            }

            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    throw new AgentSkillLoadException(
                            "Skill manifest keys must be strings: " + resource.getDescription()
                    );
                }
                result.put(key, entry.getValue());
            }
            return result;
        } catch (AgentSkillLoadException e) {
            throw e;
        } catch (Exception e) {
            throw new AgentSkillLoadException("Cannot parse Skill manifest: " + resource.getDescription(), e);
        }
    }

    private List<SkillReferenceDefinition> referenceDefinitions(Object value) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof Iterable<?> iterable)) {
            throw new IllegalArgumentException("references must be a YAML list");
        }

        List<SkillReferenceDefinition> result = new ArrayList<>();
        Set<String> seenPaths = new LinkedHashSet<>();
        for (Object item : iterable) {
            SkillReferenceDefinition definition;
            if (item instanceof String path) {
                // 兼容最简写法：字符串 reference 默认每次加载。
                definition = new SkillReferenceDefinition(
                        validatePackageRelativePath(path, "reference"),
                        Set.of(),
                        List.of(),
                        true
                );
            } else if (item instanceof Map<?, ?> rawMap) {
                Map<String, Object> referenceMap = stringKeyMap(rawMap, "reference");
                String path = validatePackageRelativePath(requiredString(referenceMap, "path"), "reference");
                definition = new SkillReferenceDefinition(
                        path,
                        new LinkedHashSet<>(stringList(referenceMap.get("purposes"), "reference.purposes")),
                        stringList(referenceMap.get("triggers"), "reference.triggers"),
                        booleanValue(referenceMap.get("always"), false, "reference.always")
                );
            } else {
                throw new IllegalArgumentException("Each reference must be a path string or an object");
            }

            if (!seenPaths.add(definition.getPath())) {
                throw new IllegalArgumentException("Duplicate reference path: " + definition.getPath());
            }
            if (!definition.isAlways()
                    && definition.getPurposes().isEmpty()
                    && definition.getTriggers().isEmpty()) {
                throw new IllegalArgumentException(
                        "Reference must declare always, purposes or triggers: " + definition.getPath()
                );
            }
            result.add(definition);
        }
        return result;
    }

    private Map<String, Object> stringKeyMap(Map<?, ?> source, String fieldName) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new IllegalArgumentException(fieldName + " keys must be strings");
            }
            result.put(key, entry.getValue());
        }
        return result;
    }

    private String readRelativeText(Resource manifest, String relativePath, String type) {
        try {
            Resource resource = manifest.createRelative(relativePath);
            if (!resource.exists() || !resource.isReadable()) {
                throw new AgentSkillLoadException(
                        "Skill " + type + " does not exist or is not readable: " + relativePath
                                + " (manifest: " + manifest.getDescription() + ")"
                );
            }
            String content = resource.getContentAsString(StandardCharsets.UTF_8);
            if (content.isBlank()) {
                throw new AgentSkillLoadException(
                        "Skill " + type + " cannot be empty: " + relativePath
                                + " (manifest: " + manifest.getDescription() + ")"
                );
            }
            return content;
        } catch (AgentSkillLoadException e) {
            throw e;
        } catch (IOException e) {
            throw new AgentSkillLoadException(
                    "Cannot read Skill " + type + ": " + relativePath
                            + " (manifest: " + manifest.getDescription() + ")",
                    e
            );
        }
    }

    private void validateIdentity(String id, String version, String workflowId, Resource resource) {
        if (id == null || !SKILL_ID_PATTERN.matcher(id).matches()) {
            throw new IllegalArgumentException(
                    "id must use lowercase letters, digits and hyphens: " + id
                            + " (manifest: " + resource.getDescription() + ")"
            );
        }
        if (!SEMVER_PATTERN.matcher(version).matches()) {
            throw new IllegalArgumentException("version must be semantic version such as 1.0.0: " + version);
        }
        if (!WORKFLOW_ID_PATTERN.matcher(workflowId).matches()) {
            throw new IllegalArgumentException(
                    "workflow must use lowercase letters, digits, underscores or hyphens: " + workflowId
            );
        }
    }

    private String validatePackageRelativePath(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " path cannot be empty");
        }
        String normalized = value.trim().replace('\\', '/');
        if (normalized.startsWith("/")
                || normalized.contains("../")
                || normalized.equals("..")
                || normalized.contains(":")) {
            throw new IllegalArgumentException(fieldName + " path must stay inside its Skill package: " + value);
        }
        return normalized;
    }

    private List<String> stringList(Object value, String fieldName) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof Iterable<?> iterable)) {
            throw new IllegalArgumentException(fieldName + " must be a YAML list");
        }
        List<String> result = new ArrayList<>();
        for (Object item : iterable) {
            if (!(item instanceof String text) || text.isBlank()) {
                throw new IllegalArgumentException(fieldName + " must contain non-empty strings");
            }
            result.add(text.trim());
        }
        return result;
    }

    private String requiredString(Map<String, Object> source, String fieldName) {
        String result = optionalString(source, fieldName);
        if (result.isBlank()) {
            throw new IllegalArgumentException(fieldName + " cannot be empty");
        }
        return result;
    }

    private String optionalString(Map<String, Object> source, String fieldName) {
        Object value = source.get(fieldName);
        if (value == null) {
            return "";
        }
        if (!(value instanceof String text)) {
            throw new IllegalArgumentException(fieldName + " must be a string");
        }
        return text.trim();
    }

    private int integerValue(Object value, int defaultValue, String fieldName) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        throw new IllegalArgumentException(fieldName + " must be an integer");
    }

    private boolean booleanValue(Object value, boolean defaultValue, String fieldName) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        throw new IllegalArgumentException(fieldName + " must be true or false");
    }

    private String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second;
    }

    private String describe(Resource resource) {
        return resource == null ? "null" : resource.getDescription();
    }
}
