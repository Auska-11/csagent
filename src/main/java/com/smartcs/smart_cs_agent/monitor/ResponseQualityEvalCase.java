package com.smartcs.smart_cs_agent.monitor;

import java.util.List;

/**
 * response_quality_eval_cases.json 里的正式评测用例结构。
 *
 * 注意：这个类只保留回复质量规则评测真正需要的字段。
 * FeedbackEvalCaseDraft 里有 sourceCandidateId、reviewNote 等溯源字段，
 * 这些字段适合人工审核和排查，但不能直接写入正式评测文件，
 * 否则测试读取 JSON 时会出现多余字段，评测集也会变得不够干净。
 */
public class ResponseQualityEvalCase {

    /** 评测 case 的稳定 ID，用来避免重复合并 */
    private String id;

    /** 被评测的候选回复 */
    private String candidateReply;

    /** 回复必须包含的关键词；正例通常需要配置，负例也可以用来验证“缺少关键信息” */
    private List<String> requiredKeywords;

    /** 回复禁止出现的关键词，例如危险承诺、内部工具名、debug 信息等 */
    private List<String> forbiddenKeywords;

    /** true 表示这条回复应该通过规则评测，false 表示应该被规则拦截 */
    private boolean expectedPass;

    public ResponseQualityEvalCase() {
        // Jackson 反序列化 JSON 文件时需要无参构造。
    }

    public ResponseQualityEvalCase(String id,
                                   String candidateReply,
                                   List<String> requiredKeywords,
                                   List<String> forbiddenKeywords,
                                   boolean expectedPass) {
        this.id = id;
        this.candidateReply = candidateReply;
        this.requiredKeywords = requiredKeywords == null ? List.of() : List.copyOf(requiredKeywords);
        this.forbiddenKeywords = forbiddenKeywords == null ? List.of() : List.copyOf(forbiddenKeywords);
        this.expectedPass = expectedPass;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getCandidateReply() {
        return candidateReply;
    }

    public void setCandidateReply(String candidateReply) {
        this.candidateReply = candidateReply;
    }

    public List<String> getRequiredKeywords() {
        return requiredKeywords == null ? List.of() : requiredKeywords;
    }

    public void setRequiredKeywords(List<String> requiredKeywords) {
        this.requiredKeywords = requiredKeywords == null ? List.of() : List.copyOf(requiredKeywords);
    }

    public List<String> getForbiddenKeywords() {
        return forbiddenKeywords == null ? List.of() : forbiddenKeywords;
    }

    public void setForbiddenKeywords(List<String> forbiddenKeywords) {
        this.forbiddenKeywords = forbiddenKeywords == null ? List.of() : List.copyOf(forbiddenKeywords);
    }

    public boolean isExpectedPass() {
        return expectedPass;
    }

    public void setExpectedPass(boolean expectedPass) {
        this.expectedPass = expectedPass;
    }
}
