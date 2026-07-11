package com.smartcs.smart_cs_agent.skill.api;

import java.util.Locale;

/**
 * Agent Skill Package 的风险级别。
 *
 * <p>这里描述的是“这个 Skill 被允许做什么类型的事情”，不是某次调用是否成功：</p>
 * <ul>
 *     <li>{@link #READ_ONLY}：只查询数据，例如查订单。</li>
 *     <li>{@link #SIDE_EFFECT}：会修改业务状态，例如提交退货。</li>
 *     <li>{@link #MIXED}：既包含查询，也包含有副作用的操作。</li>
 * </ul>
 *
 * <p>把风险写进 manifest 后，Guardrail 就可以在执行前统一检查确认、身份和幂等，
 * 而不需要每个 Workflow 各写一套容易遗漏的安全逻辑。</p>
 */
public enum RiskLevel {
    READ_ONLY,
    SIDE_EFFECT,
    MIXED;

    /**
     * YAML 中通常使用小写和下划线，本方法把它转换成 Java 枚举。
     */
    public static RiskLevel fromManifest(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("risk_level cannot be empty");
        }

        String normalized = value.trim()
                .replace('-', '_')
                .toUpperCase(Locale.ROOT);
        try {
            return RiskLevel.valueOf(normalized);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Unsupported risk_level '" + value + "'. Expected read_only, side_effect or mixed",
                    e
            );
        }
    }

    public boolean mayCauseSideEffects() {
        return this == SIDE_EFFECT || this == MIXED;
    }
}
