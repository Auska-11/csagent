package com.smartcs.smart_cs_agent.knowledge.model;

/** 内部检索范围。空渠道表示通用资料范围；空品类、型号和版本表示未指定这些过滤条件。 */
public record KnowledgeSearchProfile(
        KnowledgeType knowledgeType,
        String productCategory,
        String productModel,
        String version,
        String channel
) {
    public KnowledgeSearchProfile(KnowledgeType knowledgeType, String productCategory, String productModel, String version) {
        this(knowledgeType, productCategory, productModel, version, "");
    }

    public KnowledgeSearchProfile {
        channel = channel == null ? "" : channel.trim().toLowerCase(java.util.Locale.ROOT);
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
