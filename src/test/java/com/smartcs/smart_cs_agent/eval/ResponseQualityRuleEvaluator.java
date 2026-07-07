package com.smartcs.smart_cs_agent.eval;

import java.util.List;

/**
 * 回复质量规则评测器。
 *
 * 【为什么放在 test 目录？】
 * 当前它主要服务离线评测，不是线上业务逻辑。
 * 等后续需要在接口里实时拦截风险回复时，再考虑迁移到 main 目录。
 */
class ResponseQualityRuleEvaluator {

    private ResponseQualityRuleEvaluator() {
    }

    static Result evaluate(String reply, List<String> requiredKeywords, List<String> forbiddenKeywords) {
        String actualReply = reply == null ? "" : reply;

        List<String> missingRequiredKeywords = requiredKeywords == null
                ? List.of()
                : requiredKeywords.stream()
                .filter(keyword -> !actualReply.contains(keyword))
                .toList();

        List<String> hitForbiddenKeywords = forbiddenKeywords == null
                ? List.of()
                : forbiddenKeywords.stream()
                .filter(actualReply::contains)
                .toList();

        return new Result(missingRequiredKeywords, hitForbiddenKeywords);
    }

    record Result(List<String> missingRequiredKeywords,
                  List<String> hitForbiddenKeywords) {

        boolean passed() {
            return missingRequiredKeywords.isEmpty() && hitForbiddenKeywords.isEmpty();
        }
    }
}
