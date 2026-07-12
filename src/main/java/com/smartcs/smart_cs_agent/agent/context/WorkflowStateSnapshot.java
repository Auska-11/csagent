package com.smartcs.smart_cs_agent.agent.context;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** 单个 Workflow 命名空间的持久化格式。 */
public record WorkflowStateSnapshot(int schemaVersion, Map<String, String> payload) {

    /** 当前所有 Java Workflow State 适配器写出的 payload 结构版本。 */
    public static final int CURRENT_SCHEMA_VERSION = 1;

    public WorkflowStateSnapshot {
        if (schemaVersion <= 0) {
            throw new IllegalArgumentException("workflow state schemaVersion must be positive");
        }
        if (payload == null || payload.isEmpty()) {
            payload = Map.of();
        } else {
            Map<String, String> copy = new LinkedHashMap<>();
            for (Map.Entry<String, String> entry : payload.entrySet()) {
                if (entry.getKey() == null || entry.getKey().isBlank() || entry.getValue() == null) {
                    throw new IllegalArgumentException("workflow payload cannot contain null");
                }
                copy.put(entry.getKey(), entry.getValue());
            }
            payload = Collections.unmodifiableMap(copy);
        }
    }
}
