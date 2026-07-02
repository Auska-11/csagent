package com.smartcs.smart_cs_agent.model;

/** * 用户实体类
 * 对应数据库中的用户表
 */
public class User {

    private String userId;    // 用户ID
    private String name;      // 用户名
    private String phone;     // 手机号
    private String token;     // 登录令牌（用于识别用户身份）

    public User(String userId, String name, String phone, String token) {
        this.userId = userId;
        this.name = name;
        this.phone = phone;
        this.token = token;
    }

    // Getter 方法
    public String getUserId() { return userId; }
    public String getName() { return name; }
    public String getPhone() { return phone; }
    public String getToken() { return token; }

    @Override
    public String toString() {
        return "用户{" +
               "userId='" + userId + '\'' +
               ", name='" + name + '\'' +
               ", phone='" + phone + '\'' +
               '}';
    }
}