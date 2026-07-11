package com.smartcs.smart_cs_agent.skill.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 一份 Skill 参考资料及其按需加载规则。
 *
 * <p>如果只在 manifest 中写一个文件列表，Runtime 只能每轮把全部资料塞给模型，
 * 很快就会造成上下文过长。这里让每个 reference 声明：</p>
 * <ul>
 *     <li>适用的 LLM purpose，例如 intent_recognition；</li>
 *     <li>触发关键词，例如“退货”“保修”；</li>
 *     <li>是否每次都加载。</li>
 * </ul>
 *
 * <p>purpose 和 trigger 采用“任一命中即可加载”。这是一版简单、确定、容易测试的选择器，
 * 后续可以在不改变 Skill Package 结构的情况下替换成更智能的检索器。</p>
 */
public final class SkillReferenceDefinition {

    private final String path;
    private final Set<String> purposes;
    private final List<String> triggers;
    private final boolean always;

    public SkillReferenceDefinition(String path,
                                    Set<String> purposes,
                                    List<String> triggers,
                                    boolean always) {
        this.path = validateRelativePath(path);

        LinkedHashSet<String> normalizedPurposes = new LinkedHashSet<>();
        if (purposes != null) {
            for (String purpose : purposes) {
                if (purpose != null && !purpose.isBlank()) {
                    normalizedPurposes.add(purpose.trim().toLowerCase(Locale.ROOT));
                }
            }
        }
        this.purposes = Collections.unmodifiableSet(normalizedPurposes);

        List<String> normalizedTriggers = new ArrayList<>();
        if (triggers != null) {
            for (String trigger : triggers) {
                if (trigger != null && !trigger.isBlank()) {
                    normalizedTriggers.add(trigger.trim().toLowerCase(Locale.ROOT));
                }
            }
        }
        this.triggers = Collections.unmodifiableList(normalizedTriggers);
        this.always = always;
    }

    public String getPath() {
        return path;
    }

    public Set<String> getPurposes() {
        return purposes;
    }

    public List<String> getTriggers() {
        return triggers;
    }

    public boolean isAlways() {
        return always;
    }

    /**
     * 判断当前 LLM 调用是否需要这份资料。
     *
     * @param purpose LLMAction 的用途
     * @param searchableText 当前用户消息与本次 prompt 拼成的可检索文本
     */
    public boolean matches(String purpose, String searchableText) {
        if (always) {
            return true;
        }

        String normalizedPurpose = purpose == null ? "" : purpose.trim().toLowerCase(Locale.ROOT);
        if (purposes.contains(normalizedPurpose)) {
            return true;
        }

        String normalizedText = searchableText == null ? "" : searchableText.toLowerCase(Locale.ROOT);
        for (String trigger : triggers) {
            if (normalizedText.contains(trigger)) {
                return true;
            }
        }
        return false;
    }

    private static String validateRelativePath(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("reference path cannot be empty");
        }

        String normalized = value.trim().replace('\\', '/');
        if (normalized.startsWith("/")
                || normalized.contains("../")
                || normalized.equals("..")
                || normalized.contains(":")) {
            throw new IllegalArgumentException("reference path must stay inside its Skill package: " + value);
        }
        return normalized;
    }
}
