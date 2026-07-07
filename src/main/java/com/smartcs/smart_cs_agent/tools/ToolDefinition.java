package com.smartcs.smart_cs_agent.tools;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具定义。
 *
 * 【为什么需要 ToolDefinition？】
 * 之前 ToolManager 只有一个 switch：
 *
 * switch (capability) { ... }
 *
 * 这样能跑，但工具的“说明、参数、必填字段”都散落在代码里。
 * 如果后续要接 MCP、做工具列表展示、做参数校验，就会比较困难。
 *
 * ToolDefinition 的作用是把一个工具的元信息描述清楚：
 * - 工具叫什么
 * - 工具能做什么
 * - 工具有哪些参数
 * - 参数是什么类型
 * - 哪些参数是必填的
 *
 * 这就是 MCP-style 工具层的第一步：先让工具可描述、可注册、可发现。
 */
public class ToolDefinition {

    /** 工具名称，例如 order_lookup、return_apply */
    private final String name;

    /** 工具说明，给开发者、LLM 或前端调试面板理解这个工具的用途 */
    private final String description;

    /** 参数说明：参数名 -> 参数含义。保留这个字段是为了兼容旧代码和旧文档。 */
    private final Map<String, String> parameters;

    /** 必填参数列表，执行前会做基础校验 */
    private final List<String> requiredParameters;

    /** 参数 schema。相比 parameters，它能表达类型、是否必填和示例值。 */
    private final List<ToolParameterDefinition> parameterSchemas;

    /** 工具输出类型，例如 OrderToolData、ReturnToolData */
    private final String outputType;

    /** 输出字段 schema。用于前端工具面板或后续 MCP tools/list 展示出参结构。 */
    private final List<ToolParameterDefinition> outputSchemas;

    public ToolDefinition(String name,
                          String description,
                          Map<String, String> parameters,
                          List<String> requiredParameters) {
        this.name = name;
        this.description = description;
        this.parameters = parameters == null ? Collections.emptyMap() : Map.copyOf(parameters);
        this.requiredParameters = requiredParameters == null ? List.of() : List.copyOf(requiredParameters);
        this.parameterSchemas = this.parameters.entrySet()
                .stream()
                .map(entry -> ToolParameterDefinition.stringParam(
                        entry.getKey(),
                        entry.getValue(),
                        this.requiredParameters.contains(entry.getKey()),
                        null
                ))
                .toList();
        this.outputType = "object";
        this.outputSchemas = List.of();
    }

    public ToolDefinition(String name,
                          String description,
                          List<ToolParameterDefinition> parameterSchemas) {
        this(name, description, parameterSchemas, "object", List.of());
    }

    public ToolDefinition(String name,
                          String description,
                          List<ToolParameterDefinition> parameterSchemas,
                          String outputType,
                          List<ToolParameterDefinition> outputSchemas) {
        this.name = name;
        this.description = description;
        this.parameterSchemas = parameterSchemas == null ? List.of() : List.copyOf(parameterSchemas);
        this.parameters = buildParameterDescriptionMap(this.parameterSchemas);
        this.requiredParameters = this.parameterSchemas.stream()
                .filter(ToolParameterDefinition::isRequired)
                .map(ToolParameterDefinition::getName)
                .toList();
        this.outputType = outputType == null || outputType.isBlank() ? "object" : outputType;
        this.outputSchemas = outputSchemas == null ? List.of() : List.copyOf(outputSchemas);
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public Map<String, String> getParameters() {
        return parameters;
    }

    public List<String> getRequiredParameters() {
        return requiredParameters;
    }

    public List<ToolParameterDefinition> getParameterSchemas() {
        return parameterSchemas;
    }

    public String getOutputType() {
        return outputType;
    }

    public List<ToolParameterDefinition> getOutputSchemas() {
        return outputSchemas;
    }

    /**
     * 导出近似 JSON Schema 的输入参数结构。
     *
     * 真实 MCP tools/list 里也会返回 inputSchema。
     * 我们这里先用 Map 表示，避免过早引入额外依赖。
     */
    public Map<String, Object> toInputSchema() {
        return toSchema("object", parameterSchemas, requiredParameters);
    }

    /**
     * 给 Jackson / 前端调试接口使用的 getter。
     *
     * 方法内容和 toInputSchema 一样，只是命名成 getInputSchema 后，
     * Spring Boot 返回 JSON 时会自动把它序列化为 inputSchema 字段。
     */
    public Map<String, Object> getInputSchema() {
        return toInputSchema();
    }

    /**
     * 导出工具返回结果结构。
     *
     * 当前还不是完整 JSON Schema，但已经足够让前端和面试演示看清：
     * - 工具返回的是哪个 DTO
     * - 关键字段有哪些
     * - 字段类型和含义是什么
     */
    public Map<String, Object> toOutputSchema() {
        List<String> requiredOutputs = outputSchemas.stream()
                .filter(ToolParameterDefinition::isRequired)
                .map(ToolParameterDefinition::getName)
                .toList();
        String schemaType = outputType.endsWith("[]") ? "array" : "object";
        Map<String, Object> schema = toSchema(schemaType, outputSchemas, requiredOutputs);
        schema.put("dto", outputType);
        return schema;
    }

    public Map<String, Object> getOutputSchema() {
        return toOutputSchema();
    }

    private Map<String, Object> toSchema(String type,
                                         List<ToolParameterDefinition> schemas,
                                         List<String> requiredNames) {
        Map<String, Object> schema = new LinkedHashMap<>();
        Map<String, Object> properties = new LinkedHashMap<>();

        for (ToolParameterDefinition parameter : schemas) {
            Map<String, Object> property = new LinkedHashMap<>();
            property.put("type", parameter.getType());
            property.put("description", parameter.getDescription());

            if (parameter.getExample() != null && !parameter.getExample().isBlank()) {
                property.put("example", parameter.getExample());
            }

            properties.put(parameter.getName(), property);
        }

        schema.put("type", type);
        schema.put("properties", properties);
        schema.put("required", requiredNames == null ? List.of() : requiredNames);
        return schema;
    }

    private Map<String, String> buildParameterDescriptionMap(List<ToolParameterDefinition> parameterSchemas) {
        if (parameterSchemas == null || parameterSchemas.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<String, String> result = new LinkedHashMap<>();
        for (ToolParameterDefinition parameter : parameterSchemas) {
            result.put(parameter.getName(), parameter.getDescription());
        }
        return Collections.unmodifiableMap(result);
    }
}
