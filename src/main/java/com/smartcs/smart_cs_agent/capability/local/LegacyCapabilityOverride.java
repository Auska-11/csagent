package com.smartcs.smart_cs_agent.capability.local;

import java.util.Set;

/**
 * 独立本地 Provider 用来声明“我已经接管哪些旧 Capability”的扩展接口。
 *
 * <p>ToolRegistryCapabilityProvider 会自动收集所有实现，并隐藏对应的旧工具。这样新增 Provider 时
 * 不需要再向兼容 Adapter 添加一个业务专用字段或 if 分支。</p>
 */
public interface LegacyCapabilityOverride {

    Set<String> overriddenCapabilityIds();
}
