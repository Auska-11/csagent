package com.smartcs.smart_cs_agent.agent.context;

import java.util.*;

/**
 * 会话状态
 *
 * 【什么是 ConversationState？】
 * ConversationState 是一轮对话的状态记录本。
 * 它记录了：用户说了什么、Agent 做了什么、当前执行到哪一步。
 *
 * 【存储内容】
 * - message：当前用户消息（过渡字段，后续会逐步迁移到 ChatRequest）
 * - messages：历史对话记录
 * - activeIntent：当前识别的意图
 * - observations：执行过程中的观察结果
 * - taskState：任务状态（存储临时数据）
 * - userId：用户标识
 *
 * 【生命周期】
 * 用户消息 → 创建状态 → 更新状态 → 保存/丢弃
 *
 * 【与 Memory 的区别】
 * - ConversationState：一轮对话，会话结束可能丢弃
 * - Memory：跨会话，持久化存储
 *
 * 【当前扩展】
 * 短期窗口、摘要记忆和 Workflow 命名空间已经进入本对象；跨会话用户偏好仍由独立
 * UserMemoryStore 保存，避免把两种生命周期混在一起。
 */
public class ConversationState {

    /**
     * 短期记忆最多保留多少条消息。
     *
     * 这里先用固定值，方便学习和测试。
     * 真实项目中可以把它放到 application.yml 里做成可配置项。
     */
    private static final int SHORT_TERM_MESSAGE_LIMIT = 8;

    /**
     * 当短期消息超过这个阈值时，触发一次摘要压缩。
     *
     * 第一版为了让“短期记忆有明确上限”，一旦超过窗口就压缩。
     * 后续如果接入更复杂的 Memory 策略，可以把触发阈值做成配置项。
     */
    private static final int SUMMARY_TRIGGER_MESSAGE_COUNT = SHORT_TERM_MESSAGE_LIMIT;

    /**
     * 不应该暴露给前端 Memory 面板的内部状态 key。
     *
     * 这些字段是 Runtime 为了控制执行循环使用的临时标记，
     * 它们不属于“客服任务记忆”，展示出来反而会干扰理解。
     */
    private static final Set<String> INTERNAL_TASK_STATE_KEYS = Set.of(
            "last_llm_purpose",
            "response_generated",
            "intent_handled",
            "last_capability",
            "last_capability_result"
    );

    /**
     * Legacy Skill 仍然依赖这些字段跨轮收集槽位，因此持久化快照需要保留它们。
     * 其他 taskState 大多是当前一轮的工具结果或循环标记，重启后不应该恢复。
     */
    private static final Set<String> PERSISTENT_TASK_STATE_KEYS = Set.of(
            "pending_slot",
            "order_id",
            "return_id",
            "return_reason",
            "last_user_preference",
            "used_user_preference"
    );

    /**
     * 当前用户消息
     *
     * 【过渡说明】
     * 按照新架构，当前输入应该放在 ChatRequest 中。
     * 现在主链路已经通过 TurnContext 读取当前消息。
     * 但为了兼容旧入口和测试，这里暂时保留 message，并允许每轮更新。
     *
     * 后续引入 TurnContext 后，可以逐步弱化这个字段。
     */
    private String message;

    /**
     * 短期对话记忆。
     *
     * 早期版本这里保存完整历史，长对话时 prompt 会越来越长。
     * 现在升级为“最近消息窗口”：旧消息会被压缩进 summaryMemory。
     */
    private final List<Map<String, String>> messages;

    /** 当前识别的意图 */
    private String activeIntent;

    /** 执行过程中的观察结果 */
    private final List<Observation> observations;

    /** 任务状态（存储临时数据） */
    private final Map<String, Object> taskState;

    /** 用户标识 */
    private final String userId;

    /** 摘要记忆：用规则方式压缩过的较早历史消息 */
    private String summaryMemory;

    /** 会话累计消息数，包括用户消息和助手消息 */
    private int totalMessageCount;

