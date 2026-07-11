package com.smartcs.smart_cs_agent.agent.context;

/**
 * 一个会话中当前 Workflow 的生命周期状态。
 *
 * <p>Router 只会“粘住”非终态 Workflow：</p>
 * <ul>
 *     <li>ACTIVE：本轮正在执行；</li>
 *     <li>WAITING_USER：已经追问，等待下一轮用户补充；</li>
 *     <li>COMPLETED/CANCELLED/HANDED_OFF/FAILED：任务已经结束，下一轮应重新路由。</li>
 * </ul>
 */
public enum WorkflowStatus {
    ACTIVE(false),
    WAITING_USER(false),
    COMPLETED(true),
    CANCELLED(true),
    HANDED_OFF(true),
    FAILED(true);

    private final boolean terminal;

    WorkflowStatus(boolean terminal) {
        this.terminal = terminal;
    }

    public boolean isTerminal() {
        return terminal;
    }
}
