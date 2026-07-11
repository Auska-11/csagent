package com.smartcs.smart_cs_agent.capability.api;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Capability 稳定命名空间和旧工具名兼容映射的唯一来源。
 *
 * <p>新代码只使用 {@code domain.verb}；旧 HTTP/MCP/Legacy Skill 暂时仍可能发出下划线名称。
 * 兼容转换集中在这里，避免每层各维护一份容易不一致的 switch。</p>
 */
public final class CapabilityIds {

    public static final String ORDER_LOOKUP = "order.lookup";
    public static final String ORDER_LIST_BY_USER = "order.list-by-user";
    public static final String PRODUCT_SEARCH = "product.search";
    public static final String PRODUCT_LOOKUP = "product.lookup";
    public static final String RETURN_APPLY = "return.apply";
    public static final String RETURN_LOOKUP = "return.lookup";
    public static final String USER_LOOKUP = "user.lookup";
    public static final String KNOWLEDGE_SEARCH = "knowledge.search";
    public static final String HANDOFF_REQUEST = "handoff.request";

    private static final Map<String, String> LEGACY_TO_CANONICAL;
    private static final Map<String, String> CANONICAL_TO_LEGACY;

    static {
        Map<String, String> aliases = new LinkedHashMap<>();
        aliases.put("order_lookup", ORDER_LOOKUP);
        aliases.put("user_orders", ORDER_LIST_BY_USER);
        aliases.put("product_search", PRODUCT_SEARCH);
        aliases.put("product_info", PRODUCT_LOOKUP);
        aliases.put("return_apply", RETURN_APPLY);
        aliases.put("return_query", RETURN_LOOKUP);
        aliases.put("user_lookup", USER_LOOKUP);
        aliases.put("knowledge_search", KNOWLEDGE_SEARCH);
        aliases.put("handoff_create", HANDOFF_REQUEST);
        LEGACY_TO_CANONICAL = Map.copyOf(aliases);

        Map<String, String> reverse = new LinkedHashMap<>();
        aliases.forEach((legacy, canonical) -> reverse.put(canonical, legacy));
        CANONICAL_TO_LEGACY = Map.copyOf(reverse);
    }

    private CapabilityIds() {
    }

    /** 已是 canonical 时原样返回；未知扩展 ID 也原样返回，交由 Catalog 判断是否存在。 */
    public static String canonicalize(String capabilityId) {
        if (capabilityId == null) {
            return null;
        }
        return LEGACY_TO_CANONICAL.getOrDefault(capabilityId, capabilityId);
    }

    /** Provider 委托旧 ToolRegistry 时使用；没有旧别名则原样返回。 */
    public static String legacyName(String capabilityId) {
        if (capabilityId == null) {
            return null;
        }
        return CANONICAL_TO_LEGACY.getOrDefault(capabilityId, capabilityId);
    }

    public static boolean isLegacyAlias(String capabilityId) {
        return LEGACY_TO_CANONICAL.containsKey(capabilityId);
    }
}
