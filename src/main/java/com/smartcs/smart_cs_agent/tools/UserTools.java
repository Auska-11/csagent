package com.smartcs.smart_cs_agent.tools;

import com.smartcs.smart_cs_agent.model.User;
import com.smartcs.smart_cs_agent.repository.UserRepository;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

/**
 * 用户相关工具类
 *
 * 【Legacy 功能】根据旧 token 或 userId 查询用户信息
 *
 * <p>注意：这个类属于 Phase 9 前保留的旧 ToolRegistry 兼容层。当前生产 user.lookup 已由
 * LocalUserProfileCapabilityProvider 接管，只使用 ExecutionContext 中的 JWT Principal；
 * 本类参数绝不能用于 HTTP 或 MCP 认证。</p>
 */
@Component
public class UserTools {

    private final UserRepository userRepository;

    public UserTools(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * 根据旧 token 或 userId 查询用户信息。
     *
     * @param tokenOrUserId Legacy 查找值；不是当前安全边界的登录凭据
     * @return 用户基本信息（ID、姓名、手机号）
     */
    @Tool("query user info by token, return user id, name and phone")
    public String queryUserInfo(
            @P("legacy token or user id") String tokenOrUserId) {

        User user = userRepository.findByToken(tokenOrUserId);
        if (user == null) {
            user = userRepository.findByUserId(tokenOrUserId);
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

    /**
     * 查询用户结构化数据。
     *
     * 注意：这里不把 token 放进返回数据，避免把登录凭证暴露到 Trace 或前端调试面板。
     */
    public UserToolData queryUserInfoData(String tokenOrUserId) {
        User user = userRepository.findByToken(tokenOrUserId);
        if (user == null) {
            user = userRepository.findByUserId(tokenOrUserId);
        }

        if (user == null) {
            return UserToolData.notFound();
        }

        return UserToolData.found(user);
    }
}
