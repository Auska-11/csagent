package com.smartcs.smart_cs_agent.eval.baseline;

import dev.langchain4j.data.message.ChatMessage;
import com.smartcs.smart_cs_agent.agent.context.ConversationKey;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基线 Agent 的会话历史：保存每个 conversation 的完整多轮消息（不含 system prompt）。
 *
 * <p>这是有意的"朴素"实现——不做摘要压缩、不做滑窗截断，每次调用把全量历史发给模型。
 * 对照实验的效率指标（token 成本）需要基线保持这种朴素上下文策略才是公平的；
 * Workflow 侧的摘要记忆与这里的差异本身就是被测量的变量之一。</p>
 *
 * <p>演示环境单实例内存存储即可；并发保护用粗粒度 synchronized，
 * 与 ConversationStore 的定位一致，不引入外部依赖。</p>
 */
@Component
@ConditionalOnProperty(name = "smartcs.eval.baseline.enabled", havingValue = "true")
public class BaselineConversationStore {

    private static final int MAX_HISTORY_MESSAGES = 400;

    private final Map<ConversationKey, List<ChatMessage>> conversations = new ConcurrentHashMap<>();

    /** 取出指定会话的完整历史（可能为空列表）；返回的是可变副本，供本轮追加后写回。 */
    public synchronized List<ChatMessage> historyOf(ConversationKey key) {
        return new ArrayList<>(conversations.getOrDefault(key, List.of()));
    }

    /** 追加一条消息并写回；超过上限时按 FIFO 丢弃最早的消息（防内存无限增长的最低护栏）。 */
    public synchronized void append(ConversationKey key, ChatMessage message) {
        List<ChatMessage> history = new ArrayList<>(
                conversations.getOrDefault(key, List.of()));
        history.add(message);
        if (history.size() > MAX_HISTORY_MESSAGES) {
            history = new ArrayList<>(history.subList(history.size() - MAX_HISTORY_MESSAGES, history.size()));
        }
        conversations.put(key, history);
    }
}
