package com.smartcs.smart_cs_agent.memory;

/** 长期偏好写入后的安全结果，不回传原始未脱敏文本。 */
public record UserMemoryWriteResult(
        boolean accepted,
        boolean added,
        String storedPreference) {

    public UserMemoryWriteResult {
        if (!accepted && (added || storedPreference != null)) {
            throw new IllegalArgumentException("rejected memory result cannot contain stored data");
        }
        if (accepted && (storedPreference == null || storedPreference.isBlank())) {
            throw new IllegalArgumentException("accepted memory result requires a stored preference");
        }
    }

    public static UserMemoryWriteResult rejected() {
        return new UserMemoryWriteResult(false, false, null);
    }

    public static UserMemoryWriteResult accepted(boolean added, String storedPreference) {
        return new UserMemoryWriteResult(true, added, storedPreference);
    }
}
