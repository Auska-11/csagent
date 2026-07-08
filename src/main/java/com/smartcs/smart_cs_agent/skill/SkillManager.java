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

    /**
     * Multi-Agent 路由第一版。
     *
     * 当前系统仍然复用 CustomerServiceSkill 执行具体动作，
     * 但会先把用户消息路由到一个“专家域”：
     * - pre_sales_agent：售前咨询，商品、价格、库存、推荐
     * - after_sales_agent：售后处理，订单、物流、退换货、政策
     * - complaint_agent：投诉/人工/赔偿/维权
     * - general_agent：通用客服兜底
     *
     * 这样做的价值是先把路由决策做成可观察 Trace，
     * 后续要拆真正独立 Agent 时，边界已经清楚。
     */
    public AgentRouteResult routeAgent(String message) {
        String text = message == null ? "" : message.toLowerCase(Locale.ROOT);

        if (containsAny(text, "投诉", "赔偿", "维权", "人工", "真人", "差评", "客服经理")) {
            return new AgentRouteResult(
                    "complaint",
                    "complaint_agent",
                    "命中投诉、人工或赔偿类表达，优先交给投诉/人工路由",
                    0.95
            );
        }

        if (containsAny(text, "退货", "退款", "换货", "售后", "订单", "物流", "快递", "发货", "签收", "保修", "发票")) {
            return new AgentRouteResult(
                    "after_sales",
                    "after_sales_agent",
                    "命中订单、物流、退换货或政策类表达，交给售后路由",
                    0.88
            );
        }

        if (containsAny(text, "商品", "产品", "价格", "多少钱", "库存", "有货", "推荐", "耳机", "手表", "充电器", "数据线", "蓝牙")) {
            return new AgentRouteResult(
                    "pre_sales",
                    "pre_sales_agent",
                    "命中商品咨询、价格、库存或推荐类表达，交给售前路由",
                    0.86
            );
        }

        return new AgentRouteResult(
                "general",
                "general_agent",
                "未命中明确专家域，使用通用客服路由兜底",
                0.55
        );
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
