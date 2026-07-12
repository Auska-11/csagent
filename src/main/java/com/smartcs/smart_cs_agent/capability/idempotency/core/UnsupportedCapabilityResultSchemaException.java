package com.smartcs.smart_cs_agent.capability.idempotency.core;

/** 遇到未知未来版本时失败关闭，防止用旧代码误读新结构。 */
public class UnsupportedCapabilityResultSchemaException extends RuntimeException {

    public UnsupportedCapabilityResultSchemaException(int actualVersion, int supportedVersion) {
        super("Unsupported capability result schemaVersion " + actualVersion
                + ", supported " + supportedVersion);
    }
}
