package com.smartcs.smart_cs_agent.handoff.sla;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * SLA 平台 v1 集成配置（建单客户端 + 结果回调接收端共用）。
 *
 * <p>对应契约 docs/31-seller-agent-integration-v1.md：Agent 用独立服务账号调用 SLA，
 * 回调用独立静态令牌。凭据只经部署配置注入，不写日志、不入库。</p>
 */
@Component
@ConfigurationProperties(prefix = "smartcs.handoff.sla-v1")
public class SlaV1Properties {

    /** SLA 平台地址（本地联调可用 HTTP，远程必须 HTTPS）。 */
    private String baseUrl = "http://localhost:8081";

    /** SLA 配置式 Agent 服务账号用户名（app.integration.v1.agent-users.<name>）。 */
    private String username;

    /** 服务账号密码；只经环境变量或部署配置注入。 */
    private String password;

    /** 渠道卖家账号，必须与服务端登录主体绑定的 sellerAccountId 一致，否则 403。 */
    private String sellerAccountId;

    /** 回调 Bearer 令牌；与 SLA 侧 app.integration.v1.agent-users.<name>.callback-token 相同。 */
    private String callbackToken;

    /** 回调令牌绑定的租户；payload.tenantId 与之不符返回 403。 */
    private String callbackTenantId;

    private int connectTimeoutMs = 3000;

    private int readTimeoutMs = 10000;

    /** 建单调用前的时钟提前量：令牌距到期不足该秒数时即重新登录。 */
    private int tokenExpirySkewSeconds = 30;

    /**
     * 回调接收端是否启用：令牌与租户绑定都配置齐全才启用，否则端点按 404 处理，
     * 让 SLA 侧进入可见的重试/人工状态，而不是静默丢弃结果。
     */
    public boolean isCallbackEnabled() {
        return callbackToken != null && !callbackToken.isBlank()
                && callbackTenantId != null && !callbackTenantId.isBlank();
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getSellerAccountId() {
        return sellerAccountId;
    }

    public void setSellerAccountId(String sellerAccountId) {
        this.sellerAccountId = sellerAccountId;
    }

    public String getCallbackToken() {
        return callbackToken;
    }

    public void setCallbackToken(String callbackToken) {
        this.callbackToken = callbackToken;
    }

    public String getCallbackTenantId() {
        return callbackTenantId;
    }

    public void setCallbackTenantId(String callbackTenantId) {
        this.callbackTenantId = callbackTenantId;
    }

    public int getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public void setConnectTimeoutMs(int connectTimeoutMs) {
        this.connectTimeoutMs = positive("connect-timeout-ms", connectTimeoutMs);
    }

    public int getReadTimeoutMs() {
        return readTimeoutMs;
    }

    public void setReadTimeoutMs(int readTimeoutMs) {
        this.readTimeoutMs = positive("read-timeout-ms", readTimeoutMs);
    }

    public int getTokenExpirySkewSeconds() {
        return tokenExpirySkewSeconds;
    }

    public void setTokenExpirySkewSeconds(int tokenExpirySkewSeconds) {
        this.tokenExpirySkewSeconds = positive("token-expiry-skew-seconds", tokenExpirySkewSeconds);
    }

    private static int positive(String name, int value) {
        if (value <= 0) {
            throw new IllegalArgumentException(
                    "smartcs.handoff.sla-v1." + name + " must be greater than 0"
            );
        }
        return value;
    }
}
