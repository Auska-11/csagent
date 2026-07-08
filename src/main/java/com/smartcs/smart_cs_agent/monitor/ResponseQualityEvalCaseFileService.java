package com.smartcs.smart_cs_agent.monitor;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 回复质量评测文件的读写服务。
 *
 * 【为什么单独做一个 Service？】
 * MonitorService 负责“从线上反馈沉淀候选样本”，
 * 这个类负责“把人工确认后的样本写入正式评测文件”。
 * 两个职责分开后，后续如果不想直接写本地文件，而是改成创建 Pull Request、
 * 写数据库、或者交给 CI 生成评测集，都只需要替换这一层。
 */
@Service
public class ResponseQualityEvalCaseFileService {

    private static final TypeReference<List<ResponseQualityEvalCase>> CASE_LIST_TYPE = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;
    private final Path evalCaseFile;

    @Autowired
    public ResponseQualityEvalCaseFileService(ObjectMapper objectMapper,
                                              @Value("${smartcs.monitor.response-quality-eval-file:src/test/resources/eval/response_quality_eval_cases.json}")
                                              String evalCaseFile) {
        this(objectMapper, Path.of(evalCaseFile));
    }

    ResponseQualityEvalCaseFileService(ObjectMapper objectMapper, Path evalCaseFile) {
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
        this.evalCaseFile = evalCaseFile;
    }

    /**
     * 把人工确认后的 case 合并进正式回复质量评测文件。
     *
     * 合并策略：
     * - 文件不存在时先按空列表处理；
     * - 已存在相同 id 时跳过，避免重复写入；
     * - 新 case 追加到文件末尾，保留原有 case 顺序；
     * - 写入前做基础校验，避免把没有断言的负例写进评测集。
     */
    public synchronized EvalCaseMergeResult merge(List<ResponseQualityEvalCase> cases) {
        List<ResponseQualityEvalCase> existingCases = loadCases();
        int beforeCount = existingCases.size();

        Map<String, ResponseQualityEvalCase> caseById = new LinkedHashMap<>();
        for (ResponseQualityEvalCase existingCase : existingCases) {
            validateCase(existingCase);
            caseById.put(existingCase.getId(), existingCase);
        }

        List<String> skippedDuplicateIds = new ArrayList<>();
        int mergedCount = 0;
        for (ResponseQualityEvalCase newCase : cases == null ? List.<ResponseQualityEvalCase>of() : cases) {
            validateCase(newCase);
            if (caseById.containsKey(newCase.getId())) {
                skippedDuplicateIds.add(newCase.getId());
                continue;
            }
            caseById.put(newCase.getId(), newCase);
            mergedCount++;
        }

        List<ResponseQualityEvalCase> mergedCases = new ArrayList<>(caseById.values());
        saveCases(mergedCases);

        return new EvalCaseMergeResult(
                evalCaseFile.toString(),
                beforeCount,
                mergedCases.size(),
                mergedCount,
                skippedDuplicateIds
        );
    }

    private List<ResponseQualityEvalCase> loadCases() {
        if (evalCaseFile == null || !Files.exists(evalCaseFile)) {
            return new ArrayList<>();
        }

        try {
            return objectMapper.readValue(evalCaseFile.toFile(), CASE_LIST_TYPE);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read response quality eval cases from " + evalCaseFile, e);
        }
    }

    private void saveCases(List<ResponseQualityEvalCase> cases) {
        try {
            Path parent = evalCaseFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(evalCaseFile.toFile(), cases);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write response quality eval cases to " + evalCaseFile, e);
        }
    }

    private void validateCase(ResponseQualityEvalCase evalCase) {
        if (evalCase == null) {
            throw new IllegalArgumentException("Eval case cannot be null");
        }
        if (evalCase.getId() == null || evalCase.getId().isBlank()) {
            throw new IllegalArgumentException("Eval case id cannot be empty");
        }
        if (evalCase.getCandidateReply() == null || evalCase.getCandidateReply().isBlank()) {
            throw new IllegalArgumentException("Eval case candidateReply cannot be empty");
        }

        boolean hasRequiredKeywords = !evalCase.getRequiredKeywords().isEmpty();
        boolean hasForbiddenKeywords = !evalCase.getForbiddenKeywords().isEmpty();
        if (evalCase.isExpectedPass() && !hasRequiredKeywords) {
            throw new IllegalArgumentException("Positive eval case must provide requiredKeywords: " + evalCase.getId());
        }
        if (!evalCase.isExpectedPass() && !hasRequiredKeywords && !hasForbiddenKeywords) {
            throw new IllegalArgumentException("Negative eval case must provide requiredKeywords or forbiddenKeywords: " + evalCase.getId());
        }
    }
}
