package com.smartcs.smart_cs_agent.skill.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一次 LLM 调用最终使用的 Skill Prompt 快照。
 *
 * <p>除了最终文本，还保留 Skill ID、版本和本次选中的 reference 路径。
 * Runtime 因此可以生成可观察 Trace，排查模型回答时能够知道“它当时实际看到了哪些资料”。</p>
 */
public final class ResolvedSkillPrompt {

    private final String skillId;
    private final String skillVersion;
    private final String content;
    private final List<String> loadedReferences;

    public ResolvedSkillPrompt(String skillId,
                               String skillVersion,
                               String content,
                               List<String> loadedReferences) {
        this.skillId = skillId == null ? "legacy" : skillId;
        this.skillVersion = skillVersion == null ? "legacy" : skillVersion;
        this.content = content == null ? "" : content;
        this.loadedReferences = loadedReferences == null
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(loadedReferences));
    }

    public String getSkillId() {
        return skillId;
    }

    public String getSkillVersion() {
        return skillVersion;
    }

    public String getContent() {
        return content;
    }

    public List<String> getLoadedReferences() {
        return loadedReferences;
    }
}
