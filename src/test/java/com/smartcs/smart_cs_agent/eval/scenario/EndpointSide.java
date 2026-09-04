package com.smartcs.smart_cs_agent.eval.scenario;

/**
 * 对照实验的被测端（公平原则第 6 条：盲评匿名，报告里只允许出现 Agent A/B）。
 *
 * <p>场景 JSON 里的 workflowAssertions / baselineAssertions 只在对应端生效，
 * 公共 assertions 两端都要过。测试日志与落盘报告使用匿名标签，端点名只允许
 * 出现在测试代码内部。</p>
 */
public enum EndpointSide {
    WORKFLOW(ScenarioRunner.WORKFLOW_ENDPOINT, "Agent A"),
    BASELINE(ScenarioRunner.BASELINE_ENDPOINT, "Agent B");

    private final String endpoint;
    private final String blindLabel;

    EndpointSide(String endpoint, String blindLabel) {
        this.endpoint = endpoint;
        this.blindLabel = blindLabel;
    }

    public String getEndpoint() {
        return endpoint;
    }

    /** 盲评与报告使用的匿名标签；同一份报告里 A/B 与端的映射由测试持有，不外泄。 */
    public String blindLabel() {
        return blindLabel;
    }

    public static EndpointSide ofEndpoint(String chatEndpoint) {
        for (EndpointSide side : values()) {
            if (side.endpoint.equals(chatEndpoint)) {
                return side;
            }
        }
        throw new IllegalArgumentException("未知的被测端点：" + chatEndpoint);
    }
}
