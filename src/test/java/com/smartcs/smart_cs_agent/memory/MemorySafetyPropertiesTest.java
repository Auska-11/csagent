package com.smartcs.smart_cs_agent.memory;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

/** Memory 容量或 TTL 配成 0 会退化成无边界/立即失效，因此启动时直接拒绝。 */
class MemorySafetyPropertiesTest {

    @Test
    void shouldRejectNonPositiveSafetyLimits() {
        MemorySafetyProperties properties = new MemorySafetyProperties();

        assertThrows(IllegalArgumentException.class, () -> properties.setPreferenceTtlMs(0));
        assertThrows(IllegalArgumentException.class, () -> properties.setMaxUsers(0));
        assertThrows(IllegalArgumentException.class, () -> properties.setMaxPreferencesPerUser(0));
        assertThrows(IllegalArgumentException.class, () -> properties.setMaxPreferenceLength(0));
        assertThrows(IllegalArgumentException.class, () -> properties.setSummaryMaxLength(0));
    }
}
