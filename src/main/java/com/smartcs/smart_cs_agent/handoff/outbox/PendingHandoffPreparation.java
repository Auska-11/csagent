package com.smartcs.smart_cs_agent.handoff.outbox;

/**
 * 调用前准备结果。
 *
 * @param record Outbox 当前记录
 * @param created 本次调用是否真正创建了记录；只有 created=true 的同步执行者可以首次调用远端
 */
public record PendingHandoffPreparation(PendingHandoffRecord record, boolean created) {
}
