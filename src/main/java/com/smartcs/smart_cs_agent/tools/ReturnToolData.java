package com.smartcs.smart_cs_agent.tools;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.smartcs.smart_cs_agent.model.ReturnRecord;

/**
 * 退换货工具的结构化输出 DTO。
 *
 * 同一个 DTO 同时服务两个工具：
 * - return_apply：会关心 submitted、reasonCode、expectedReviewTime
 * - return_query：会关心 found、returnId、status
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ReturnToolData {

    private final boolean found;
    private final Boolean submitted;
    private final String reasonCode;
    private final String returnId;
    private final String orderId;
    private final String productName;
    private final String reason;
    private final String status;
    private final String expectedReviewTime;

    private ReturnToolData(boolean found,
                           Boolean submitted,
                           String reasonCode,
                           String returnId,
                           String orderId,
                           String productName,
                           String reason,
                           String status,
                           String expectedReviewTime) {
        this.found = found;
        this.submitted = submitted;
        this.reasonCode = reasonCode;
        this.returnId = returnId;
        this.orderId = orderId;
        this.productName = productName;
        this.reason = reason;
        this.status = status;
        this.expectedReviewTime = expectedReviewTime;
    }

    public static ReturnToolData notFound(String returnId) {
        return new ReturnToolData(false, null, null, returnId, null, null, null, null, null);
    }

    public static ReturnToolData orderNotFound(String orderId) {
        return new ReturnToolData(false, false, "ORDER_NOT_FOUND", null, orderId, null, null, null, null);
    }

    public static ReturnToolData duplicate(ReturnRecord record, String productName) {
        return fromRecord(record, productName, false, "DUPLICATE_RETURN_APPLICATION", null);
    }

    public static ReturnToolData submitted(ReturnRecord record, String productName, String expectedReviewTime) {
        return fromRecord(record, productName, true, null, expectedReviewTime);
    }

    public static ReturnToolData queried(ReturnRecord record, String productName) {
        return fromRecord(record, productName, null, null, null);
    }

    private static ReturnToolData fromRecord(ReturnRecord record,
                                             String productName,
                                             Boolean submitted,
                                             String reasonCode,
                                             String expectedReviewTime) {
        return new ReturnToolData(
                true,
                submitted,
                reasonCode,
                record.getReturnId(),
                record.getOrderId(),
                productName,
                record.getReason(),
                record.getStatus(),
                expectedReviewTime
        );
    }

    @JsonProperty("found")
    public boolean isFound() {
        return found;
    }

    @JsonProperty("submitted")
    public Boolean getSubmitted() {
        return submitted;
    }

    @JsonProperty("reason_code")
    public String getReasonCode() {
        return reasonCode;
    }

    @JsonProperty("return_id")
    public String getReturnId() {
        return returnId;
    }

    @JsonProperty("order_id")
    public String getOrderId() {
        return orderId;
    }

    @JsonProperty("product_name")
    public String getProductName() {
        return productName;
    }

    @JsonProperty("reason")
    public String getReason() {
        return reason;
    }

    @JsonProperty("status")
    public String getStatus() {
        return status;
    }

    @JsonProperty("expected_review_time")
    public String getExpectedReviewTime() {
        return expectedReviewTime;
    }
}
