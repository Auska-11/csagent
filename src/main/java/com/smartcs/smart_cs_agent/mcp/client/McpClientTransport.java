package com.smartcs.smart_cs_agent.mcp.client;

import com.smartcs.smart_cs_agent.agent.context.ExecutionContext;
import com.smartcs.smart_cs_agent.capability.api.CapabilityCall;
import com.smartcs.smart_cs_agent.capability.api.CapabilityDefinition;
import com.smartcs.smart_cs_agent.capability.api.CapabilityResult;

import java.util.Collection;

/**
 * MCP 网络传输端口。Provider 依赖此接口，因此测试可用 Fake Transport，生产使用 HTTP JSON-RPC。
 */
public interface McpClientTransport {

    McpRemoteServerInfo initialize();

    Collection<CapabilityDefinition> listCapabilities();

    CapabilityResult<?> invoke(CapabilityCall call, ExecutionContext context);

    McpTransportHealth health();
}
