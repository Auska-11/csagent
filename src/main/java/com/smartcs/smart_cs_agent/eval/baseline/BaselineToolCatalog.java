package com.smartcs.smart_cs_agent.eval.baseline;

import com.smartcs.smart_cs_agent.capability.api.CapabilityDefinition;
import com.smartcs.smart_cs_agent.mcp.server.McpServerAdapter;
import dev.langchain4j.agent.tool.ToolParameters;
import dev.langchain4j.agent.tool.ToolSpecification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 基线 Function Calling Agent 的工具目录。
 *
 * <p>公平原则 3：工具清单与 schema 直接从 MCP catalog 导出生成——本类读取的
 * {@link McpServerAdapter#listCapabilities()} 正是 {@code GET /api/mcp/tools} 返回的同一份数据，
 * 因此基线看到的工具描述与 Workflow 侧对外声明一字不差，没有任何改写。</p>
 *
 * <p>唯一必要的转换是名称字符映射：OpenAI 函数名只允许 {@code [a-zA-Z0-9_-]}，
 * 而本项目能力 ID 使用 {@code order.lookup} 风格。映射规则为把所有非法字符替换成下划线，
 * 并在构造时校验映射不产生碰撞（碰撞即启动失败，静默错配比失败更糟）。</p>
 */
@Component
public class BaselineToolCatalog {

    private static final Logger LOGGER = LoggerFactory.getLogger(BaselineToolCatalog.class);

    private final McpServerAdapter mcpServerAdapter;

    /** 导出结果；catalog 在本地 Provider 模式下启动后不变，懒加载一次即可。 */
    private volatile ExportedTools exported;

    public BaselineToolCatalog(McpServerAdapter mcpServerAdapter) {
        this.mcpServerAdapter = mcpServerAdapter;
    }

    /** 模型可见的工具清单（name/description/parameters 与 CapabilityDefinition 一致）。 */
    public List<ToolSpecification> toolSpecifications() {
        return export().toolSpecifications();
    }

    /** 工具名 → 能力 ID；未知工具名返回 null，由调用方决定如何反馈给模型。 */
    public String toCapabilityId(String toolName) {
        if (toolName == null) {
            return null;
        }
        return export().toolNameToCapabilityId.get(toolName);
    }

    public int toolCount() {
        return export().toolSpecifications().size();
    }

    private ExportedTools export() {
        ExportedTools current = exported;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (exported != null) {
                return exported;
            }
            List<CapabilityDefinition> definitions = mcpServerAdapter.listCapabilities();
            List<ToolSpecification> specifications = new ArrayList<>(definitions.size());
            Map<String, String> nameToId = new LinkedHashMap<>();
            for (CapabilityDefinition definition : definitions) {
                String toolName = toToolName(definition.getId());
                String previous = nameToId.put(toolName, definition.getId());
                if (previous != null) {
                    throw new IllegalStateException(
                            "工具名映射碰撞：" + previous + " 与 " + definition.getId()
                                    + " 都映射到 " + toolName);
                }
                specifications.add(toToolSpecification(toolName, definition));
            }
            exported = new ExportedTools(List.copyOf(specifications), Map.copyOf(nameToId));
            // 审计日志：对照实验要求两侧工具清单可核对，启动时打印导出结果。
            LOGGER.info("基线 Function Calling 工具目录已从 MCP catalog 导出：count={}, names={}",
                    specifications.size(),
                    nameToId.keySet());
            return exported;
        }
    }

    /** OpenAI 函数名只允许 [a-zA-Z0-9_-]；把能力 ID 中的其余字符替换成下划线。 */
    private String toToolName(String capabilityId) {
        StringBuilder name = new StringBuilder(capabilityId.length());
        for (char ch : capabilityId.toCharArray()) {
            boolean legal = ch == '_' || ch == '-'
                    || (ch >= 'a' && ch <= 'z')
                    || (ch >= 'A' && ch <= 'Z')
                    || (ch >= '0' && ch <= '9');
            name.append(legal ? ch : '_');
        }
        return name.toString();
    }

    private ToolSpecification toToolSpecification(String toolName, CapabilityDefinition definition) {
        Map<String, Object> schema = definition.getInputSchema() == null
                ? Map.of()
                : definition.getInputSchema();
        ToolParameters.Builder parameters = ToolParameters.builder()
                .type("object")
                .properties(stringMaps(schema.get("properties")));
        List<String> required = stringList(schema.get("required"));
        if (!required.isEmpty()) {
            parameters.required(required);
        }
        return ToolSpecification.builder()
                .name(toolName)
                // 描述逐字来自 CapabilityDefinition，不做任何拼接或删改。
                .description(definition.getDescription())
                .parameters(parameters.build())
                .build();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Map<String, Object>> stringMaps(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Map<String, Object>> converted = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() == null || !(entry.getValue() instanceof Map<?, ?> value)) {
                continue;
            }
            converted.put(String.valueOf(entry.getKey()), (Map<String, Object>) value);
        }
        return converted;
    }

    private List<String> stringList(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(String::valueOf).toList();
    }

    private record ExportedTools(List<ToolSpecification> toolSpecifications,
                                 Map<String, String> toolNameToCapabilityId) {
    }
}
