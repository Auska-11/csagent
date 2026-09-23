package com.smartcs.smart_cs_agent.handoff.sla;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * requestId 映射的契约约束：稳定性（同一租户同一操作永远同值）、隔离性（跨租户同键不碰撞）、
 * 格式合法性（1–128 个 ASCII 字母/数字/点/下划线/短横线，禁止截断内部键）。
 */
class SlaV1RequestIdsTest {

    private static final String LONG_INTERNAL_KEY = "agent-handoff-" + "k".repeat(240);

    @Test
    void shouldDeriveStableContractCompliantRequestId() {
        String first = SlaV1RequestIds.derive("tenant-a", LONG_INTERNAL_KEY);
        String second = SlaV1RequestIds.derive("tenant-a", LONG_INTERNAL_KEY);

        assertEquals(first, second);
        assertTrue(first.startsWith(SlaV1RequestIds.PREFIX));
        // wi-v1- (6) + 64 位十六进制 = 70，不截断内部 247 字符长键也保持契约长度。
        assertEquals(70, first.length());
        assertTrue(SlaV1RequestIds.isValid(first));
    }

    @Test
    void shouldIsolateTenantsAndOperations() {
        String tenantA = SlaV1RequestIds.derive("tenant-a", "key-1");
        String tenantB = SlaV1RequestIds.derive("tenant-b", "key-1");
        String otherOperation = SlaV1RequestIds.derive("tenant-a", "key-2");

        assertNotEquals(tenantA, tenantB, "跨租户同键必须映射到不同事项");
        assertNotEquals(tenantA, otherOperation);
    }

    @Test
    void shouldTrimTenantButKeepKeyVerbatim() {
        assertEquals(
                SlaV1RequestIds.derive("tenant-a", "key-1"),
                SlaV1RequestIds.derive("  tenant-a  ", "key-1")
        );
        assertNotEquals(
                SlaV1RequestIds.derive("tenant-a", "key-1"),
                SlaV1RequestIds.derive("tenant-a", "key-1 ")
        );
    }

    @Test
    void shouldRejectBlankInputs() {
        assertThrows(IllegalArgumentException.class, () -> SlaV1RequestIds.derive(null, "key"));
        assertThrows(IllegalArgumentException.class, () -> SlaV1RequestIds.derive("  ", "key"));
        assertThrows(IllegalArgumentException.class, () -> SlaV1RequestIds.derive("tenant", null));
        assertThrows(IllegalArgumentException.class, () -> SlaV1RequestIds.derive("tenant", ""));
    }

    @Test
    void shouldValidateExternalRequestIdFormat() {
        assertTrue(SlaV1RequestIds.isValid("wi-v1-abc"));
        assertTrue(SlaV1RequestIds.isValid("a".repeat(128)));
        assertTrue(SlaV1RequestIds.isValid("A.b_C-9"));

        // 回调载荷里的 requestId 不符合契约格式时按非法处理，不能进存储。
        assertEquals(false, SlaV1RequestIds.isValid(null));
        assertEquals(false, SlaV1RequestIds.isValid(""));
        assertEquals(false, SlaV1RequestIds.isValid("a".repeat(129)));
        assertEquals(false, SlaV1RequestIds.isValid("wi v1"));
        assertEquals(false, SlaV1RequestIds.isValid("wi-v1-中文"));
        assertEquals(false, SlaV1RequestIds.isValid("wi-v1-abc/1"));
    }
}
