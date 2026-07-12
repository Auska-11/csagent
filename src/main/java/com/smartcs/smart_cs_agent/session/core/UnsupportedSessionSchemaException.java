package com.smartcs.smart_cs_agent.session.core;

/** 当前代码无法读取数据库中的会话快照版本。 */
public final class UnsupportedSessionSchemaException extends RuntimeException {

    public UnsupportedSessionSchemaException(int actualVersion, int supportedVersion) {
        super("Unsupported session schemaVersion " + actualVersion + ", supported " + supportedVersion);
    }
}