    /** 摘要更新次数，用来判断本轮是否发生过压缩 */
    private int summaryVersion;

    /** 摘要最近更新时间，毫秒时间戳；没有摘要时为 0 */
    private long summaryUpdatedAt;

    /**
     * 当前正在执行或最近结束的 Workflow ID。
     *
     * <p>它和 activeIntent 不同：intent 只是“用户想做什么”的分类；Workflow ID 指向真正负责
     * 多轮状态机的实现。Router 会优先恢复非终态 activeWorkflow。</p>
     */
    private String activeWorkflowId;

    /** 启动当前 Workflow 时使用的实现版本，方便恢复时检测部署版本变化。 */
    private String workflowVersion;

    /** 当前 Workflow 生命周期状态；null 表示尚未进入新版 Workflow 路由。 */
    private WorkflowStatus workflowStatus;

    /** 当前 Workflow 首次启动时间，毫秒时间戳。 */
    private long workflowStartedAt;

    /** 当前 Workflow 最近一次状态变化时间，毫秒时间戳。 */
    private long workflowUpdatedAt;

    /**
     * 按 Workflow ID 隔离的私有状态。
     *
     * <p>外层 key 是 workflowId，内层才是该流程的 orderId、step 等字段。采用命名空间后，
     * return_apply 的 orderId 不会被另一个 Workflow 当作自己的槽位。</p>
     */
    private final Map<String, Map<String, Object>> workflowStates;

    /**
     * 构造函数
     *
     * @param message 用户消息
     */
    public ConversationState(String message) {
        this(message, "default");
    }

    /**
     * 构造函数
     *
     * @param message 用户消息
     * @param userId 用户标识
     */
    public ConversationState(String message, String userId) {
        this(message, userId, true);
    }

    /** restore 使用 addInitialMessage=false，避免恢复时把最后一条用户消息再追加一次。 */
    private ConversationState(String message, String userId, boolean addInitialMessage) {
        this.message = message;
        this.userId = userId;
        this.messages = new ArrayList<>();
        this.observations = new ArrayList<>();
        this.taskState = new HashMap<>();
        this.summaryMemory = "";
        this.totalMessageCount = 0;
        this.summaryVersion = 0;
        this.summaryUpdatedAt = 0;
        this.activeWorkflowId = null;
        this.workflowVersion = null;
        this.workflowStatus = null;
        this.workflowStartedAt = 0;
        this.workflowUpdatedAt = 0;
        this.workflowStates = new LinkedHashMap<>();

        if (addInitialMessage) {
            // 普通新会话需要把第一条用户消息加入历史；持久化恢复则直接复制原窗口。
            addMessage("user", message);
        }
    }

    /**
     * 从持久化快照恢复一个可继续执行的 ConversationState。
     *
     * <p>Observation 和 Runtime 临时 taskState 故意不会恢复。新一轮开始时 Workflow 只需要消息、
     * 槽位和自己的命名空间状态；旧工具结果跨重启复用反而可能产生错误判断。</p>
     */
    public static ConversationState restore(ConversationStateSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot cannot be null");
        if (snapshot.schemaVersion() != ConversationStateSnapshot.CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException(
                    "Unsupported conversation state schemaVersion: " + snapshot.schemaVersion()
            );
        }

        // 兼容直接调用 restore 的旧测试和工具：没有迁移注册表时，只允许读取基线 v1。
        for (Map.Entry<String, WorkflowStateSnapshot> entry : snapshot.workflowStates().entrySet()) {
            if (entry.getValue().schemaVersion() != WorkflowStateSnapshot.BASE_SCHEMA_VERSION) {
                throw new UnsupportedWorkflowStateSchemaException(
                        entry.getKey(),
                        entry.getValue().schemaVersion(),
                        WorkflowStateSnapshot.BASE_SCHEMA_VERSION
                );
            }
        }
        return restoreMigrated(snapshot);
    }

