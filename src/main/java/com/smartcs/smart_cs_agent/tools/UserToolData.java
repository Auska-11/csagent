package com.smartcs.smart_cs_agent.tools;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.smartcs.smart_cs_agent.model.User;

/**
 * 用户工具的结构化输出 DTO。
 *
 * 注意：这里故意不返回 token，避免登录凭证出现在 Trace 或前端调试面板里。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class UserToolData {

    private final boolean found;
    private final String userId;
    private final String name;
    private final String phone;

    private UserToolData(boolean found, String userId, String name, String phone) {
        this.found = found;
        this.userId = userId;
        this.name = name;
        this.phone = phone;
    }

    public static UserToolData found(User user) {
        return new UserToolData(true, user.getUserId(), user.getName(), user.getPhone());
    }

    public static UserToolData notFound() {
        return new UserToolData(false, null, null, null);
    }

    @JsonProperty("found")
    public boolean isFound() {
        return found;
    }

    @JsonProperty("user_id")
    public String getUserId() {
        return userId;
    }

    @JsonProperty("name")
    public String getName() {
        return name;
    }

    @JsonProperty("phone")
    public String getPhone() {
        return phone;
    }
}
