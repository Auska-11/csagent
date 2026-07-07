package com.smartcs.smart_cs_agent.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 评测报告只读接口。
 *
 * 【为什么要把评测报告暴露给前端？】
 * Agent 项目不能只证明“能回复”，还要证明“回复质量可以被验证”。
 * 测试阶段已经会生成 target/eval-reports/summary.json 和 trend.json，
 * 这个 Controller 的作用就是把这些构建产物展示给前端调试工作台。
 *
 * 注意：
 * - 这里只读固定目录下的固定文件，不接收前端传入的任意文件路径。
 * - 如果报告还没生成，接口返回 available=false，而不是抛异常。
 *   这样新同学第一次启动项目时，前端不会因为没有跑过测试而崩掉。
 */
@RestController
@RequestMapping("/api/eval")
public class EvalReportController {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;
    private final Path reportDir;

    public EvalReportController() {
        this(Path.of("target", "eval-reports"), new ObjectMapper());
    }

    EvalReportController(Path reportDir) {
        this(reportDir, new ObjectMapper());
    }

    EvalReportController(Path reportDir, ObjectMapper objectMapper) {
        this.reportDir = reportDir;
        this.objectMapper = objectMapper;
    }

    /**
     * 返回当前最新评测概览和趋势数据。
     *
     * 返回结构里保留 summary/trend 原始 JSON，是为了让前端可以灵活展示；
     * 同时额外加 available/reportDir，方便页面判断“有没有报告”。
     */
    @GetMapping("/reports")
    public Map<String, Object> getReports() {
        Map<String, Object> summary = readJson("summary.json");
        Map<String, Object> trend = readJson("trend.json");
        boolean available = !summary.isEmpty();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("available", available);
        response.put("reportDir", reportDir.toString());
        response.put("summary", available ? summary : emptySummary());
        response.put("trend", trend.isEmpty() ? emptyTrend() : trend);
        return response;
    }

    private Map<String, Object> readJson(String fileName) {
        Path reportPath = reportDir.resolve(fileName);
        if (!Files.isRegularFile(reportPath)) {
            return Map.of();
        }

        try {
            return objectMapper.readValue(reportPath.toFile(), MAP_TYPE);
        } catch (IOException exception) {
            // 读报告失败时也不让调试页面崩溃；前端会看到 available=false。
            return Map.of();
        }
    }

    private Map<String, Object> emptySummary() {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("generatedAt", null);
        summary.put("reportCount", 0);
        summary.put("reports", List.of());
        return summary;
    }

    private Map<String, Object> emptyTrend() {
        Map<String, Object> trend = new LinkedHashMap<>();
        trend.put("generatedAt", null);
        trend.put("reportCount", 0);
        trend.put("reports", List.of());
        return trend;
    }
}
