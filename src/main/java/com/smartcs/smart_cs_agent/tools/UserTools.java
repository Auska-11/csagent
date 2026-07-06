package com.smartcs.smart_cs_agent.tools;

import com.smartcs.smart_cs_agent.model.User;
import com.smartcs.smart_cs_agent.repository.UserRepository;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

/**
 * 用户相关工具类
 *
 * 【功能】根据 token 查询用户信息
 *
 * 【为什么用 token 而不是 userId？】
 * - 真实场景：前端请求会带 token（登录凭证），不是 userId
 * - token 是用户登录后获得的，代表用户身份
 * - 我们的设计：用户登录 -> 获取 token -> 后续请求带 token
 *
 * 【模拟数据】
 * - 张三的 token: token-zhangsan -> USER-001
 * - 李四的 token: token-lisi -> USER-002
 */
@Component
public class UserTools {

    private final UserRepository userRepository;

    public UserTools(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * 根据 token 查询用户信息
     *
     * 【使用场景】
     * - 用户说 "查看我的信息"，但没说 userId
     * - 前端会自动带上 token，我们用 token 识别用户
     *
     * @param token 用户登录凭证，如 token-zhangsan
     * @return 用户基本信息（ID、姓名、手机号）
     */
    @Tool("query user info by token, return user id, name and phone")
    public String queryUserInfo(
            @P("user login token or user id") String token) {

        // 通过 token 查找用户；演示前端当前传 userId，这里兼容两种身份标识。
        User user = userRepository.findByToken(token);
        if (user == null) {
            user = userRepository.findByUserId(token);
        }

        if (user == null) {
            return "User not found, please check the token or user id.";
        }

        // 返回用户信息
        return "User info:\n" +
               "- User ID: " + user.getUserId() + "\n" +
               "- Name: " + user.getName() + "\n" +
               "- Phone: " + user.getPhone();
    }
}
