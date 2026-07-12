package com.smartcs.smart_cs_agent.workflow.api;

import java.util.Map;

/**
 * 单个 Workflow 私有状态的逐版本迁移扩展点。
 *
 * <p>每个实现只负责一步，例如 {@code 1 -> 2}。注册表会把多步迁移按版本顺序串起来，因此新增
 * {@code 2 -> 3} 时不需要修改旧迁移器、ConversationStore、Router 或 Runtime。</p>
 *
 * <p>接口只使用字符串 Map，不依赖数据库、Spring 或具体 Workflow 类。这样迁移规则可以跟随业务
 * Workflow 放在同一个包中，同时保持 API 层干净。</p>
 */
public interface WorkflowStateMigrator {

    /** 迁移器所属的 Workflow ID，例如 {@code return_apply}。 */
    String workflowId();

    /** 能读取的旧 stateSchemaVersion。 */
    int fromVersion();

    /** 迁移完成后的版本；当前要求必须等于 {@code fromVersion + 1}。 */
    int toVersion();

    /**
     * 把旧 payload 转换成下一版本。
     *
     * <p>实现应保持确定性和无副作用：不能调用模型、数据库或远程服务，也不能修改传入 Map。
     * 同一份旧 payload 无论执行多少次，都应产生相同结果。</p>
     *
     * @param payload 不可变的旧状态，不包含其他 Workflow 的命名空间
     * @return 新 Map；不能返回 null，也不能包含 null key/value
     */
    Map<String, String> migrate(Map<String, String> payload);
}
