package com.smartcs.smart_cs_agent.workflow.api;

import com.smartcs.smart_cs_agent.agent.context.TurnContext;

/**
 * 可执行任务工作流（Executable Workflow）的最小扩展协议。
 *
 * <p>职责：</p>
 * <ul>
 *     <li>说明自己能处理哪类任务。</li>
 *     <li>读取当前请求、会话状态和 Observation。</li>
 *     <li>决定下一步 Action，但不亲自执行 Action。</li>
 * </ul>
 *
 * <p>不负责：不直接访问 Repository、不直接发 HTTP、不直接调用 MCP，也不维护全局 Router。</p>
 *
 * <p>调用链：AgentRuntime -> WorkflowRouter -> AgentWorkflow -> WorkflowDecision ->
 * ActionHandler。Phase 9 起所有实现都直接遵守本协议，不再通过旧 Java Skill Adapter 执行。</p>
 */
public interface AgentWorkflow {

    /**
     * 返回 Workflow 的稳定元数据，供注册、路由、版本检查和 Trace 使用。
     */
    WorkflowDescriptor descriptor();

    /**
     * 评估当前请求是否适合由本 Workflow 处理。
     *
     * <p>该方法必须是无副作用的：不能调用工具、写数据库或修改会话状态。</p>
     */
    WorkflowMatchResult match(RoutingContext context);

    /**
     * 作为活跃 Workflow 时，是否愿意接手这条新消息。
     *
     * <p>默认 true，维持多轮粘性：Router 恢复活跃 Workflow 前会先询问一次。只有能明确判断
     * “这句话不属于我的任务”的实现才应返回 false，例如故障排查正在等补充型号，买家却发起
     * 砍价新问题；此时 Router 会取消本 Workflow 并按首评逻辑对完整消息重新路由。</p>
     *
     * <p>与 match() 一样必须无副作用，且只应在“悬挂等待用户补充”这类可判定场景拒接，
     * 否则会破坏槽位跨轮粘性（例如用户第二轮只回复 ORD-003）。</p>
     */
    default boolean acceptsMessage(RoutingContext context) {
        return true;
    }

    /**
     * 根据当前轮上下文决定下一步动作。
     *
     * <p>方法可以更新属于本 Workflow 的任务状态，但真正的 LLM/Capability 执行交给 Runtime。</p>
     */
    WorkflowDecision nextAction(TurnContext context);
}
