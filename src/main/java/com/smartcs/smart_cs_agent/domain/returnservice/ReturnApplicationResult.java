package com.smartcs.smart_cs_agent.domain.returnservice;

import com.smartcs.smart_cs_agent.model.ReturnRecord;

/** 领域服务提交退货申请后的明确业务结果。 */
public final class ReturnApplicationResult {

    private final boolean submitted;
    private final String code;
    private final String message;
    private final ReturnRecord record;
    private final String productName;
    private final String expectedReviewTime;

    private ReturnApplicationResult(boolean submitted,
                                    String code,
                                    String message,
                                    ReturnRecord record,
                                    String productName,
                                    String expectedReviewTime) {
        this.submitted = submitted;
        this.code = code;
        this.message = message;
        this.record = record;
        this.productName = productName;
        this.expectedReviewTime = expectedReviewTime;
    }

    public static ReturnApplicationResult submitted(String message,
                                                    ReturnRecord record,
                                                    String productName,
                                                    String expectedReviewTime) {
        return new ReturnApplicationResult(
                true,
                null,
                message,
                record,
                productName,
                expectedReviewTime
        );
    }

    public static ReturnApplicationResult rejected(String code, String message) {
        return new ReturnApplicationResult(false, code, message, null, null, null);
    }

    public boolean isSubmitted() {
        return submitted;
    }

    public String getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    public ReturnRecord getRecord() {
        return record;
    }

    public String getProductName() {
        return productName;
    }

    public String getExpectedReviewTime() {
        return expectedReviewTime;
    }
}
