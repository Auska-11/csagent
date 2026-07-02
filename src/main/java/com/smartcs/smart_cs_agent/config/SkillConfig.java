package com.smartcs.smart_cs_agent.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Skill 配置加载器
 *
 * 【这个类的作用】
 * 从文件加载 System Prompt，实现配置外部化。
 *
 * 【为什么要外部化？】
 * 之前：System Prompt 写死在代码里（@SystemMessage 注解）
 * 问题：改 Prompt 要改代码、重新编译、重新部署
 * 现在：Prompt 放在文件里，改文件就行，不用动代码
 *
 * 【@Value("classpath:prompts/system-prompt.md")】
 * Spring 的资源注入语法：
 * - classpath: 表示从 classpath（即 src/main/resources）加载
 * - prompts/system-prompt.md 是文件路径
 *
 * 【Resource 接口】
 * Spring 的资源抽象，可以读取文件内容。
 * getContentAsString() 直接返回文件内容为字符串。
 *
 * 【异常处理】
 * 如果文件读取失败，返回默认的 Prompt，保证程序不会崩溃。
 */
@Component
public class SkillConfig {

    /**
     * 注入 System Prompt 文件资源
     *
     * @Value 注解会自动把文件加载到 Resource 对象
     * 文件位置：src/main/resources/prompts/system-prompt.md
     */
    @Value("classpath:prompts/system-prompt.md")
    private Resource systemPromptResource;

    /**
     * 获取 System Prompt
     *
     * @return System Prompt 内容（字符串）
     */
    public String getSystemPrompt() {
        try {
            // 读取文件内容，使用 UTF-8 编码
            return systemPromptResource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            // 文件读取失败，返回默认 Prompt
            // 这样即使文件丢失，程序也能正常运行
            return "You are a professional customer service assistant named XiaoZhi.";
        }
    }
}