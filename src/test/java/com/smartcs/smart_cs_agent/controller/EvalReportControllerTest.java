package com.smartcs.smart_cs_agent.controller;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 评测报告接口测试。
 *
 * 这里使用 JUnit 的临时目录，而不是直接依赖 target/eval-reports。
 * 这样测试不会被本机是否跑过评测影响，结果更稳定。
 */
class EvalReportControllerTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldReturnSummaryAndTrendWhenReportsExist() throws Exception {
        Files.writeString(tempDir.resolve("summary.json"), """
                {
                  "generatedAt": "2026-07-06T16:09:11Z",
                  "reportCount": 1,
                  "reports": [
                    {
                      "reportName": "customer-service-routing",
                      "summary": {
                        "total": 16,
                        "intentAccuracy": 1.0
                      }
                    }
                  ]
                }
                """);
        Files.writeString(tempDir.resolve("trend.json"), """
                {
                  "generatedAt": "2026-07-06T16:09:12Z",
                  "reportCount": 1,
                  "reports": [
                    {
                      "reportName": "customer-service-routing",
                      "historyCount": 3
                    }
                  ]
                }
                """);

        EvalReportController controller = new EvalReportController(tempDir);

        Map<String, Object> response = controller.getReports();

        assertEquals(true, response.get("available"));
        assertEquals(tempDir.toString(), response.get("reportDir"));

        Map<?, ?> summary = assertInstanceOf(Map.class, response.get("summary"));
        assertEquals(1, summary.get("reportCount"));

        List<?> reports = assertInstanceOf(List.class, summary.get("reports"));
        assertEquals(1, reports.size());

        Map<?, ?> firstReport = assertInstanceOf(Map.class, reports.get(0));
        assertEquals("customer-service-routing", firstReport.get("reportName"));

        Map<?, ?> trend = assertInstanceOf(Map.class, response.get("trend"));
        assertEquals(1, trend.get("reportCount"));
    }

    @Test
    void shouldReturnEmptyResultWhenReportsDoNotExist() {
        EvalReportController controller = new EvalReportController(tempDir);

        Map<String, Object> response = controller.getReports();

        assertEquals(false, response.get("available"));

        Map<?, ?> summary = assertInstanceOf(Map.class, response.get("summary"));
        assertEquals(0, summary.get("reportCount"));
        assertTrue(assertInstanceOf(List.class, summary.get("reports")).isEmpty());

        Map<?, ?> trend = assertInstanceOf(Map.class, response.get("trend"));
        assertEquals(0, trend.get("reportCount"));
        assertFalse(response.get("reportDir").toString().isBlank());
    }
}
