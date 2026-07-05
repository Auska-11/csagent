package com.smartcs.smart_cs_agent.skill;

import com.smartcs.smart_cs_agent.config.SkillConfig;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Skill 管理器
 *
 * 【职责】
 * 1. 管理所有注册的 Skill
 * 2. 获取当前活跃的 Skill
 * 3. 加载 System Prompt
 * 4. 后续支持多 Skill 路由
 *
 * 【当前设计】
 * 目前只有一个 CustomerServiceSkill，直接返回。
 * 后续做多 Agent 时，可以注册多个 Skill，根据消息选择。
 */
@Component
public class SkillManager {

    private final Map<String, Skill> skills = new HashMap<>();
    private final SkillConfig skillConfig;
    private String activeSkillName;

    /**
     * 构造函数注入
     */
    public SkillManager(SkillConfig skillConfig) {
        this.skillConfig = skillConfig;
    }

    /**
     * 注册 Skill
     *
     * @param skill Skill 实例
     */
    public void registerSkill(Skill skill) {
        skills.put(skill.getName(), skill);
        // 第一个注册的 Skill 默认为活跃 Skill
        if (activeSkillName == null) {
            activeSkillName = skill.getName();
        }
    }

    /**
     * 获取当前活跃的 Skill
     *
     * @return 当前 Skill
     */
    public Skill getActiveSkill() {
        if (activeSkillName == null || !skills.containsKey(activeSkillName)) {
            throw new IllegalStateException("No active skill registered");
        }
        return skills.get(activeSkillName);
    }

    /**
     * 设置活跃的 Skill
     *
     * @param skillName Skill 名称
     */
    public void setActiveSkill(String skillName) {
        if (!skills.containsKey(skillName)) {
            throw new IllegalArgumentException("Skill not found: " + skillName);
        }
        this.activeSkillName = skillName;
    }

    /**
     * 获取 System Prompt
     *
     * 从 SkillConfig 加载，内容来自 prompts/system-prompt.md 文件
     *
     * @return System Prompt 内容
     */
    public String getSystemPrompt() {
        return skillConfig.getSystemPrompt();
    }

    /**
     * 获取所有注册的 Skill 名称
     *
     * @return Skill 名称列表
     */
    public List<String> getSkillNames() {
        return new ArrayList<>(skills.keySet());
    }

    /**
     * 根据消息选择最合适的 Skill（后续多 Skill 路由用）
     *
     * @param message 用户消息
     * @return 最合适的 Skill
     */
    public Skill selectSkill(String message) {
        Skill bestSkill = null;
        float bestScore = 0.0f;

        for (Skill skill : skills.values()) {
            float score = skill.canHandle(message);
            if (score > bestScore) {
                bestScore = score;
                bestSkill = skill;
            }
        }

        return bestSkill != null ? bestSkill : getActiveSkill();
    }
}