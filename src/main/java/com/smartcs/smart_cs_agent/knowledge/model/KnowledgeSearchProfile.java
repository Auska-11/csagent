package com.smartcs.smart_cs_agent.knowledge.model;

/** knowledge.search 的内部检索 Profile。空过滤字段表示不限制。 */
public record KnowledgeSearchProfile(
        KnowledgeType knowledgeType,
        String productCategory,
        String productModel,
        String version
) {
    public KnowledgeSearchProfile {
        productCategory = safe(productCategory);
        productModel = safe(productModel);
        version = safe(version);
    }

    public static KnowledgeSearchProfile auto() {
        return new KnowledgeSearchProfile(null, "", "", "");
    }

    public static KnowledgeSearchProfile policy() {
        return new KnowledgeSearchProfile(KnowledgeType.POLICY, "", "", "");
    }

    public static KnowledgeSearchProfile troubleshooting(String productCategory, String productModel) {
        return new KnowledgeSearchProfile(
                KnowledgeType.TROUBLESHOOTING,
                productCategory,
                productModel,
                ""
        );
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
