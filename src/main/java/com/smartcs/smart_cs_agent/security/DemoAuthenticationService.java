package com.smartcs.smart_cs_agent.security;

import com.smartcs.smart_cs_agent.agent.context.AuthenticatedPrincipal;
import com.smartcs.smart_cs_agent.model.User;
import com.smartcs.smart_cs_agent.repository.UserRepository;
import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * 学习项目使用的轻量 token 认证适配器。
 *
 * <p>它通过 UserRepository 验证 token，而不是相信请求体里的 userId。生产环境应替换为 JWT、
 * Session 或网关认证，但 Agent 内部继续只读取 AuthenticatedPrincipal，不需要随认证方案变化。</p>
 */
@Service
public class DemoAuthenticationService {

    private final UserRepository userRepository;

    public DemoAuthenticationService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public AuthenticatedPrincipal authenticate(String claimedUserId, String token) {
        if (token == null || token.isBlank()) {
            return AuthenticatedPrincipal.unverifiedClaim(claimedUserId);
        }

        User user = userRepository.findByToken(token.trim());
        if (user == null) {
            throw AuthenticationException.failed();
        }
        if (claimedUserId != null
                && !claimedUserId.isBlank()
                && !"anonymous".equals(claimedUserId)
                && !claimedUserId.equals(user.getUserId())) {
            // 不向客户端说明 token 实际属于谁，否则会泄露账户是否存在。
            throw AuthenticationException.failed();
        }
        return AuthenticatedPrincipal.authenticated(user.getUserId(), Set.of("CUSTOMER"));
    }
}
