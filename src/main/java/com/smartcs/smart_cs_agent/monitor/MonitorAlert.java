package com.smartcs.smart_cs_agent.monitor;

/**
 * Monitor 面板上的一条风险告警。
 *
 * 【为什么单独建 DTO？】
 * MonitorSummary 里已经有很多原始指标，例如工具成功率、LLM 失败率、反馈数量。
 * 但面试项目不能只停留在“展示数据”，还要能说明“发现了什么问题、建议怎么处理”。
 * 所以这里把指标进一步整理成结构化告警，前端和后续接口都可以直接复用。
 */
public class MonitorAlert {

    /** 告警级别，用来决定前端展示颜色和处理优先级 */
    private final MonitorAlertSeverity severity;

    /** 稳定的告警编码，方便测试、日志搜索和后续扩展国际化文案 */
    private final String code;

    /** 一句话标题，适合在前端列表里快速浏览 */
    private final String title;

    /** 告警原因，解释这个告警为什么出现 */
    private final String message;

    /** 处理建议，帮助开发者知道下一步该排查哪里 */
    private final String suggestion;

    public MonitorAlert(MonitorAlertSeverity severity,
                        String code,
                        String title,
                        String message,
                        String suggestion) {
        this.severity = severity == null ? MonitorAlertSeverity.INFO : severity;
        this.code = code == null ? "" : code;
        this.title = title == null ? "" : title;
        this.message = message == null ? "" : message;
        this.suggestion = suggestion == null ? "" : suggestion;
    }

    public MonitorAlertSeverity getSeverity() {
        return severity;
    }

    public String getCode() {
        return code;
    }

    public String getTitle() {
        return title;
    }

    public String getMessage() {
        return message;
    }

    public String getSuggestion() {
        return suggestion;
    }
}
