package com.smartcs.smart_cs_agent.workflow.bargain;

import com.smartcs.smart_cs_agent.agent.context.ConversationState;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;

/**
 * 在 bargain 命名空间保存议价多轮状态：买家最近一次出价、我方最近一次有效报价。
 *
 * <p>报价承诺必须可追溯：买家连续砍价时，代码用这里的 lastQuote 判断是“重申一口价”
 * 还是“继续让价”，模型不能凭对话印象自行决定。买家出价与卖家授权底线都只存在于
 * 代码层，bottom price 不会进入任何提示词。</p>
 */
public final class BargainState {

    public static final String WORKFLOW_ID = "bargain";

    private static final String KEY_STEP = "step";
    private static final String KEY_BUYER_OFFER = "buyerOffer";
    private static final String KEY_LAST_QUOTE = "lastQuote";

    /**
     * lastQuote 在 taskState 里的镜像键。
     *
     * <p>bargain 命名空间在暂停转卖家（HANDED_OFF）或本轮任务完成后会被整体清空，
     * 但“已报给买家的授权价”是跨任务事实：恢复后重新进入议价时不能改口报更高的价。
     * taskState 的固定键不受命名空间清理影响，且随会话快照持久化（跨进程重启存活）。
     * 值只存 String，与快照恢复时 taskState 的 String 往返保持一致。</p>
     */
    static final String MIRROR_LAST_QUOTE = "bargain_last_quote";

    private final ConversationState conversationState;

    public BargainState(ConversationState conversationState) {
        this.conversationState = Objects.requireNonNull(
                conversationState,
                "conversationState cannot be null"
        );
    }

    public BargainStep getStep() {
        Object value = conversationState.getWorkflowState(WORKFLOW_ID, KEY_STEP);
        if (value == null) {
            return BargainStep.START;
        }
        try {
            return BargainStep.valueOf(value.toString());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Unknown bargain step: " + value, e);
        }
    }

    public void transitionTo(BargainStep step) {
        conversationState.setWorkflowState(WORKFLOW_ID, KEY_STEP, step.name());
    }

    public BigDecimal getBuyerOffer() {
        return decimal(KEY_BUYER_OFFER);
    }

    public void setBuyerOffer(BigDecimal offer) {
        conversationState.setWorkflowState(
                WORKFLOW_ID,
                KEY_BUYER_OFFER,
                offer == null ? null : offer.toPlainString()
        );
    }

    /**
     * 我方最近一次有效报价（已授权的成交/还价口径），连续砍价时用于重申。
     *
     * <p>命名空间里的值优先；命名空间已被清理（暂停转卖家后恢复、任务完成重开）时
     * 回落到 taskState 镜像，保证已报出的价格不会被改写。</p>
     */
    public BigDecimal getLastQuote() {
        BigDecimal fromNamespace = decimal(KEY_LAST_QUOTE);
        if (fromNamespace != null) {
            return fromNamespace;
        }
        Object mirrored = conversationState.getTaskState(MIRROR_LAST_QUOTE);
        if (mirrored == null) {
            return null;
        }
        try {
            return new BigDecimal(mirrored.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public void setLastQuote(BigDecimal quote) {
        String text = quote == null ? null : quote.toPlainString();
        conversationState.setWorkflowState(WORKFLOW_ID, KEY_LAST_QUOTE, text);
        conversationState.setTaskState(MIRROR_LAST_QUOTE, text);
    }

    public Map<String, Object> snapshot() {
        return conversationState.getWorkflowStateSnapshot(WORKFLOW_ID);
    }

    private BigDecimal decimal(String key) {
        Object value = conversationState.getWorkflowState(WORKFLOW_ID, key);
        if (value == null) {
            return null;
        }
        try {
            return new BigDecimal(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
