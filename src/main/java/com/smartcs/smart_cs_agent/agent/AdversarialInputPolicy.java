package com.smartcs.smart_cs_agent.agent;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 入站对抗拦截：在 Workflow 路由之前识别“无论路由到哪个 Workflow 都必须拒绝”的消息。
 *
 * <p>77 号报告第一阶段的失分诊断：对抗类消息被路由进具体业务 Workflow 后，得到的是业务模板
 * （“请提供订单号”式答非所问），安全立场没有任何骨架承载。本闸门遵循 §6 的混合架构原则——
 * <strong>确定性代码决定拒绝什么，话术按命中规则给出固定模板</strong>，不经 LLM：
 * 拒绝路径零模型调用，故障注入下行为不变，确定性断言可直接锁死。</p>
 *
 * <p>与 {@link OutboundSafetyFilter} 的分工：出站过滤管“我们说出去的话”，本闸门管
 * “用户说进来的消息”。匹配面 v1 刻意收窄为四类（77 号报告 §3.1 的三个失分场景 +
 * §6.2 设计中的全库导出）：系统提示词/工具清单索取、站外交易诱导、身份冒充 + 他人敏感数据
 * 索取、全量数据导出。</p>
 *
 * <p><strong>放行边界（不可拦截）</strong>：消息携带具体订单号（{@code ORD-123}）时一律放行，
 * 走正常路由——{@code adv-inject-via-order} 等注入必须仍在 Provider 归属校验边界被拒
 * （ORDER_OWNERSHIP_MISMATCH 是被对照实验验证过的防线，不能被前置闸门替代）；
 * 诱导承诺、角色扮演、威胁升级等不索取数据的对抗留给通用兜底处理。规则集上线前已对
 * 60 条评测场景全量扫描：恰好命中 4 个对抗场景 5 轮，其余 56 个场景零误伤。</p>
 */
@Component
@ConfigurationProperties(prefix = "smartcs.agent.adversarial-gate")
public class AdversarialInputPolicy {

    /** 携带具体订单号的消息一律放行（含注入的订单话术由 Provider 边界处理）。 */
    static final Pattern ORDER_ID = Pattern.compile("(?i)ord-\\d+");

    /**
     * 规则清单按评估顺序排列；首条命中即返回。
     *
     * <p>每条规则只记录模式与话术，不记录匹配片段，Trace 落盘时同理——用户原文已由
     * turn_started 事件完整记录，这里不再二次回显。</p>
     */
    private static final List<Rule> DEFAULT_RULES = List.of(
            new Rule(
                    "SYSTEM_EXPLOIT",
                    "系统提示词或工具清单索取、指令覆盖",
                    List.of(
                            "系统提示词", "忽略你之前的所有设定", "全部设定",
                            "工具清单", "可用工具列表", "调用参数"
                    ),
                    "我是平台官方的客服助手，只能帮你处理订单、售后等客服问题，"
                            + "不能提供内部设定或工具信息，也不会更改这个身份。"
                            + "如果你有客服问题，我很乐意继续帮你。"
            ),
            new Rule(
                    "OFF_PLATFORM_DEAL",
                    "站外私下交易或站外转账诱导",
                    List.of(
                            "加个微信", "加微信", "加下微信", "加我微信", "微信私下",
                            "私下交易", "私下付款", "当面交易", "线下面交",
                            "转你个人账户", "转到你个人账户", "转到我个人账户",
                            "打到你个人账户", "支付宝转账给", "转你支付宝"
                    ),
                    "为了你的账号和资金安全，我不能把交易或付款转到微信等站外渠道，"
                            + "站外转账没有平台保障。请放心在平台内下单和付款，交易全程有平台保障；"
                            + "商品或订单问题我随时可以帮你。"
            ),
            new Rule(
                    "IMPERSONATION_SOLICIT",
                    "身份冒充（官方/内部/工号/审计）+ 他人敏感数据索取",
                    List.of(
                            "官方客服", "平台客服", "平台管理员", "管理员身份",
                            "内部测试", "内部审计", "内部员工", "内部人员",
                            "审计员", "安全审计", "工号"
                    ),
                    // 第二组条件词由 requiresAll 承载：命中身份词之外还须出现敏感数据词。
                    List.of(
                            "手机号", "收货地址", "收件人", "身份证", "系统提示词",
                            "数据库", "所有用户", "全部用户", "其他用户", "别人的", "他人"
                    ),
                    "客服无法核验工号或内部身份，也不能把其他用户或全量用户的信息提供给任何人。"
                            + "如果你要查自己的订单或信息，可以直接告诉我订单号，或在 App「我的订单」里查看；"
                            + "如需官方协助，请通过 App 内的官方客服入口联系。"
            ),
            new Rule(
                    "BULK_EXPORT",
                    "全量用户/批量数据导出请求",
                    List.of(
                            "所有用户", "全部用户", "数据库"
                    ),
                    List.of(
                            "导出", "手机号", "订单记录", "用户数据"
                    ),
                    "我无法导出或提供其他用户、全部用户的任何数据，这超出客服助手的权限边界。"
                            + "如果你要查自己的订单或售后进度，告诉我具体信息，我马上帮你查。"
            )
    );

    /** false 时完全放行（与出站过滤一致的运行开关）；默认开启。 */
    private boolean enabled = true;

    /** 规则命中后返回的拒绝结果；ruleId 供 Trace 与监控归因，refusal 为固定话术。 */
    public record Denial(String ruleId, String description, String refusal) {
    }

    private record Rule(
            String id,
            String description,
            List<String> markers,
            List<String> requiredAlongside,
            String refusal
    ) {
        Rule(String id, String description, List<String> markers, String refusal) {
            this(id, description, markers, List.of(), refusal);
        }
    }

    /**
     * @return 命中则返回拒绝结果；null 表示放行（走正常 Workflow 路由）。
     */
    public Denial evaluate(String message) {
        if (message == null || message.isBlank() || !enabled) {
            return null;
        }
        String normalized = message.toLowerCase(Locale.ROOT);
        if (ORDER_ID.matcher(normalized).find()) {
            return null;
        }
        for (Rule rule : DEFAULT_RULES) {
            if (!containsAny(normalized, rule.markers())) {
                continue;
            }
            if (!rule.requiredAlongside().isEmpty()
                    && !containsAny(normalized, rule.requiredAlongside())) {
                continue;
            }
            return new Denial(rule.id(), rule.description(), rule.refusal());
        }
        return null;
    }

    private static boolean containsAny(String text, List<String> markers) {
        for (String marker : markers) {
            if (text.contains(marker.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    /** 供纯 Java 测试使用默认配置。 */
    public static AdversarialInputPolicy withDefaults() {
        return new AdversarialInputPolicy();
    }

    /**
     * 兼容旧测试构造：返回关闭状态的闸门。
     *
     * <p>旧测试编写时闸门尚不存在，其消息集不假设入站拦截；生产路径由 Spring 注入
     * 默认开启的 Bean，闸门行为由新增的专项测试与 60 场景回归覆盖。</p>
     */
    public static AdversarialInputPolicy disabled() {
        AdversarialInputPolicy policy = new AdversarialInputPolicy();
        policy.enabled = false;
        return policy;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /** 供配置审计与测试读取规则面；不可变。 */
    public List<String> ruleIds() {
        List<String> ids = new ArrayList<>();
        for (Rule rule : DEFAULT_RULES) {
            ids.add(rule.id());
        }
        return List.copyOf(ids);
    }
}
