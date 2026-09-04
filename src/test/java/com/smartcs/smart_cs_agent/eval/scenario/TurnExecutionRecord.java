package com.smartcs.smart_cs_agent.eval.scenario;

import com.smartcs.smart_cs_agent.agent.context.TraceEvent;

import java.util.List;

/**
 * 一轮真实执行的结果快照，断言引擎与报告落盘的唯一数据来源。
 *
 * @param index                 轮次序号，从 1 开始
 * @param message               用户本轮消息原文
 * @param httpStatus            /api/chat/send 的 HTTP 状态码
 * @param reply                 Agent 最终回复；非 200 时为空串
 * @param trace                 本轮 Trace 事件（从 HTTP 响应反序列化）
 * @param outcome               生产 Monitor 分类的 TurnOutcome 名称
 * @param errorCode             生产 Monitor 分类的稳定错误码
 * @param triggeredCapabilities 本轮 tool_called 事件中出现的 capability 集合
 * @param llmFailed             本轮是否发生 llm_failed 事件
 * @param violations            本轮断言违规描述
 */
record TurnExecutionRecord(
        int index,
        String message,
        int httpStatus,
        String reply,
        List<TraceEvent> trace,
        String outcome,
        String errorCode,
        List<String> triggeredCapabilities,
        boolean llmFailed,
        List<String> violations) {
}
