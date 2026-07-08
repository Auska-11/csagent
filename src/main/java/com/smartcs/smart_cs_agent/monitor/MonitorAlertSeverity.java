package com.smartcs.smart_cs_agent.monitor;

/**
 * Monitor 告警级别。
 *
 * 第一版只保留三个级别，避免一开始就把告警系统设计得过重：
 * - INFO：提示信息，通常不需要立刻处理；
 * - WARN：需要关注，可能影响用户体验；
 * - CRITICAL：高风险问题，建议优先处理。
 */
public enum MonitorAlertSeverity {

    INFO,
    WARN,
    CRITICAL
}
