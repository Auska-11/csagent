package com.smartcs.smart_cs_agent.channel.xianyu;

public class XianyuProfileConflictException extends RuntimeException {
    public XianyuProfileConflictException() { super("Item profile has changed; reload before saving"); }
}
