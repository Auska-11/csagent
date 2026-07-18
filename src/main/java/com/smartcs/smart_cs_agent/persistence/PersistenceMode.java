package com.smartcs.smart_cs_agent.persistence;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Locale;

/** 保留 smartcs.persistence.mode=memory/jdbc 的稳定外部开关。 */
@Component
public class PersistenceMode {

    private final String value;

    public PersistenceMode(@Value("${smartcs.persistence.mode:memory}") String value) {
        this.value = value == null ? "memory" : value.trim().toLowerCase(Locale.ROOT);
        if (!"memory".equals(this.value) && !"jdbc".equals(this.value)) {
            throw new IllegalArgumentException(
                    "smartcs.persistence.mode must be either memory or jdbc"
            );
        }
    }

    public boolean isJdbc() {
        return "jdbc".equals(value);
    }

    public String value() {
        return value;
    }
}
