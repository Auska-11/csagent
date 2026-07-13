package com.smartcs.smart_cs_agent.handoff.recovery;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 后台人工工单恢复配置，所有时间单位均为毫秒。 */
@Component
@ConfigurationProperties(prefix = "smartcs.handoff.recovery")
public class PendingHandoffRecoveryProperties {

    /** 默认关闭；确认下游支持 Idempotency-Key 后再在联调/生产环境开启。 */
    private boolean enabled = false;
    private int batchSize = 10;
    private long fixedDelayMs = 5_000;
    private long preparedGraceMs = 30_000;
    private long leaseMs = 30_000;
    private long retryDelayMs = 5_000;
    private long maxRetryDelayMs = 60_000;
    private int maxAttempts = 3;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = requirePositive("batch-size", batchSize);
    }

    public long getFixedDelayMs() {
        return fixedDelayMs;
    }

    public void setFixedDelayMs(long fixedDelayMs) {
        this.fixedDelayMs = requirePositive("fixed-delay-ms", fixedDelayMs);
    }

    public long getLeaseMs() {
        return leaseMs;
    }

    public long getPreparedGraceMs() {
        return preparedGraceMs;
    }

    public void setPreparedGraceMs(long preparedGraceMs) {
        this.preparedGraceMs = requirePositive("prepared-grace-ms", preparedGraceMs);
    }

    public void setLeaseMs(long leaseMs) {
        this.leaseMs = requirePositive("lease-ms", leaseMs);
    }

    public long getRetryDelayMs() {
        return retryDelayMs;
    }

    public void setRetryDelayMs(long retryDelayMs) {
        this.retryDelayMs = requirePositive("retry-delay-ms", retryDelayMs);
    }

    public long getMaxRetryDelayMs() {
        return maxRetryDelayMs;
    }

    public void setMaxRetryDelayMs(long maxRetryDelayMs) {
        this.maxRetryDelayMs = requirePositive("max-retry-delay-ms", maxRetryDelayMs);
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = requirePositive("max-attempts", maxAttempts);
    }

    private int requirePositive(String name, int value) {
        if (value <= 0) {
            throw new IllegalArgumentException("smartcs.handoff.recovery." + name + " must be positive");
        }
        return value;
    }

    private long requirePositive(String name, long value) {
        if (value <= 0) {
            throw new IllegalArgumentException("smartcs.handoff.recovery." + name + " must be positive");
        }
        return value;
    }
}
