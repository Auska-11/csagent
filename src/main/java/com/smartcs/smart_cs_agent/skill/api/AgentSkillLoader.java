package com.smartcs.smart_cs_agent.skill.api;

import java.util.List;

/**
 * Agent Skill Package 的加载端口。
 *
 * <p>Registry 只依赖这个接口，不关心资源来自 classpath、外部目录还是以后可能出现的远程仓库。
 * 因此新增来源时只需增加 Loader/Locator 实现，不需要修改 AgentRuntime。</p>
 */
public interface AgentSkillLoader {

    /**
     * 读取当前配置来源，并返回一次性不可变 Package 快照。
     * 解析或校验失败应抛出明确异常，让应用启动失败，而不是带着半套 Skill 继续运行。
     */
    List<AgentSkillPackage> load();
}
