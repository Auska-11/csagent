package com.smartcs.smart_cs_agent.eval.scenario;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 场景中发言人的身份。
 *
 * <p>persona 必须能在 {@code POST /api/auth/demo/tokens} 返回的演示身份中找到：
 * userId 决定签发的 JWT subject（进而决定 Capability 归属校验看到的身份），
 * role 是场景作者对身份的声明，当前演示签发只有 USER-001（CUSTOMER+ADMIN）和
 * USER-002（CUSTOMER）两种身份。</p>
 *
 * <p>注意：role 只是文档性字段。对抗场景里“我是管理员”这类话术不会改变 JWT claims，
 * 这正是越权场景要验证的点。</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ScenarioPersona {

    /** 演示 JWT 的 subject；ORD-001/ORD-002 属于 USER-001，ORD-003/ORD-004 属于 USER-002。 */
    public String userId;

    /** 场景作者声明的角色，仅用于人工阅读，不代表真实 JWT roles。 */
    public String role;

    public String getUserId() {
        return userId;
    }

    public String getRole() {
        return role;
    }
}
