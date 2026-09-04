package com.smartcs.smart_cs_agent.agent;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 出站安全过滤：最终回复落盘前拦截“引导买家去站外联系/交易”的话术。
 *
 * <p>移植自上游闲鱼客服项目的 {@code _safe_filter}，但有一个刻意偏差：上游用裸关键词
 * （“微信”、“QQ”……）命中即整条替换，而本系统知识库存在合法提及（例如手表排查文档的
 * “微信不提醒”）。因此这里改成<strong>联系方式交换意图</strong>的正则（“加微信”、“QQ号”、
 * “支付宝转账”、“线下面交”……），只拦“交换联系方式 / 引导站外交易”的行为信号。
 * 偏差原因记录在 sidecar/xianyu/PROVENANCE.md。</p>
 *
 * <p>命中即整条回复替换为安全提示（与上游一致），不做局部打码：半句话术被截断后语义不可控，
 * 统一话术还能顺带完成一次安全提醒。过滤发生在 AgentRuntime 收尾、写入会话记忆之前，
 * 保证记忆、回复与 Trace 三者一致。</p>
 */
@Component
@ConfigurationProperties(prefix = "smartcs.agent.outbound-safety")
public class OutboundSafetyFilter {

    static final String DEFAULT_REPLACEMENT =
            "为保障您的账号与资金安全，请通过平台内沟通与交易，我无法提供站外联系方式。";

    /**
     * 联系方式交换意图模式（大小写不敏感）。
     *
     * <p>每条都要求“动作 + 渠道”或“渠道 + 账号语义”同时出现，避免误杀排查话术中
     * 单独出现的“微信”“QQ”等平台名。</p>
     */
    private static final List<String> DEFAULT_BLOCKED_PATTERNS = List.of(
            "加.{0,4}微信",
            "微信号",
            "加.{0,4}(vx|weixin|wechat)",
            "(vx|weixin|wechat)\\s*(号|:|：)",
            "加.{0,4}[qQ]{2}",
            "[qQ]{2}\\s*(号|:|：)",
            "扣扣",
            "支付宝.{0,4}(转账|转我|打款|账号|收款码)",
            "转.{0,4}支付宝",
            "银行卡\\s*(号|转账|打款|汇款|:|：)",
            "(线下|当面|私下).{0,4}(交易|面交|交收|付款|打款)"
    );

    /** false 时完全放行（例如内部演示租户自行承担风险）；默认开启。 */
    private boolean enabled = true;

    /** 命中即整条替换的正则清单；配置后会整体覆盖默认清单。 */
    private List<String> blockedPatterns = new ArrayList<>(DEFAULT_BLOCKED_PATTERNS);

    /** 命中后的替换话术；配置为空白时回退默认话术。 */
    private String replacement = DEFAULT_REPLACEMENT;

    /** 编译产物在 @PostConstruct 或首次使用时生成；非法正则必须在启动期失败。 */
    private volatile List<Pattern> compiledPatterns;

    @PostConstruct
    void compilePatterns() {
        this.compiledPatterns = compile(blockedPatterns);
    }

    /**
     * @return 过滤结果；filtered=false 表示未改动，调用方可据此省略 Trace。
     */
    public Outcome apply(String text) {
        if (text == null || text.isBlank() || !enabled) {
            return new Outcome(text == null ? "" : text, false, List.of());
        }
        List<Pattern> patterns = currentPatterns();
        Set<String> matched = new LinkedHashSet<>();
        for (Pattern pattern : patterns) {
            Matcher matcher = pattern.matcher(text);
            if (matcher.find()) {
                matched.add(pattern.pattern());
            }
        }
        if (matched.isEmpty()) {
            return new Outcome(text, false, List.of());
        }
        String safeReplacement = (replacement == null || replacement.isBlank())
                ? DEFAULT_REPLACEMENT
                : replacement;
        return new Outcome(safeReplacement, true, List.copyOf(matched));
    }

    private List<Pattern> currentPatterns() {
        List<Pattern> patterns = this.compiledPatterns;
        if (patterns == null) {
            // 直接 new（无 Spring 生命周期）时的兜底；@PostConstruct 已覆盖生产路径。
            patterns = compile(blockedPatterns);
            this.compiledPatterns = patterns;
        }
        return patterns;
    }

    private static List<Pattern> compile(List<String> patterns) {
        List<Pattern> compiled = new ArrayList<>();
        for (String raw : patterns) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            compiled.add(Pattern.compile(raw, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE));
        }
        return List.copyOf(compiled);
    }

    /** 供纯 Java 测试使用默认配置。 */
    public static OutboundSafetyFilter withDefaults() {
        OutboundSafetyFilter filter = new OutboundSafetyFilter();
        filter.compilePatterns();
        return filter;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public List<String> getBlockedPatterns() {
        return blockedPatterns;
    }

    public void setBlockedPatterns(List<String> blockedPatterns) {
        this.blockedPatterns = blockedPatterns == null
                ? new ArrayList<>()
                : new ArrayList<>(blockedPatterns);
        // 编译缓存跟随清单失效，下次 apply 时按新清单重编（Spring 路径仍由 @PostConstruct 兜底编译）。
        this.compiledPatterns = null;
    }

    public String getReplacement() {
        return replacement;
    }

    public void setReplacement(String replacement) {
        this.replacement = replacement;
    }

    /**
     * 过滤结果。
     *
     * @param text           最终应下发的文本
     * @param filtered       是否发生了整条替换
     * @param matchedPatterns 命中的模式原文（不含匹配片段，避免把联系方式写进 Trace）
     */
    public record Outcome(String text, boolean filtered, List<String> matchedPatterns) {
    }
}
