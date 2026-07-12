package com.smartcs.smart_cs_agent.tools;

/**
 * 外部人工工单创建结果无法确认。
 *
 * <p>它表达的是“本次不能安全宣告成功”，不等于远端一定没有创建工单。上层 Gateway 会把异常类型
 * 转成不泄露内部地址的技术失败，并由幂等 Store 稳定回放，避免同一个 key 立即重复调用远端。</p>
 */
public class HandoffTicketCreationException extends RuntimeException {

    public HandoffTicketCreationException(String message, Throwable cause) {
        super(message, cause);
    }
}
