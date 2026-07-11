package com.smartcs.smart_cs_agent.skill.loader;

/**
 * Skill manifest 或关联文件加载失败时抛出的统一异常。
 *
 * <p>单独定义异常类型的好处是：启动日志可以快速区分“Skill 配置错误”和普通业务异常；
 * 以后做开发模式热更新时，也可以只捕获这一类异常并保留上一个有效快照。</p>
 */
public class AgentSkillLoadException extends RuntimeException {

    public AgentSkillLoadException(String message) {
        super(message);
    }

    public AgentSkillLoadException(String message, Throwable cause) {
        super(message, cause);
    }
}
