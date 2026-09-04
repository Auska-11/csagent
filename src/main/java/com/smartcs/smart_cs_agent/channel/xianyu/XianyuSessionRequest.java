package com.smartcs.smart_cs_agent.channel.xianyu;

/** 侧车会话注册请求；itemSnapshot 仅首次携带（JSON 字符串，结构由侧车 build_item_description 决定）。 */
public record XianyuSessionRequest(String buyerId, String itemId, String itemSnapshot) {
}
