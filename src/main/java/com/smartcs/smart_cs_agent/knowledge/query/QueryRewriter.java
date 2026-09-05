package com.smartcs.smart_cs_agent.knowledge.query;

import com.smartcs.smart_cs_agent.knowledge.model.KnowledgeType;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 可解释的客服领域规则改写，不调用 LLM。 */
public class QueryRewriter {

    private static final List<String> DOMAIN_TERMS = List.of(
            "退货", "退款", "换货", "七天", "七天无理由", "超过七天", "质量问题", "运费", "售后",
            "保修", "质保", "维修", "售后检测", "发票", "电子发票", "专用发票", "抬头", "税号",
            "配送", "物流", "发货", "偏远地区", "签收", "时效", "人工客服", "转人工", "投诉",
            "耳机", "单侧无声", "左边", "右边", "配对", "蓝牙", "断连", "麦克风", "杂音",
            "手表", "充电", "续航", "耗电", "消息", "通知", "同步",
            "充电器", "快充", "协议", "功率", "兼容", "发热", "烫",
            "数据线", "接口", "传输", "识别", "只能充电", "type-c", "usb-c"
    );

    public RewriteResult rewrite(String query, KnowledgeType type) {
        String normalized = normalize(query);
        Set<String> variants = new LinkedHashSet<>();
        variants.add(normalized);

        if (type == KnowledgeType.POLICY) {
            rewritePolicy(normalized, variants);
        } else {
            rewriteTroubleshooting(normalized, variants);
        }

        Set<String> terms = new LinkedHashSet<>();
        for (String variant : variants) {
            for (String part : variant.split("[\\s,，。？?！!、；;：:]+")) {
                if (part.length() >= 2) {
                    terms.add(part);
                }
            }
        }
        for (String domainTerm : DOMAIN_TERMS) {
            String normalizedTerm = normalize(domainTerm);
            if (variants.stream().anyMatch(variant -> variant.contains(normalizedTerm))) {
                terms.add(normalizedTerm);
            }
        }
        return new RewriteResult(List.copyOf(variants), new ArrayList<>(terms));
    }

    private void rewritePolicy(String query, Set<String> variants) {
        if (containsAny(query, "坏了能修", "故障能修", "保修", "质保", "维修多久")) {
            variants.add("保修 质保 维修 售后检测 质量问题 故障");
            if (containsAny(query, "多久", "期限", "多长", "几年")) {
                variants.add("保修期限");
            }
        }
        if (containsAny(query, "票", "开票", "发票", "抬头", "税号", "专票")) {
            variants.add("发票 电子发票 普通发票 专用发票 增值税专用发票 抬头 税号");
            if (containsAny(query, "什么时候", "多久", "几天", "时间")) {
                variants.add("开票时间");
            }
        }
        if (containsAny(query, "送到", "多久到", "几天到", "配送", "快递", "物流", "发货", "签收")) {
            variants.add("配送 物流 发货 时效 签收 偏远地区 配送限制");
            if (containsAny(query, "偏远", "范围", "能送", "可以配送")) {
                variants.add("配送范围");
            }
        }
        // 只用多字词触发：单字"退"/"换"会让"以旧换新""积分兑换"等非政策话术也追加退货变体，
        // 是幻觉类场景"必然召回无关政策文档"的第一层根因（客服Agent-77 §3.3 / 76 §6.2）。
        if (containsAny(query, "退货", "退款", "换货", "想退", "能退", "可以退", "申请退",
                "退回去", "退了", "七天", "质量问题", "运费")) {
            variants.add("退货 退款 换货 七天无理由 超过七天 质量问题 运费 售后检测");
        }
        if (containsAny(query, "人工", "真人", "投诉", "赔偿", "维权")) {
            variants.add("人工客服 转人工 投诉 赔偿 维权 人工转接规则");
        }
    }

    private void rewriteTroubleshooting(String query, Set<String> variants) {
        if (containsAny(query, "只剩左边", "只剩右边", "一边响", "单边", "单侧", "没声音")) {
            variants.add("耳机 单侧无声 单侧声道无输出 左右耳重新配对 重置蓝牙连接");
        }
        if (containsAny(query, "连不上", "配不上", "搜不到", "无法配对")) {
            variants.add("耳机 无法配对 清除配对记录 进入配对模式");
        }
        if (containsAny(query, "断断续续", "老是断", "断连", "卡顿")) {
            variants.add("耳机 蓝牙断连 连接不稳定 无线干扰");
        }
        if (containsAny(query, "麦没声", "听不到我", "麦克风", "通话声音小")) {
            variants.add("耳机 麦克风异常 通话输入 权限");
        }
        if (containsAny(query, "手表充不进", "手表不充电", "无法充电")) {
            variants.add("手表 无法充电 充电触点 磁吸底座");
        }
        if (containsAny(query, "掉电快", "不耐用", "续航", "耗电")) {
            variants.add("手表 续航异常 后台耗电 屏幕常亮");
        }
        if (containsAny(query, "消息不来", "收不到通知", "不同步")) {
            variants.add("手表 消息不同步 通知权限 后台运行 蓝牙连接");
        }
        if (containsAny(query, "快充", "协议", "多少瓦", "功率")) {
            variants.add("充电器 快充协议 USB PD PPS QC 功率兼容");
        }
        if (containsAny(query, "发热", "很烫", "烫手", "异常热")) {
            variants.add("充电器 异常发热 停止使用 断开电源");
        }
        if (containsAny(query, "只能充电", "不能传文件", "不能传输", "电脑不识别")) {
            variants.add("数据线 只能充电不能传输 数据通道 USB 数据传输");
        }
        if (containsAny(query, "充不进", "不充电", "接触不良")) {
            variants.add("数据线 无法充电 接口兼容 接头异物 线材损坏");
        }
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT)
                .replace("７", "7")
                .replace("七日", "七天")
                .replace("7 天", "七天")
                .replace("7天", "七天")
                .trim();
    }

    private boolean containsAny(String text, String... values) {
        for (String value : values) {
            if (text.contains(value.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    public record RewriteResult(List<String> variants, List<String> terms) {
    }
}
