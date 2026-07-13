package com.smartcs.smart_cs_agent.controller;

import com.smartcs.smart_cs_agent.capability.api.CapabilityDefinition;
import com.smartcs.smart_cs_agent.capability.api.CapabilityGateway;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 工具调试接口。
 *
 * 【为什么只做 list，不做 call？】
 * MCP 里有 tools/list 和 tools/call 两类能力。
 * 当前阶段我们先暴露只读的 tools/list 风格接口，
 * 让开发者可以看到系统支持哪些工具、每个工具需要哪些参数。
 *
 * 但暂时不开放“通过 HTTP 任意调用工具”的接口，
 * 因为订单、用户、退货等工具属于业务能力，直接暴露执行入口需要额外鉴权和审计。
 */
@RestController
@RequestMapping("/api/tools")
public class ToolApiController {

    private final CapabilityGateway capabilityGateway;

    public ToolApiController(CapabilityGateway capabilityGateway) {
        this.capabilityGateway = capabilityGateway;
    }

    /**
     * 查询当前已注册工具列表。
     *
     * 返回结构接近 MCP tools/list 的思想：
     * - name：工具名
     * - description：工具说明
     * - inputSchema：工具输入参数 schema
     */
    @GetMapping
    public Map<String, Object> listTools() {
        // 调试接口和 MCP tools/list 读取同一份 Capability Catalog，避免维护第二套 ToolDefinition Schema。
        List<CapabilityDefinition> tools = List.copyOf(capabilityGateway.catalog().list());
        return Map.of(
                "count", tools.size(),
                "tools", tools
        );
    }
}
