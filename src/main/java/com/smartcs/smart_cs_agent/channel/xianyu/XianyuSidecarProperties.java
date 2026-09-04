package com.smartcs.smart_cs_agent.channel.xianyu;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * 闲鱼侧车接入（第三阶段 X2）的配置。
 *
 * <p>默认关闭：未显式启用时端点按 404 处理，避免暴露任何接入面。启用时必须配置
 * 与侧车 CS_AGENT_TOKEN 一致的服务令牌，令牌长度下限与 JWT 密钥一致（32 字节）。</p>
 */
@Component
@ConfigurationProperties(prefix = "smartcs.xianyu")
public class XianyuSidecarProperties {

    private static final int MIN_SECRET_BYTES = 32;

    /** 是否启用闲鱼侧车会话端点。生产环境不接入闲鱼时应保持 false。 */
    private boolean enabled = false;

    /** 侧车调用 /api/xianyu/sessions 的静态服务令牌；启用时必填且不少于 32 字节。 */
    private String serviceToken = "";

    /** 闲鱼买家合成用户所属租户；X4 将按该租户关闭副作用类能力。 */
    private String tenantId = "xianyu";

    /** 买家 JWT 有效期。侧车收到 401 会自动重注册，短周期不阻碍服务。 */
    private long buyerTokenTtlSeconds = 86_400;

    /** 商品快照与知识文档草稿的落盘目录（不进 classpath，不参与自动索引）。 */
    private String itemSnapshotDir = "data/xianyu-items";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getServiceToken() {
        return serviceToken;
    }

    public void setServiceToken(String serviceToken) {
        this.serviceToken = serviceToken == null ? "" : serviceToken.trim();
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId == null || tenantId.isBlank() ? "xianyu" : tenantId.trim();
    }

    public long getBuyerTokenTtlSeconds() {
        return buyerTokenTtlSeconds;
    }

    public void setBuyerTokenTtlSeconds(long buyerTokenTtlSeconds) {
        if (buyerTokenTtlSeconds <= 0) {
            throw new IllegalArgumentException("buyerTokenTtlSeconds must be greater than 0");
        }
        this.buyerTokenTtlSeconds = buyerTokenTtlSeconds;
    }

    public String getItemSnapshotDir() {
        return itemSnapshotDir;
    }

    public void setItemSnapshotDir(String itemSnapshotDir) {
        this.itemSnapshotDir = itemSnapshotDir == null || itemSnapshotDir.isBlank()
                ? "data/xianyu-items"
                : itemSnapshotDir.trim();
    }

    /** 启用时强制校验服务令牌强度，让配置错误在启动期失败而不是运行期被探测。 */
    @PostConstruct
    void requireValidWhenEnabled() {
        if (enabled && serviceToken.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "smartcs.xianyu.service-token 必须配置且不少于 32 字节（启用闲鱼侧车接入时）"
            );
        }
    }
}
