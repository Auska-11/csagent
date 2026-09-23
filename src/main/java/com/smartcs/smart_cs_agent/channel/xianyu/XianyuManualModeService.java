package com.smartcs.smart_cs_agent.channel.xianyu;

import com.smartcs.smart_cs_agent.agent.context.ChatRequest;
import com.smartcs.smart_cs_agent.agent.context.ConversationKey;
import com.smartcs.smart_cs_agent.agent.context.ConversationState;
import com.smartcs.smart_cs_agent.agent.context.ConversationStore;
import com.smartcs.smart_cs_agent.session.api.SessionVersionConflictException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.function.Consumer;

/**
 * 闲鱼会话的人工模式：暂停机器人自动回复、卖家消息写入会话、恢复机器人。
 *
 * <p>“暂停机器人让卖家接管”与“创建需要持续处理的事项（工单）”是两件事：SELLER_CONFIRM
 * 这类“等卖家拍个价”的暂停只切换回复主体，不强制建工单。暂停状态存在 taskState
 * （{@code xianyu_manual_mode}，String "true"），随会话快照持久化，跨进程重启存活；
 * 恢复机器人（resumeBot）与卖家发消息分别控制，互不绑定。</p>
 *
 * <p>所有入口都先做渠道门控（租户 + 会话 ID 前缀），商城租户的会话永远不会被这里
 * 暂停或写入。</p>
 */
@Component
public class XianyuManualModeService {

    /** taskState 键；只存 String "true"，与快照恢复的 String 往返保持一致。 */
    static final String MANUAL_MODE_KEY = "xianyu_manual_mode";

    private static final Logger LOGGER = LoggerFactory.getLogger(XianyuManualModeService.class);

    private final ConversationStore conversationStore;
    private final XianyuSidecarProperties sidecarProperties;

    @Autowired
    public XianyuManualModeService(ConversationStore conversationStore,
                                    XianyuSidecarProperties sidecarProperties) {
        this.conversationStore = conversationStore;
        this.sidecarProperties = sidecarProperties;
    }

    /** 该请求是否属于闲鱼渠道（租户与会话 ID 同源校验；不要求会话已存在）。 */
    public boolean isXianyuChatRequest(ChatRequest request) {
        return request != null
                && sidecarProperties.getTenantId() != null
                && sidecarProperties.getTenantId().equalsIgnoreCase(request.getTenantId())
                && XianyuIdentity.isXianyuConversationId(request.getConversationId());
    }

    /**
     * 机器人是否对该请求暂停（人工模式中）。非闲鱼请求恒为 false；会话尚不存在时
     * 也为 false（没有可暂停的东西）。
     */
    public boolean isBotPaused(ChatRequest request) {
        if (!isXianyuChatRequest(request)) {
            return false;
        }
        ConversationState state = conversationStore.get(ConversationKey.from(request));
        return state != null && Boolean.parseBoolean(
                String.valueOf(state.getTaskState(MANUAL_MODE_KEY))
        );
    }

    /**
     * HANDED_OFF 后自动进入人工模式：闲鱼会话转人工（包括 pauseOnly 的卖家确认暂停）
     * 的当轮结束后立即持久化暂停标志，不依赖侧车再调一次 manual-mode，消除
     * “响应已告知暂停、下一条买家消息仍被机器人抢答”的竞态窗口。
     *
     * @return 本次调用是否真正置位（已暂停或非闲鱼会话返回 false）
     */
    public boolean markManualAfterHandoff(ChatRequest request) {
        if (!isXianyuChatRequest(request) || isBotPaused(request)) {
            return false;
        }
        ConversationKey key = ConversationKey.from(request);
        ConversationState state = conversationStore.get(key);
        if (state == null) {
            return false;
        }
        state.setTaskState(MANUAL_MODE_KEY, "true");
        saveWithRetry(key, state, fresh -> fresh.setTaskState(MANUAL_MODE_KEY, "true"));
        LOGGER.info("闲鱼会话 HANDED_OFF，进入人工模式: {}", request.getConversationId());
        return true;
    }

    /**
     * 侧车显式切换人工模式（卖家主动接管/交回）。会话必须已存在（注册过），否则
     * 返回 false 由 Controller 转 404。
     */
    public boolean setManualMode(String buyerId, String itemId, boolean enabled) {
        ConversationKey key = conversationKey(buyerId, itemId);
        ConversationState state = conversationStore.get(key);
        if (state == null) {
            return false;
        }
        state.setTaskState(MANUAL_MODE_KEY, enabled ? "true" : null);
        saveWithRetry(key, state, fresh -> fresh.setTaskState(MANUAL_MODE_KEY, enabled ? "true" : null));
        LOGGER.info("闲鱼人工模式 {} : {}", enabled ? "开启" : "关闭", key.conversationId());
        return true;
    }

    /** 当前人工模式状态；会话不存在返回 empty 由 Controller 转 404。 */
    public Optional<Boolean> manualMode(String buyerId, String itemId) {
        ConversationState state = conversationStore.get(conversationKey(buyerId, itemId));
        if (state == null) {
            return Optional.empty();
        }
        return Optional.of(Boolean.parseBoolean(
                String.valueOf(state.getTaskState(MANUAL_MODE_KEY))
        ));
    }

    /**
     * 卖家以人工身份向会话追加一条助手消息（买家侧看到卖家原文），可选同时恢复机器人。
     * 消息原文不经过任何改写；机器人恢复与否由 resumeBot 单独决定。
     */
    public boolean addSellerMessage(String buyerId, String itemId, String text, boolean resumeBot) {
        ConversationKey key = conversationKey(buyerId, itemId);
        ConversationState state = conversationStore.get(key);
        if (state == null) {
            return false;
        }
        state.addAssistantMessage(text);
        if (resumeBot) {
            state.setTaskState(MANUAL_MODE_KEY, null);
        }
        saveWithRetry(key, state, fresh -> {
            fresh.addAssistantMessage(text);
            if (resumeBot) {
                fresh.setTaskState(MANUAL_MODE_KEY, null);
            }
        });
        LOGGER.info(
                "闲鱼卖家消息写入: {} (resumeBot={})",
                key.conversationId(),
                resumeBot
        );
        return true;
    }

    private ConversationKey conversationKey(String buyerId, String itemId) {
        return new ConversationKey(
                sidecarProperties.getTenantId(),
                XianyuIdentity.syntheticUserId(buyerId),
                XianyuIdentity.conversationId(buyerId, itemId)
        );
    }

    /**
     * 侧车低频写操作：CAS 冲突（与买家消息并发）时重读最新状态，把意图（{@code replay}）
     * 重放上去再保存，最多三次。直接保存旧实例会覆盖并发写，直接放弃会丢卖家消息。
     */
    private void saveWithRetry(ConversationKey key,
                               ConversationState state,
                               Consumer<ConversationState> replay) {
        for (int attempt = 1; ; attempt++) {
            try {
                conversationStore.save(key, state);
                return;
            } catch (SessionVersionConflictException exception) {
                if (attempt >= 3) {
                    throw exception;
                }
                ConversationState fresh = conversationStore.get(key);
                if (fresh == null) {
                    throw exception;
                }
                replay.accept(fresh);
                state = fresh;
            }
        }
    }
}
