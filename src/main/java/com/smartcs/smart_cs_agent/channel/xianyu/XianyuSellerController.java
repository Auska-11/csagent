package com.smartcs.smart_cs_agent.channel.xianyu;

import com.smartcs.smart_cs_agent.agent.context.AuthenticatedPrincipal;
import com.smartcs.smart_cs_agent.controller.ApiNotFoundException;
import com.smartcs.smart_cs_agent.security.AuthorizationException;
import com.smartcs.smart_cs_agent.security.SecurityRoles;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;
import java.util.TreeSet;

/** JWT seller workspace; the sidecar service token is never exposed to browsers. One configured seller tenant. */
@RestController
@RequestMapping("/api/admin/xianyu")
public class XianyuSellerController {
    private final XianyuSidecarProperties properties;
    private final XianyuItemProfileStore profiles;
    private final XianyuItemFactService facts;
    private final XianyuManualModeService manual;

    public XianyuSellerController(XianyuSidecarProperties properties, XianyuItemProfileStore profiles,
                                 XianyuItemFactService facts, XianyuManualModeService manual) {
        this.properties = properties;
        this.profiles = profiles;
        this.facts = facts;
        this.manual = manual;
    }

    public record ItemView(String itemId, XianyuItemFactService.ItemFacts facts,
                           XianyuItemProfileStore.Revision profile) { }
    public record DraftRequest(long expectedVersion, XianyuItemProfileStore.ItemProfile profile) { }
    public record PublishRequest(long expectedVersion) { }
    public record ManualRequest(String buyerId, String itemId, Boolean enabled) { }
    public record ManualView(String conversationId, boolean manualMode) { }

    @GetMapping("/items")
    public List<ItemView> items(@AuthenticationPrincipal AuthenticatedPrincipal principal) {
        requireSeller(principal);
        var ids = new TreeSet<>(profiles.itemIds());
        facts.listedItems().forEach(item -> ids.add(item.itemId()));
        return ids.stream().map(this::item).toList();
    }

    @GetMapping("/items/{itemId}")
    public ItemView getItem(@PathVariable String itemId, @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        requireSeller(principal);
        XianyuItemProfileStore.requireId(itemId);
        return item(itemId);
    }

    @PutMapping("/items/{itemId}/draft")
    public XianyuItemProfileStore.Revision draft(@PathVariable String itemId, @RequestBody DraftRequest request,
                                                @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        requireSeller(principal);
        return profiles.saveDraft(itemId, request.expectedVersion(), request.profile());
    }

    @PostMapping("/items/{itemId}/publish")
    public XianyuItemProfileStore.Revision publish(@PathVariable String itemId, @RequestBody PublishRequest request,
                                                  @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        requireSeller(principal);
        return profiles.publish(itemId, request.expectedVersion(), principal.getUserId());
    }

    @GetMapping("/conversations/manual-mode")
    public ManualView mode(@RequestParam String buyerId, @RequestParam String itemId,
                           @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        requireSeller(principal);
        requireCoordinates(buyerId, itemId);
        return new ManualView(XianyuIdentity.conversationId(buyerId, itemId),
                manual.manualMode(buyerId, itemId).orElseThrow(() -> new ApiNotFoundException("Conversation not found")));
    }

    @PostMapping("/conversations/manual-mode")
    public ManualView mode(@RequestBody ManualRequest request, @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        requireSeller(principal);
        requireCoordinates(request.buyerId(), request.itemId());
        if (request.enabled() == null) throw new IllegalArgumentException("enabled is required");
        if (!manual.setManualMode(request.buyerId(), request.itemId(), request.enabled())) {
            throw new ApiNotFoundException("Conversation not found");
        }
        return new ManualView(XianyuIdentity.conversationId(request.buyerId(), request.itemId()), request.enabled());
    }

    private ItemView item(String id) {
        return new ItemView(id, facts.itemFacts(id).orElse(null), profiles.find(id).orElse(null));
    }

    private void requireSeller(AuthenticatedPrincipal principal) {
        if (principal == null || !principal.isAuthenticated() || !principal.hasRole(SecurityRoles.ADMIN)
                || !properties.getTenantId().equals(principal.getTenantId())) throw AuthorizationException.denied();
    }

    private void requireCoordinates(String buyer, String item) {
        if (buyer == null || item == null || !buyer.matches("[0-9]{1,64}") || !item.matches("[0-9]{1,64}")) {
            throw new IllegalArgumentException("buyerId and itemId must be platform numeric IDs");
        }
    }
}
