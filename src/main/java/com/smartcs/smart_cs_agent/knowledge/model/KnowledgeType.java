package com.smartcs.smart_cs_agent.knowledge.model;

import java.util.Locale;

/** 客服知识的业务类型；实时订单、库存和售后进度不属于这里。 */
public enum KnowledgeType {
    POLICY,
    TROUBLESHOOTING;

    public static KnowledgeType parse(String value, KnowledgeType fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }
}
