package com.smartcs.smart_cs_agent.controller;

import java.util.List;

/**
 * 合并 Eval case 的请求体。
 *
 * 第一版允许一次合并多条，方便前端把导出的草稿 JSON 给开发者编辑后再提交。
 */
public class EvalCaseMergeRequest {

    private List<EvalCaseMergeItem> cases;

    public EvalCaseMergeRequest() {
    }

    public EvalCaseMergeRequest(List<EvalCaseMergeItem> cases) {
        this.cases = cases;
    }

    public List<EvalCaseMergeItem> getCases() {
        return cases == null ? List.of() : cases;
    }

    public void setCases(List<EvalCaseMergeItem> cases) {
        this.cases = cases == null ? List.of() : List.copyOf(cases);
    }
}
