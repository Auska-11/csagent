package com.smartcs.smart_cs_agent.handoff.review;

import com.smartcs.smart_cs_agent.tools.AfterSalesTicketCreateResult;

/**
 * 带强制审计的人工处置写端口。
 *
 * <p>它刻意不继承 20.2 的 {@link PendingHandoffReviewStore}。后续 Adapter 接入这个端口后，上层管理 API
 * 只能调用会原子追加审计的新方法，不能因为继承关系意外选择旧的无审计写方法。</p>
 */
public interface PendingHandoffManualDecisionStore {

    /** 将待复核记录重新排队，并在同一原子操作中追加 RETRY 审计。 */
    PendingHandoffManualDecisionResult retry(PendingHandoffManualDecisionContext context);

    /** 保存可信的外部成功结果，并在同一原子操作中追加 CONFIRM 审计。 */
    PendingHandoffManualDecisionResult confirm(PendingHandoffManualDecisionContext context,
                                               AfterSalesTicketCreateResult result);

    /** 进入不可逆人工关闭终态，并在同一原子操作中追加 CLOSE 审计。 */
    PendingHandoffManualDecisionResult close(PendingHandoffManualDecisionContext context);
}
