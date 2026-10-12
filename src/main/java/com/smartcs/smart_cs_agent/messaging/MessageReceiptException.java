package com.smartcs.smart_cs_agent.messaging;

public class MessageReceiptException extends RuntimeException {
    private final String errorCode;

    public MessageReceiptException(String errorCode) {
        super(errorCode);
        this.errorCode = errorCode;
    }

    public String getErrorCode() { return errorCode; }
}
