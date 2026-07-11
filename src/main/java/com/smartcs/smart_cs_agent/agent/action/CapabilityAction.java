package com.smartcs.smart_cs_agent.agent.action;

import java.util.Map;

/**
 * 工具调用 Action
 *
 * 【作用】
 * 当 Skill 需要调用工具时，返回这个 Action。
 * 比如查询订单、查询产品、申请退换货等。
 *
 * 【使用场景】
 * 1. order_lookup：查询订单
 * 2. product_search：搜索产品
 * 3. return_apply：申请退换货
 * 4. user_lookup：查询用户信息
 *
 * 【字段说明】
 * - capability：工具名称，标识要调用哪个工具
 * - arguments：参数，传给工具的参数
 *
 * 【执行流程】
 * 1. Workflow 返回 CapabilityAction
 * 2. AgentRuntime 通过 ActionHandlerRegistry 找到 CapabilityActionHandler
 * 3. Handler 校验 Skill allowlist，并调用 CapabilityGateway
 * 4. Gateway 可选择本地 Provider 或远程 MCP Provider
 * 5. Handler 写入 CapabilityObservation，Workflow 根据结果决定下一步
 */
public class CapabilityAction implements AgentAction {

    /** 工具名称：标识要调用哪个工具 */
    private final String capability;

    /** 参数：传给工具的参数 */
    private final Map<String, Object> arguments;

    /**
     * 构造函数
     *
     * @param capability 工具名称，如 order_lookup, product_search
     * @param arguments 参数，如 {order_id: "ORD-001"}
     */
    public CapabilityAction(String capability, Map<String, Object> arguments) {
        this.capability = capability;
        this.arguments = arguments;
    }

    /** 获取工具名称 */
    public String getCapability() {
        return capability;
    }

    /** 获取参数 */
    public Map<String, Object> getArguments() {
        return arguments;
    }

    @Override
    public String toString() {
        return "CapabilityAction{" +
                "capability=" + capability +
                ", arguments=" + arguments +
                "}";
    }
}
