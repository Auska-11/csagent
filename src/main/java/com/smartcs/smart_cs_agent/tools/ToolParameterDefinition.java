package com.smartcs.smart_cs_agent.tools;

/**
 * 工具参数定义。
 *
 * 【为什么要把参数单独建模？】
 * 早期我们只用 Map<String, String> 表示参数说明：
 *
 * order_id -> 订单号，例如 ORD-001
 *
 * 这种写法能看懂，但不够“机器可读”。
 * 如果后续要接 MCP tools/list、前端工具面板、自动参数校验，
 * 系统需要知道参数类型、是否必填、示例值等结构化信息。
 *
 * 这个类就是工具参数 schema 的第一版。
 */
public class ToolParameterDefinition {

    /** 参数名，例如 order_id、query */
    private final String name;

    /** 参数类型。当前工具参数以 string 为主，后续可以扩展 object、number、boolean、array */
    private final String type;

    /** 参数说明，给开发者、LLM 或调试面板理解这个参数的含义 */
    private final String description;

    /** 是否必填。ToolRegistry 会根据这个字段做基础参数校验 */
    private final boolean required;

    /** 示例值，方便生成工具文档或前端调试表单 */
    private final String example;

    public ToolParameterDefinition(String name,
                                   String type,
                                   String description,
                                   boolean required,
                                   String example) {
        this.name = name;
        this.type = type == null || type.isBlank() ? "string" : type;
        this.description = description == null ? "" : description;
        this.required = required;
        this.example = example;
    }

    public static ToolParameterDefinition stringParam(String name,
                                                       String description,
                                                       boolean required,
                                                       String example) {
        return new ToolParameterDefinition(name, "string", description, required, example);
    }

    public String getName() {
        return name;
    }

    public String getType() {
        return type;
    }

    public String getDescription() {
        return description;
    }

    public boolean isRequired() {
        return required;
    }

    public String getExample() {
        return example;
    }
}
