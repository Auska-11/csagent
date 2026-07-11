package com.smartcs.smart_cs_agent.skill.loader;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Agent Skill 资源来源配置。
 *
 * <p>默认使用 classpath，适合把稳定 Skill 随应用一起发布。开发或部署环境也可以配置：</p>
 * <pre>
 * smartcs.skill.source=external-directory
 * smartcs.skill.external-directory=/opt/smartcs/skills
 * </pre>
 *
 * <p>若需要临时回滚到旧的统一 System Prompt，可设置 source=legacy；此时 Loader 返回空列表，
 * SkillManager 会继续读取原有 {@code prompts/system-prompt.md}。</p>
 */
@Component
@ConfigurationProperties(prefix = "smartcs.skill")
public class AgentSkillProperties {

    /** 支持 classpath、external-directory、legacy。 */
    private String source = "classpath";

    /** source=external-directory 时的根目录，每个直接子目录是一份 Skill Package。 */
    private String externalDirectory = "";

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getExternalDirectory() {
        return externalDirectory;
    }

    public void setExternalDirectory(String externalDirectory) {
        this.externalDirectory = externalDirectory;
    }
}
