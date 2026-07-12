package com.smartcs.smart_cs_agent.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Memory 写入前的高风险格式脱敏和 Unicode 长度测试。 */
class SensitiveTextRedactorTest {

    @Test
    void shouldRedactCredentialsEmailPhoneIdAndLongNumbers() {
        String raw = "token=secret-token buyer@example.com 13800138000 "
                + "11010519491231002X 6222021234567890 密码是 dont-store-me";

        String redacted = SensitiveTextRedactor.redact(raw);

        assertFalse(redacted.contains("secret-token"));
        assertFalse(redacted.contains("buyer@example.com"));
        assertFalse(redacted.contains("13800138000"));
        assertFalse(redacted.contains("11010519491231002X"));
        assertFalse(redacted.contains("6222021234567890"));
        assertFalse(redacted.contains("dont-store-me"));
        assertTrue(redacted.contains("[REDACTED]"));
    }

    @Test
    void shouldKeepUnicodeBoundariesAndRecognizeMeaningfulPreference() {
        assertEquals(
                "😀😀😀",
                SensitiveTextRedactor.sanitizeSingleLine("😀😀😀😀", 3)
        );
        assertFalse(SensitiveTextRedactor.hasMeaningfulContent("手机号 [REDACTED]"));
        assertFalse(SensitiveTextRedactor.hasMeaningfulContent("我的手机号是 [REDACTED]"));
        assertFalse(SensitiveTextRedactor.hasMeaningfulContent("我喜欢 [REDACTED]"));
        assertTrue(SensitiveTextRedactor.hasMeaningfulContent("蓝牙耳机 [REDACTED]"));
        assertTrue(SensitiveTextRedactor.hasMeaningfulContent("我喜欢手机，邮箱 [REDACTED]"));
        assertEquals("", SensitiveTextRedactor.sanitizeSingleLine("[REDACTED]", 4));
        assertThrows(
                IllegalArgumentException.class,
                () -> SensitiveTextRedactor.redactAndKeepTail("text", 0)
        );
    }
}
