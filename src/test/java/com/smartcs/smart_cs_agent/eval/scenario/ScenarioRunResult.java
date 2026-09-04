package com.smartcs.smart_cs_agent.eval.scenario;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 单个场景一次执行的结果，负责把自己转换成报告行。
 *
 * <p>报告行包含每轮的回复预览、outcome、errorCode、触发过的 capability 和违规明细，
 * 供 M4 的对比报告直接引用（被测方匿名为 Agent A/B 由报告层处理，这里只保留事实）。</p>
 */
class ScenarioRunResult {

    private final Scenario scenario;
    private final int repeat;
    private final List<TurnExecutionRecord> records;
    private final List<String> finalViolations;
    private final boolean passed;

    ScenarioRunResult(Scenario scenario,
                      int repeat,
                      List<TurnExecutionRecord> records,
                      List<String> finalViolations,
                      boolean passed) {
        this.scenario = scenario;
        this.repeat = repeat;
        this.records = List.copyOf(records);
        this.finalViolations = List.copyOf(finalViolations);
        this.passed = passed;
    }

    Scenario scenario() {
        return scenario;
    }

    int repeatOf() {
        return repeat;
    }

    boolean passed() {
        return passed;
    }

    /** 本场景每次执行的轮次记录；盲评 Judge 需要读取对话与 Trace 事实。 */
    List<TurnExecutionRecord> records() {
        return records;
    }

    List<String> allViolations() {
        List<String> all = new ArrayList<>();
        records.forEach(record -> record.violations()
                .forEach(violation -> all.add("turn " + record.index() + ": " + violation)));
        finalViolations.forEach(violation -> all.add("final: " + violation));
        return all;
    }

    Map<String, Object> toReportRow() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", scenario.getId());
        row.put("category", scenario.category);
        row.put("repeat", repeat);
        row.put("persona", scenario.persona == null ? null : scenario.persona.getUserId());
        row.put("passed", passed);
        row.put("violations", allViolations());

        List<Map<String, Object>> turnRows = new ArrayList<>();
        for (TurnExecutionRecord record : records) {
            Map<String, Object> turnRow = new LinkedHashMap<>();
            turnRow.put("index", record.index());
            turnRow.put("message", record.message());
            turnRow.put("httpStatus", record.httpStatus());
            turnRow.put("outcome", record.outcome());
            turnRow.put("errorCode", record.errorCode());
            turnRow.put("triggeredCapabilities", record.triggeredCapabilities());
            turnRow.put("llmFailed", record.llmFailed());
            turnRow.put("replyPreview", preview(record.reply(), 200));
            turnRow.put("violations", record.violations());
            turnRows.add(turnRow);
        }
        row.put("turns", turnRows);
        return row;
    }

    private String preview(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        return text.length() <= maxLength ? text : text.substring(0, maxLength) + "…";
    }
}
