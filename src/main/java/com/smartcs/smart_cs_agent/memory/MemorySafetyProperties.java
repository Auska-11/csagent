package com.smartcs.smart_cs_agent.memory;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 长期用户记忆和会话摘要的容量、生命周期与文本长度配置。 */
@Component
@ConfigurationProperties(prefix = "smartcs.memory")
public class MemorySafetyProperties {

    public static final long DEFAULT_PREFERENCE_TTL_MS = 30L * 24 * 60 * 60 * 1_000;
    public static final int DEFAULT_MAX_USERS = 10_000;
    public static final int DEFAULT_MAX_PREFERENCES_PER_USER = 20;
    public static final int DEFAULT_MAX_PREFERENCE_LENGTH = 80;
    public static final int DEFAULT_SUMMARY_MAX_LENGTH = 2_000;

    /** 单条长期偏好最后一次被用户表达后，可以保留多久。 */
    private long preferenceTtlMs = DEFAULT_PREFERENCE_TTL_MS;

    /** 内存实现最多保留多少个用户，防止 Demo 服务被无限 userId 撑满。 */
    private int maxUsers = DEFAULT_MAX_USERS;

    /** 每个用户最多保留多少条商品偏好，超出后淘汰最久未表达的一条。 */
    private int maxPreferencesPerUser = DEFAULT_MAX_PREFERENCES_PER_USER;

    /** 单条偏好脱敏、压缩空白后最多保留多少个 Unicode 字符。 */
    private int maxPreferenceLength = DEFAULT_MAX_PREFERENCE_LENGTH;

    /** Conversation 规则摘要最多保留多少个 Unicode 字符。 */
    private int summaryMaxLength = DEFAULT_SUMMARY_MAX_LENGTH;

    public long getPreferenceTtlMs() {
        return preferenceTtlMs;
    }

    public void setPreferenceTtlMs(long preferenceTtlMs) {
        this.preferenceTtlMs = requirePositive("preferenceTtlMs", preferenceTtlMs);
    }

    public int getMaxUsers() {
        return maxUsers;
    }

    public void setMaxUsers(int maxUsers) {
        this.maxUsers = requirePositive("maxUsers", maxUsers);
    }

    public int getMaxPreferencesPerUser() {
        return maxPreferencesPerUser;
    }

    public void setMaxPreferencesPerUser(int maxPreferencesPerUser) {
        this.maxPreferencesPerUser = requirePositive(
                "maxPreferencesPerUser",
                maxPreferencesPerUser
        );
    }

    public int getMaxPreferenceLength() {
        return maxPreferenceLength;
    }

    public void setMaxPreferenceLength(int maxPreferenceLength) {
        this.maxPreferenceLength = requirePositive("maxPreferenceLength", maxPreferenceLength);
    }

    public int getSummaryMaxLength() {
        return summaryMaxLength;
    }

    public void setSummaryMaxLength(int summaryMaxLength) {
        this.summaryMaxLength = requirePositive("summaryMaxLength", summaryMaxLength);
    }

    private int requirePositive(String propertyName, int value) {
        if (value <= 0) {
            throw new IllegalArgumentException(propertyName + " must be greater than 0");
        }
        return value;
    }

    private long requirePositive(String propertyName, long value) {
        if (value <= 0) {
            throw new IllegalArgumentException(propertyName + " must be greater than 0");
        }
        return value;
    }
}