    /**
     * 只供 WorkflowStateMigrationRegistry 调用：传入快照已经完成逐版本校验和迁移。
     */
    static ConversationState restoreMigrated(ConversationStateSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot cannot be null");
        if (snapshot.schemaVersion() != ConversationStateSnapshot.CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException(
                    "Unsupported conversation state schemaVersion: " + snapshot.schemaVersion()
            );
        }

        ConversationState state = new ConversationState(snapshot.message(), snapshot.userId(), false);
        state.messages.addAll(snapshot.messages());
        state.activeIntent = snapshot.activeIntent();
        state.taskState.putAll(snapshot.taskState());
        state.summaryMemory = snapshot.summaryMemory();
        state.totalMessageCount = snapshot.totalMessageCount();
        state.summaryVersion = snapshot.summaryVersion();
        state.summaryUpdatedAt = snapshot.summaryUpdatedAt();
        state.activeWorkflowId = snapshot.activeWorkflowId();
        state.workflowVersion = snapshot.workflowVersion();
        state.workflowStatus = snapshot.workflowStatus() == null || snapshot.workflowStatus().isBlank()
                ? null
                : WorkflowStatus.valueOf(snapshot.workflowStatus());
        state.workflowStartedAt = snapshot.workflowStartedAt();
        state.workflowUpdatedAt = snapshot.workflowUpdatedAt();
        for (Map.Entry<String, WorkflowStateSnapshot> entry : snapshot.workflowStates().entrySet()) {
            WorkflowStateSnapshot workflowSnapshot = entry.getValue();
            state.workflowStates.put(
                    entry.getKey(),
                    new LinkedHashMap<>(workflowSnapshot.payload())
            );
        }
        return state;
    }

    /**
     * 获取用户消息
     */
    public String getMessage() {
        return message;
    }

    /**
     * 开始新一轮对话
     *
     * 【什么时候调用？】
     * ConversationStore 发现这是已有 conversationId 的下一轮消息时调用。
     *
     * 【做了什么？】
     * 1. 更新当前轮用户消息
     * 2. 清空上一轮的观察结果，避免旧工具结果影响新一轮判断
     * 3. 清理上一轮 LLM/回复生成的临时标记
     * 4. 把当前用户消息追加到历史消息
     */
    public void startNewTurn(String message) {
        this.message = message;
        this.observations.clear();

        // 这些状态只属于上一轮执行过程，新一轮开始时需要清理。
        this.taskState.remove("last_llm_purpose");
        this.taskState.remove("response_generated");
        this.taskState.remove("intent_handled");

        addMessage("user", message);
    }

    /**
     * 获取历史对话记录
     */
    public List<Map<String, String>> getMessages() {
        return messages;
    }

    /**
     * 添加助手消息到历史记录
     */
    public void addAssistantMessage(String content) {
        addMessage("assistant", content);
    }

    /**
     * 添加一条消息到短期记忆。
     *
     * 【为什么不直接 messages.add？】
     * 因为每次新增消息后，都需要顺手检查是否应该压缩长会话。
     * 把这个动作封装起来，可以避免以后某个调用点忘记压缩。
     */
    private void addMessage(String role, String content) {
        messages.add(Map.of(
                "role", role,
                "content", content == null ? "" : content
        ));
        totalMessageCount++;
        compressMemoryIfNeeded();
    }

    /**
     * 当短期消息太多时，把较早消息压缩进 summaryMemory。
     *
     * 第一版不用 LLM 做摘要，而是用规则生成“可解释摘要”：
     * - 不依赖外部模型，测试稳定；
     * - 面试时可以先讲清楚结构，再说明后续可替换为 LLM 摘要；
     * - 对新手更友好，代码行为是确定的。
     */
    private void compressMemoryIfNeeded() {
        if (messages.size() <= SUMMARY_TRIGGER_MESSAGE_COUNT) {
            return;
        }

        int compressCount = messages.size() - SHORT_TERM_MESSAGE_LIMIT;
        if (compressCount <= 0) {
            return;
        }

        List<Map<String, String>> compressedMessages = new ArrayList<>(messages.subList(0, compressCount));
        String fragment = buildSummaryFragment(compressedMessages);
        if (summaryMemory == null || summaryMemory.isBlank()) {
            summaryMemory = fragment;
        } else {
            summaryMemory = summaryMemory + "\n" + fragment;
        }

        summaryVersion++;
        summaryUpdatedAt = System.currentTimeMillis();

        // subList(...).clear() 会直接删除原 List 中对应范围的元素。
        // 这里删除的是已经进入摘要的旧消息，留下最近几条继续作为短期记忆。
        messages.subList(0, compressCount).clear();
    }

