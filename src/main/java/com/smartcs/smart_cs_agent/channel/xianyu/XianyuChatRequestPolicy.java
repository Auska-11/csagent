package com.smartcs.smart_cs_agent.channel.xianyu;

import com.smartcs.smart_cs_agent.agent.ChatRequestPolicy;
import com.smartcs.smart_cs_agent.agent.context.ChatRequest;
import com.smartcs.smart_cs_agent.security.AuthorizationException;
import org.springframework.stereotype.Component;

/** Buyer identity in the conversation must agree with the server-issued JWT identity. */
@Component
public class XianyuChatRequestPolicy implements ChatRequestPolicy {
    private final XianyuSidecarProperties properties;
    public XianyuChatRequestPolicy(XianyuSidecarProperties properties) { this.properties = properties; }

    @Override
    public void validate(ChatRequest request) {
        if (!properties.getTenantId().equalsIgnoreCase(request.getTenantId())) return;
        var coordinates = XianyuIdentity.parseConversationId(request.getConversationId())
                .orElseThrow(AuthorizationException::denied);
        if (!XianyuIdentity.syntheticUserId(coordinates.buyerId()).equals(request.getUserId())) {
            throw AuthorizationException.denied();
        }
    }
}
