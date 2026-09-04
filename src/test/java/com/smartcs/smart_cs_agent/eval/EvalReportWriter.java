package com.smartcs.smart_cs_agent.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 评测报告 JSON 写入工具。
 *
 * 【为什么放在 test 目录？】
 * 当前评测系统主要在测试阶段运行，生成的 JSON 报告也是测试产物，
 * 所以先放在 src/test/java 中，不影响正式业务代码。
 *
 * 【为什么写到 target/eval-reports？】
 * target 是 Maven 的构建输出目录，适合存放自动生成文件。
 * 这样不会把每次测试生成的报告误提交到源码里。
 */
public class EvalReportWriter {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private static final DateTimeFormatter HISTORY_FILE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneOffset.UTC);

    private EvalReportWriter() {
    }

    public static void write(String reportName,
                      Map<String, Object> summary,
                      List<Map<String, Object>> cases) throws IOException {
        Path outputDir = Path.of("target", "eval-reports");
        Files.createDirectories(outputDir);

        Instant generatedAt = Instant.now();
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("reportName", reportName);
        report.put("generatedAt", generatedAt.toString());
        report.put("summary", summary);
        report.put("cases", cases == null ? List.of() : cases);

        // 当前报告使用固定文件名，方便人和程序直接找到“最新结果”。
        OBJECT_MAPPER.writeValue(outputDir.resolve(reportName + ".json").toFile(), report);

        // 历史快照使用时间戳文件名，方便后续做趋势对比。
        writeHistorySnapshot(outputDir, reportName, report, generatedAt);
        writeSummaryIndex(outputDir);
        writeTrend(outputDir);
    }

    public static double rate(int passed, int total) {
        if (total == 0) {
            return 0.0;
        }
        return passed * 1.0 / total;
    }

    /**
     * 生成一个聚合入口 summary.json。
     *
     * 后续如果要做页面或 CI 报告，只读取这一个文件就能知道：
     * - 当前有哪些评测报告
     * - 每个报告的总体指标是什么
     */
    private static void writeSummaryIndex(Path outputDir) throws IOException {
        List<Map<String, Object>> reports = new ArrayList<>();

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(outputDir, "*.json")) {
            for (Path reportPath : stream) {
                String fileName = reportPath.getFileName().toString();
                if ("summary.json".equals(fileName) || "trend.json".equals(fileName)) {
                    continue;
                }

                Map<?, ?> report = OBJECT_MAPPER.readValue(reportPath.toFile(), Map.class);
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("file", fileName);
                item.put("reportName", report.get("reportName"));
                item.put("generatedAt", report.get("generatedAt"));
                item.put("summary", report.get("summary"));
                reports.add(item);
            }
        }

        reports.sort((left, right) -> String.valueOf(left.get("file")).compareTo(String.valueOf(right.get("file"))));

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("generatedAt", Instant.now().toString());
        summary.put("reportCount", reports.size());
        summary.put("reports", reports);

        OBJECT_MAPPER.writeValue(outputDir.resolve("summary.json").toFile(), summary);
    }

    /**
     * 为每一次评测保存一份历史快照。
     *
     * 固定报告文件会被下一次测试覆盖；历史快照不会覆盖，
     * 因此可以用来回答“本次修改后指标有没有退化”。
     */
    private static void writeHistorySnapshot(Path outputDir,
                                             String reportName,
                                             Map<String, Object> report,
                                             Instant generatedAt) throws IOException {
        Path reportHistoryDir = outputDir.resolve("history").resolve(reportName);
        Files.createDirectories(reportHistoryDir);

        String fileName = HISTORY_FILE_TIME_FORMATTER.format(generatedAt) + ".json";
        OBJECT_MAPPER.writeValue(reportHistoryDir.resolve(fileName).toFile(), report);
    }

    /**
     * 生成趋势入口 trend.json。
     *
     * 第一版先不画图，只输出结构化数据：
     * - latestSummary：当前最新指标
     * - previousSummary：上一次指标
     * - metricDelta：数值型指标的变化量
     *
     * 后续无论是做前端 Monitor 页面，还是 CI 里检查指标是否退化，都可以直接读取这个文件。
     */
    private static void writeTrend(Path outputDir) throws IOException {
        Path historyDir = outputDir.resolve("history");
        List<Map<String, Object>> reportTrends = new ArrayList<>();

        if (Files.isDirectory(historyDir)) {
            try (DirectoryStream<Path> reportDirs = Files.newDirectoryStream(historyDir)) {
                for (Path reportDir : reportDirs) {
                    if (Files.isDirectory(reportDir)) {
                        reportTrends.add(buildReportTrend(reportDir));
                    }
                }
            }
        }

        reportTrends.sort((left, right) ->
                String.valueOf(left.get("reportName")).compareTo(String.valueOf(right.get("reportName"))));

        Map<String, Object> trend = new LinkedHashMap<>();
        trend.put("generatedAt", Instant.now().toString());
        trend.put("historyDir", historyDir.toString());
        trend.put("reportCount", reportTrends.size());
        trend.put("reports", reportTrends);

        OBJECT_MAPPER.writeValue(outputDir.resolve("trend.json").toFile(), trend);
    }

    private static Map<String, Object> buildReportTrend(Path reportDir) throws IOException {
        List<Path> snapshots = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(reportDir, "*.json")) {
            for (Path snapshot : stream) {
                snapshots.add(snapshot);
            }
        }

        // 文件名以时间开头，倒序后第一条就是最新快照。
        snapshots.sort((left, right) ->
                right.getFileName().toString().compareTo(left.getFileName().toString()));

        List<Map<String, Object>> recentRuns = new ArrayList<>();
        for (int i = 0; i < snapshots.size() && i < 10; i++) {
            Map<?, ?> snapshot = OBJECT_MAPPER.readValue(snapshots.get(i).toFile(), Map.class);

            Map<String, Object> run = new LinkedHashMap<>();
            run.put("file", snapshots.get(i).getFileName().toString());
            run.put("generatedAt", snapshot.get("generatedAt"));
            run.put("summary", snapshot.get("summary"));
            recentRuns.add(run);
        }

        Map<String, Object> trend = new LinkedHashMap<>();
        trend.put("reportName", reportDir.getFileName().toString());
        trend.put("historyCount", snapshots.size());
        trend.put("recentRuns", recentRuns);

        if (!recentRuns.isEmpty()) {
            Map<String, Object> latest = recentRuns.get(0);
            trend.put("latestFile", latest.get("file"));
            trend.put("latestGeneratedAt", latest.get("generatedAt"));
            trend.put("latestSummary", latest.get("summary"));
        }

        if (recentRuns.size() >= 2) {
            Map<String, Object> previous = recentRuns.get(1);
            trend.put("previousFile", previous.get("file"));
            trend.put("previousGeneratedAt", previous.get("generatedAt"));
            trend.put("previousSummary", previous.get("summary"));
            trend.put("metricDelta", calculateMetricDelta(summaryOf(recentRuns.get(0)), summaryOf(previous)));
        }

        return trend;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> summaryOf(Map<String, Object> run) {
        Object summary = run.get("summary");
        if (summary instanceof Map<?, ?>) {
            return (Map<String, Object>) summary;
        }
        return Map.of();
    }

    private static Map<String, Object> calculateMetricDelta(Map<String, Object> latest,
                                                            Map<String, Object> previous) {
        Map<String, Object> delta = new LinkedHashMap<>();

        for (Map.Entry<String, Object> entry : latest.entrySet()) {
            Object latestValue = entry.getValue();
            Object previousValue = previous.get(entry.getKey());

            if (latestValue instanceof Number latestNumber && previousValue instanceof Number previousNumber) {
                delta.put(entry.getKey(), latestNumber.doubleValue() - previousNumber.doubleValue());
            }
        }

        return delta;
    }
}