    /**
     * 构造一段规则摘要。
     *
     * 注意：这不是为了写出最优美的摘要，而是为了保留关键上下文。
     * 后续如果接入 LLM 摘要，可以只替换这个方法或新建 MemoryCompressor。
     */
    private String buildSummaryFragment(List<Map<String, String>> compressedMessages) {
        StringBuilder builder = new StringBuilder();
        builder.append("第 ").append(summaryVersion + 1).append(" 次压缩：");
        builder.append("已归档 ").append(compressedMessages.size()).append(" 条较早消息。关键片段：");

        for (int i = 0; i < compressedMessages.size(); i++) {
            Map<String, String> message = compressedMessages.get(i);
            if (i > 0) {
                builder.append(" / ");
            }
            builder.append(formatRole(message.get("role")))
                    .append("：")
                    .append(preview(message.get("content"), 42));
        }
        return builder.toString();
    }

    private String formatRole(String role) {
        if ("user".equals(role)) {
            return "用户";
        }
        if ("assistant".equals(role)) {
            return "客服";
        }
        return role == null ? "未知" : role;
    }

    private String preview(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "...";
    }

    /**
     * 获取当前识别的意图
     */
    public String getActiveIntent() {
        return activeIntent;
    }

    /**
     * 设置当前识别的意图
     */
    public void setActiveIntent(String activeIntent) {
        this.activeIntent = activeIntent;
    }

    /**
     * 添加观察结果
     *
     * @param type 观察类型（capability, llm_response 等）
     * @param data 观察数据
     */
    public void addObservation(String type, Object data) {
        observations.add(new Observation(type, data));
    }

    /**
     * 获取所有观察结果
     */
    public List<Observation> getObservations() {
        return observations;
    }

    /**
     * 获取最新的观察结果
     */
    public Observation getLatestObservation() {
        if (observations.isEmpty()) {
            return null;
        }
        return observations.get(observations.size() - 1);
    }

    /**
     * 获取指定类型的最新观察结果
     */
    public Observation getLatestObservationByType(String type) {
        for (int i = observations.size() - 1; i >= 0; i--) {
            if (observations.get(i).getType().equals(type)) {
                return observations.get(i);
            }
        }
        return null;
    }

    /**
     * 设置任务状态
     */
    public void setTaskState(String key, Object value) {
        // value 为 null 时表示清理这个状态。
        // 这样调用方不需要额外暴露 taskState.remove，也能避免 Map 里残留一堆空值。
        if (value == null) {
            taskState.remove(key);
            return;
        }
        taskState.put(key, value);
    }

    /**
     * 获取任务状态
     */
    public Object getTaskState(String key) {
        return taskState.get(key);
    }

