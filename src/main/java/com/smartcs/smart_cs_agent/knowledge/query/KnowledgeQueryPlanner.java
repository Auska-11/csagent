package com.smartcs.smart_cs_agent.knowledge.query;

import com.smartcs.smart_cs_agent.knowledge.model.KnowledgeQueryPlan;
import com.smartcs.smart_cs_agent.knowledge.model.KnowledgeSearchProfile;
import com.smartcs.smart_cs_agent.knowledge.model.KnowledgeSearchRequest;
import com.smartcs.smart_cs_agent.knowledge.model.KnowledgeType;

import java.util.Locale;

/** 选择政策/故障策略并补全品类、型号过滤；专业 Workflow 传入的显式类型优先。 */
public class KnowledgeQueryPlanner {

    private final QueryRewriter queryRewriter;

    public KnowledgeQueryPlanner(QueryRewriter queryRewriter) {
        this.queryRewriter = queryRewriter;
    }

    public KnowledgeQueryPlan plan(KnowledgeSearchRequest request) {
        KnowledgeSearchProfile profile = request.profile();
        KnowledgeType type = profile.knowledgeType() == null
                ? inferType(request.query())
                : profile.knowledgeType();
        String category = profile.productCategory().isBlank()
                ? (type == KnowledgeType.TROUBLESHOOTING ? inferProductCategory(request.query()) : "")
                : normalizeCategory(profile.productCategory());
        String model = profile.productModel().isBlank()
                ? (type == KnowledgeType.TROUBLESHOOTING ? inferProductModel(request.query()) : "")
                : profile.productModel().trim();
        QueryRewriter.RewriteResult rewrite = queryRewriter.rewrite(request.query(), type);
        return new KnowledgeQueryPlan(
                request.query(),
                type,
                category,
                model,
                profile.version(),
                rewrite.variants(),
                rewrite.terms()
        );
    }

    private KnowledgeType inferType(String query) {
        String text = normalize(query);
        if (containsAny(
                text,
                "怎么用", "如何使用", "没声音", "一边响", "只剩左边", "只剩右边", "单边", "单侧",
                "配不上", "连不上", "断连", "麦克风", "充不进", "不充电", "掉电快", "续航异常",
                "消息不来", "不来", "不同步", "发热", "烫手", "不能传", "只能充电", "电脑不识别"
        )) {
            return KnowledgeType.TROUBLESHOOTING;
        }
        return KnowledgeType.POLICY;
    }

    private String inferProductCategory(String query) {
        String text = normalize(query);
        if (containsAny(text, "耳机", "耳麦", "左右耳", "声道")) {
            return "headphones";
        }
        if (containsAny(text, "手表", "表盘", "腕表")) {
            return "watch";
        }
        if (containsAny(text, "充电器", "充电头", "快充头")) {
            return "charger";
        }
        if (containsAny(text, "数据线", "线材", "type-c线", "usb-c线")) {
            return "cable";
        }
        return "";
    }

    private String inferProductModel(String query) {
        String text = normalize(query);
        if (text.contains("pro max")) {
            return "Pro Max";
        }
        if (text.contains("s2")) {
            return "S2";
        }
        return "";
    }

    private String normalizeCategory(String value) {
        String text = normalize(value);
        return switch (text) {
            case "耳机", "headphone", "headphones" -> "headphones";
            case "手表", "watch" -> "watch";
            case "充电器", "charger" -> "charger";
            case "数据线", "cable" -> "cable";
            default -> text;
        };
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private boolean containsAny(String text, String... values) {
        for (String value : values) {
            if (text.contains(value.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
