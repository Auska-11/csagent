package com.smartcs.smart_cs_agent.tools;

/**
 * 远端明确拒绝了本次建单请求（协议性拒绝），同样的请求重试不会成功。
 *
 * <p>对应 SLA v1 契约中的 400/401/403/409 以及本地确定性配置错误（缺少卖家账号、
 * 会话坐标不符合渠道要求）。调用方必须把它与“超时/5xx 的结果未知”区分开：
 * 前者直接转入人工复核，不进入通用自动建单重试；后者保持 RESULT_UNKNOWN
 * 由恢复执行器对账。</p>
 */
public class HandoffTicketCreationRejectedException extends HandoffTicketCreationException {

    public HandoffTicketCreationRejectedException(String message) {
        super(message);
    }
}
