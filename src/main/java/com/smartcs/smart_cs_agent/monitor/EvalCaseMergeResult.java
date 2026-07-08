package com.smartcs.smart_cs_agent.monitor;

import java.util.List;

/**
 * 合并 Eval case 后返回给前端的结果。
 *
 * 这个结果刻意包含 before/after 数量和重复 ID，
 * 方便面试演示时说明“不是简单覆盖文件，而是做了去重和可观测反馈”。
 */
public class EvalCaseMergeResult {

    private final String filePath;
    private final int beforeCount;
    private final int afterCount;
    private final int mergedCount;
    private final List<String> skippedDuplicateIds;

    public EvalCaseMergeResult(String filePath,
                               int beforeCount,
                               int afterCount,
                               int mergedCount,
                               List<String> skippedDuplicateIds) {
        this.filePath = filePath;
        this.beforeCount = beforeCount;
        this.afterCount = afterCount;
        this.mergedCount = mergedCount;
        this.skippedDuplicateIds = skippedDuplicateIds == null ? List.of() : List.copyOf(skippedDuplicateIds);
    }

    public String getFilePath() {
        return filePath;
    }

    public int getBeforeCount() {
        return beforeCount;
    }

    public int getAfterCount() {
        return afterCount;
    }

    public int getMergedCount() {
        return mergedCount;
    }

    public List<String> getSkippedDuplicateIds() {
        return skippedDuplicateIds;
    }
}
