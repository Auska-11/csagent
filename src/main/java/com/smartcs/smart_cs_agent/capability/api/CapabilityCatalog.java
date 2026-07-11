package com.smartcs.smart_cs_agent.capability.api;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 当前运行时可用 Capability 的不可变目录。
 *
 * <p>Catalog 只保存契约，不执行调用。构造时发现重复 ID 会立即失败，避免两个 Provider 都声明
 * {@code order.lookup} 时由注册顺序悄悄决定实际实现。</p>
 */
public final class CapabilityCatalog {

    private final Map<String, CapabilityDefinition> definitions;

    public CapabilityCatalog(Collection<CapabilityDefinition> definitions) {
        Map<String, CapabilityDefinition> byId = new LinkedHashMap<>();
        if (definitions != null) {
            for (CapabilityDefinition definition : definitions) {
                CapabilityDefinition previous = byId.putIfAbsent(definition.getId(), definition);
                if (previous != null) {
                    throw new IllegalArgumentException("Duplicate capability definition: " + definition.getId());
                }
            }
        }
        this.definitions = Collections.unmodifiableMap(byId);
    }

    public CapabilityDefinition get(String capabilityId) {
        return definitions.get(capabilityId);
    }

    public boolean contains(String capabilityId) {
        return definitions.containsKey(capabilityId);
    }

    public Collection<CapabilityDefinition> list() {
        return definitions.values();
    }
}
