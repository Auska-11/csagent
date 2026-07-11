package com.smartcs.smart_cs_agent.skill.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 已完整加载且通过文件校验的 Agent Skill Package。
 *
 * <p>Definition 是 manifest 元数据；systemPrompt 和 referenceContents 是文件内容。
 * 该对象构造后不可变，因此每个请求读取到的是同一份一致快照，不会出现读取一半时文件被修改的情况。</p>
 */
public final class AgentSkillPackage {

    private final AgentSkillDefinition definition;
    private final String systemPrompt;
    private final Map<String, String> referenceContents;
    private final String sourceDescription;

    public AgentSkillPackage(AgentSkillDefinition definition,
                             String systemPrompt,
                             Map<String, String> referenceContents,
                             String sourceDescription) {
        this.definition = Objects.requireNonNull(definition, "definition cannot be null");
        if (systemPrompt == null || systemPrompt.isBlank()) {
            throw new IllegalArgumentException("Skill prompt cannot be empty: " + definition.getId());
        }
        this.systemPrompt = systemPrompt;
        this.referenceContents = immutableReferences(definition, referenceContents);
        this.sourceDescription = sourceDescription == null ? "unknown" : sourceDescription;
    }

    public AgentSkillDefinition getDefinition() {
        return definition;
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    public Map<String, String> getReferenceContents() {
        return referenceContents;
    }

    public String getSourceDescription() {
        return sourceDescription;
    }

    /**
     * 根据 purpose 和当前文本，只把命中的 references 拼进系统提示词。
     */
    public ResolvedSkillPrompt resolvePrompt(String purpose, String searchableText) {
        StringBuilder prompt = new StringBuilder(systemPrompt.strip());
        List<String> loadedPaths = new ArrayList<>();

        for (SkillReferenceDefinition reference : definition.getReferences()) {
            if (!reference.matches(purpose, searchableText)) {
                continue;
            }

            String content = referenceContents.get(reference.getPath());
            // 构造函数已经保证内容存在；这里的判断只是防止未来自定义实现返回不完整快照。
            if (content == null) {
                throw new IllegalStateException("Reference content missing from loaded snapshot: " + reference.getPath());
            }
            loadedPaths.add(reference.getPath());
            prompt.append("\n\n# 按需加载的参考资料\n\n")
                    .append("<!-- source: ").append(reference.getPath()).append(" -->\n")
                    .append(content.strip());
        }

        return new ResolvedSkillPrompt(
                definition.getId(),
                definition.getVersion(),
                prompt.toString(),
                loadedPaths
        );
    }

    private static Map<String, String> immutableReferences(AgentSkillDefinition definition,
                                                            Map<String, String> source) {
        Map<String, String> safeSource = source == null ? Map.of() : source;
        LinkedHashMap<String, String> result = new LinkedHashMap<>();

        for (SkillReferenceDefinition reference : definition.getReferences()) {
            String content = safeSource.get(reference.getPath());
            if (content == null || content.isBlank()) {
                throw new IllegalArgumentException(
                        "Reference is missing or empty for skill " + definition.getId() + ": " + reference.getPath()
                );
            }
            result.put(reference.getPath(), content);
        }
        return Collections.unmodifiableMap(result);
    }
}