    /**
     * 生成任务记忆快照。
     *
     * taskState 里既有业务槽位，也有 Runtime 临时标记。
     * 前端 Memory 面板只展示对理解任务有帮助的字段，所以这里会过滤掉内部字段。
     */
    public Map<String, Object> getTaskMemory() {
        Map<String, Object> memory = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : taskState.entrySet()) {
            if (INTERNAL_TASK_STATE_KEYS.contains(entry.getKey())) {
                continue;
            }
            memory.put(entry.getKey(), simplifyMemoryValue(entry.getValue()));
        }
        return memory;
    }

    private Object simplifyMemoryValue(Object value) {
        if (value == null
                || value instanceof String
                || value instanceof Number
                || value instanceof Boolean) {
            return value;
        }
        return value.toString();
    }

    /**
     * 设置当前等待用户补充的槽位。
     *
     * 例如退货流程里：
     * - pending_slot = order_id 表示正在等用户补充订单号
     * - pending_slot = return_reason 表示正在等用户补充退货原因
     */
    public void setPendingSlot(String slotName) {
        setTaskState("pending_slot", slotName);
    }

    /**
     * 获取当前等待用户补充的槽位。
     */
    public String getPendingSlot() {
        Object value = getTaskState("pending_slot");
        return value == null ? null : value.toString();
    }

    /**
     * 清空等待补充的槽位。
     */
    public void clearPendingSlot() {
        taskState.remove("pending_slot");
    }

    /**
     * 清理一次任务完成后的临时进度。
     *
     * 【什么时候调用？】
     * AgentRuntime 准备返回最终回复时调用。
     *
     * 【为什么要清理？】
     * 例如用户刚完成一次退货申请，taskState 里可能还有：
     * - order_id = ORD-004
     * - return_reason = 质量问题
     *
     * 如果不清理，下一次用户开启新任务时，旧订单号和旧原因可能误导后续逻辑。
     *
     * 【为什么不清理 activeIntent 和 messages？】
     * - activeIntent 保留最近意图，方便调试。
     * - messages 是对话历史，后续做摘要、Trace、LLM 回复都会用到。
     */
    public void clearTaskProgress() {
        taskState.remove("pending_slot");
        taskState.remove("order_id");
        taskState.remove("return_id");
        taskState.remove("return_reason");
        taskState.remove("last_llm_purpose");
        taskState.remove("response_generated");
        taskState.remove("intent_handled");
        taskState.remove("last_capability");
        taskState.remove("last_capability_result");
        taskState.remove("last_user_preference");
        taskState.remove("used_user_preference");
    }

    /**
     * 启动一个新 Workflow，或把同一个等待中的 Workflow 恢复为 ACTIVE。
     *
     * <p>如果 ID 与当前非终态 Workflow 相同，只更新状态，不清空它已经收集的槽位；
     * 如果进入另一个 Workflow，则为它创建独立命名空间并记录新的开始时间。</p>
     */
    public void startOrResumeWorkflow(String workflowId, String version) {
        if (workflowId == null || workflowId.isBlank()) {
            throw new IllegalArgumentException("workflowId cannot be empty");
        }
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("workflow version cannot be empty");
        }

        long now = System.currentTimeMillis();
        boolean sameNonTerminalWorkflow = workflowId.equals(activeWorkflowId)
                && workflowStatus != null
                && !workflowStatus.isTerminal();

        if (!sameNonTerminalWorkflow) {
            this.activeWorkflowId = workflowId;
            this.workflowVersion = version;
            this.workflowStartedAt = now;
            workflowStates.put(workflowId, new LinkedHashMap<>());
        }

        this.workflowStatus = WorkflowStatus.ACTIVE;
        this.workflowUpdatedAt = now;
    }

    /** 当前是否存在应该跨轮恢复的非终态 Workflow。 */
    public boolean hasActiveNonTerminalWorkflow() {
        return activeWorkflowId != null
                && workflowStatus != null
                && !workflowStatus.isTerminal();
    }

    public String getActiveWorkflowId() {
        return activeWorkflowId;
    }

    public String getWorkflowVersion() {
        return workflowVersion;
    }

    public WorkflowStatus getWorkflowStatus() {
        return workflowStatus;
    }

    public long getWorkflowStartedAt() {
        return workflowStartedAt;
    }

    public long getWorkflowUpdatedAt() {
        return workflowUpdatedAt;
    }

    public void markWorkflowWaiting() {
        updateWorkflowStatus(WorkflowStatus.WAITING_USER);
    }

    public void markWorkflowCompleted() {
        // Workflow 自己可能已经把任务标为 CANCELLED；Runtime 返回 FinalAnswer 时不应把它覆盖成 COMPLETED。
        if (workflowStatus == WorkflowStatus.CANCELLED || workflowStatus == WorkflowStatus.HANDED_OFF) {
            return;
        }
        updateWorkflowStatus(WorkflowStatus.COMPLETED);
        clearActiveWorkflowState();
    }

    public void markWorkflowCancelled() {
        updateWorkflowStatus(WorkflowStatus.CANCELLED);
        clearActiveWorkflowState();
    }

    public void markWorkflowHandedOff() {
        updateWorkflowStatus(WorkflowStatus.HANDED_OFF);
        clearActiveWorkflowState();
    }

    public void markWorkflowFailed() {
        updateWorkflowStatus(WorkflowStatus.FAILED);
    }

    private void updateWorkflowStatus(WorkflowStatus status) {
        if (activeWorkflowId == null) {
            return;
        }
        this.workflowStatus = Objects.requireNonNull(status, "workflow status cannot be null");
        this.workflowUpdatedAt = System.currentTimeMillis();
    }

    /**
     * 在指定 Workflow 的命名空间内写状态。
     * null 表示删除，和 setTaskState 的约定一致。
     */
    public void setWorkflowState(String workflowId, String key, Object value) {
        validateWorkflowStateAddress(workflowId, key);
        validateWorkflowStateValue(workflowId, key, value);
        Map<String, Object> namespace = workflowStates.computeIfAbsent(
                workflowId,
                ignored -> new LinkedHashMap<>()
        );
        if (value == null) {
            namespace.remove(key);
        } else {
            namespace.put(key, value);
        }
        workflowUpdatedAt = System.currentTimeMillis();
    }

    public Object getWorkflowState(String workflowId, String key) {
        validateWorkflowStateAddress(workflowId, key);
        Map<String, Object> namespace = workflowStates.get(workflowId);
        return namespace == null ? null : namespace.get(key);
    }

    /** 返回只读副本，避免 Workflow 绕过 setter 直接修改内部 Map。 */
    public Map<String, Object> getWorkflowStateSnapshot(String workflowId) {
        if (workflowId == null || workflowId.isBlank()) {
            return Map.of();
        }
        Map<String, Object> namespace = workflowStates.get(workflowId);
        return namespace == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(namespace));
    }

    public Map<String, Object> getActiveWorkflowStateSnapshot() {
        return getWorkflowStateSnapshot(activeWorkflowId);
    }

    private void clearActiveWorkflowState() {
        if (activeWorkflowId != null) {
            workflowStates.remove(activeWorkflowId);
        }
    }

    private void validateWorkflowStateAddress(String workflowId, String key) {
        if (workflowId == null || workflowId.isBlank()) {
            throw new IllegalArgumentException("workflowId cannot be empty");
        }
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("workflow state key cannot be empty");
        }
    }

    /**
     * Workflow 命名空间属于可持久化业务状态，不能像 taskState 那样保存任意运行时对象。
     * 需要结构化 payload 时应先升级 WorkflowStateSnapshot schema，而不是依赖 Java 类名序列化。
     */
    private void validateWorkflowStateValue(String workflowId, String key, Object value) {
        if (value == null
                || value instanceof String
                || value instanceof Number
                || value instanceof Boolean
                || value instanceof Enum<?>) {
            return;
        }
        throw new IllegalArgumentException(
                "Workflow state only supports scalar values: " + workflowId + "." + key
        );
    }

    /**
     * 获取用户标识
     */
    public String getUserId() {
        return userId;
    }

    public String getSummaryMemory() {
        return summaryMemory == null ? "" : summaryMemory;
    }

    public int getTotalMessageCount() {
        return totalMessageCount;
    }

    public int getShortTermMessageLimit() {
        return SHORT_TERM_MESSAGE_LIMIT;
    }

    public int getSummaryVersion() {
        return summaryVersion;
    }

    public long getSummaryUpdatedAt() {
        return summaryUpdatedAt;
    }

    /**
     * 生成可写入 SessionStore 的稳定快照。
     *
     * <p>Workflow 私有状态当前只写入 step、订单号、原因等标量。这里统一保存成 String，避免
     * Object 经过 JSON 后类型漂移。若以后确实需要结构化值，应升级 schema 并增加显式 DTO。</p>
     */
    public ConversationStateSnapshot toPersistenceSnapshot() {
        Map<String, String> persistedTaskState = new LinkedHashMap<>();
        for (String key : PERSISTENT_TASK_STATE_KEYS) {
            Object value = taskState.get(key);
            if (value != null) {
                persistedTaskState.put(key, persistentText(value, "taskState." + key));
            }
        }

        Map<String, WorkflowStateSnapshot> persistedWorkflowStates = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Object>> workflowEntry : workflowStates.entrySet()) {
            Map<String, String> namespace = new LinkedHashMap<>();
            for (Map.Entry<String, Object> stateEntry : workflowEntry.getValue().entrySet()) {
                if (stateEntry.getValue() != null) {
                    namespace.put(
                            stateEntry.getKey(),
                            persistentText(
                                    stateEntry.getValue(),
                                    "workflowState." + workflowEntry.getKey() + "." + stateEntry.getKey()
                            )
                    );
                }
            }
            persistedWorkflowStates.put(
                    workflowEntry.getKey(),
                    new WorkflowStateSnapshot(
                            WorkflowStateSnapshot.BASE_SCHEMA_VERSION,
                            namespace
                    )
            );
        }

        return new ConversationStateSnapshot(
                ConversationStateSnapshot.CURRENT_SCHEMA_VERSION,
                userId,
                message,
                new ArrayList<>(messages),
                activeIntent,
                persistedTaskState,
                getSummaryMemory(),
                totalMessageCount,
                summaryVersion,
                summaryUpdatedAt,
                activeWorkflowId,
                workflowVersion,
                workflowStatus == null ? null : workflowStatus.name(),
                workflowStartedAt,
                workflowUpdatedAt,
                persistedWorkflowStates
        );
    }

    private String persistentText(Object value, String address) {
        if (value instanceof String
                || value instanceof Number
                || value instanceof Boolean
                || value instanceof Enum<?>) {
            return value.toString();
        }
        throw new IllegalStateException(
                "Unsupported persistent conversation value at " + address + ": " + value.getClass().getName()
        );
    }

    /**
     * 是否已经产生过摘要记忆。
     */
    public boolean hasSummaryMemory() {
        return summaryMemory != null && !summaryMemory.isBlank();
    }

    /**
     * 生成给接口、前端和测试使用的只读记忆快照。
     */
    public ConversationMemorySnapshot toMemorySnapshot(String conversationId) {
        Map<String, Object> taskMemory = new LinkedHashMap<>();
        if (activeIntent != null) {
            taskMemory.put("activeIntent", activeIntent);
        }
        if (getPendingSlot() != null) {
            taskMemory.put("pendingSlot", getPendingSlot());
        }
        taskMemory.putAll(getTaskMemory());

        return new ConversationMemorySnapshot(
                conversationId,
                userId,
                activeIntent,
                getPendingSlot(),
                activeWorkflowId,
                workflowVersion,
                workflowStatus == null ? null : workflowStatus.name(),
                getActiveWorkflowStateSnapshot(),
                workflowStartedAt,
                workflowUpdatedAt,
                taskMemory,
                new ArrayList<>(messages),
                getSummaryMemory(),
                totalMessageCount,
                SHORT_TERM_MESSAGE_LIMIT,
                summaryVersion,
                summaryUpdatedAt
        );
    }

    @Override
    public String toString() {
        return "ConversationState{" +
                "message=" + message +
                ", activeIntent=" + activeIntent +
                ", activeWorkflowId=" + activeWorkflowId +
                ", workflowStatus=" + workflowStatus +
                ", observations=" + observations.size() +
                ", userId=" + userId +
                "}";
    }
}
