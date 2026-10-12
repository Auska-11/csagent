package com.smartcs.smart_cs_agent.channel.xianyu;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartcs.smart_cs_agent.channel.xianyu.XianyuItemFactService.ItemFacts;
import com.smartcs.smart_cs_agent.channel.xianyu.XianyuItemFactService.PriceRange;
import com.smartcs.smart_cs_agent.channel.xianyu.XianyuScenarioProperties.SellerRules;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Seller-reviewed item prose and authorization. Drafts never enter buyer answers before publication. */
@Component
public class XianyuItemProfileStore {
    private final ObjectMapper json = new ObjectMapper();
    private final Path directory;
    private final Map<String, Revision> items = new HashMap<>();

    public XianyuItemProfileStore(XianyuSidecarProperties properties) {
        directory = Path.of(properties.getItemSnapshotDir(), "profiles");
        if (!Files.isDirectory(directory)) return;
        try (var files = Files.list(directory)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".json")).toList()) {
                Revision revision = json.readValue(Files.readString(file), Revision.class);
                requireId(revision.itemId());
                if (!file.getFileName().toString().equals(revision.itemId() + ".json")) {
                    throw new IllegalStateException("Item profile file identity mismatch");
                }
                items.put(revision.itemId(), revision);
            }
        } catch (IOException failure) {
            throw new IllegalStateException("Reviewed item profiles could not be restored", failure);
        }
    }

    public record SellerPolicy(boolean bargainAccepted, BigDecimal floorPrice, BigDecimal allowedDiscount,
                               String freeShippingCondition, String shippingArrangement) {
        public SellerPolicy {
            freeShippingCondition = text(freeShippingCondition, 500);
            shippingArrangement = text(shippingArrangement, 500);
            if (floorPrice != null && floorPrice.signum() <= 0) throw new IllegalArgumentException("floorPrice must be positive");
            if (allowedDiscount != null && allowedDiscount.signum() < 0) throw new IllegalArgumentException("allowedDiscount must be non-negative");
        }

        public SellerRules toRules() {
            SellerRules rules = new SellerRules();
            rules.setBargainAccepted(bargainAccepted);
            rules.setFloorPrice(floorPrice);
            rules.setAllowedDiscount(allowedDiscount);
            rules.setFreeShippingCondition(freeShippingCondition);
            rules.setShippingArrangement(shippingArrangement);
            return rules;
        }
    }

    public record ItemProfile(String title, String productCategory, String productModel, String description,
                              String condition, String accessories, SellerPolicy sellerPolicy) {
        public ItemProfile {
            title = text(title, 200);
            if (title.isBlank()) throw new IllegalArgumentException("title is required");
            productCategory = text(productCategory, 64);
            productModel = text(productModel, 100);
            description = text(description, 3000);
            condition = text(condition, 1000);
            accessories = text(accessories, 1000);
            sellerPolicy = sellerPolicy == null ? new SellerPolicy(false, null, null, "", "") : sellerPolicy;
        }

        ItemFacts toFacts(String id) {
            return new ItemFacts(id, title, PriceRange.unknown(), -1, description, condition,
                    productCategory, productModel, accessories);
        }
    }

    public record Revision(String itemId, long version, ItemProfile draft, ItemProfile published,
                           long updatedAt, String reviewedBy) { }

    public synchronized Optional<Revision> find(String itemId) { return Optional.ofNullable(items.get(itemId)); }
    public synchronized Set<String> itemIds() { return Set.copyOf(items.keySet()); }
    public synchronized Optional<ItemProfile> published(String itemId) { return find(itemId).map(Revision::published); }

    public synchronized Revision saveDraft(String itemId, long expectedVersion, ItemProfile draft) {
        requireId(itemId);
        if (draft == null) throw new IllegalArgumentException("profile is required");
        Revision previous = checked(itemId, expectedVersion);
        return persist(new Revision(itemId, expectedVersion + 1, draft,
                previous == null ? null : previous.published(), System.currentTimeMillis(),
                previous == null ? "" : previous.reviewedBy()));
    }

    public synchronized Revision publish(String itemId, long expectedVersion, String reviewer) {
        Revision previous = checked(itemId, expectedVersion);
        if (previous == null || previous.draft() == null) throw new IllegalArgumentException("No draft to publish");
        return persist(new Revision(itemId, expectedVersion + 1, previous.draft(), previous.draft(),
                System.currentTimeMillis(), text(reviewer, 128)));
    }

    private Revision checked(String itemId, long expectedVersion) {
        requireId(itemId);
        Revision current = items.get(itemId);
        if (expectedVersion < 0 || expectedVersion != (current == null ? 0 : current.version())) {
            throw new XianyuProfileConflictException();
        }
        return current;
    }

    private Revision persist(Revision revision) {
        Path temp = null;
        try {
            Files.createDirectories(directory);
            temp = Files.createTempFile(directory, revision.itemId(), ".tmp");
            Files.writeString(temp, json.writeValueAsString(revision));
            Path destination = directory.resolve(revision.itemId() + ".json");
            try { Files.move(temp, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException unsupported) { Files.move(temp, destination, StandardCopyOption.REPLACE_EXISTING); }
            // Acknowledgment follows persistence; a failed disk write cannot appear as a published profile.
            items.put(revision.itemId(), revision);
            return revision;
        } catch (IOException failure) {
            throw new IllegalStateException("Item profile could not be saved", failure);
        } finally {
            if (temp != null) try { Files.deleteIfExists(temp); } catch (IOException ignored) { }
        }
    }

    public static void requireId(String id) {
        if (id == null || !id.matches("[A-Za-z0-9_-]{1,64}")) throw new IllegalArgumentException("Invalid itemId");
    }

    private static String text(String value, int limit) {
        String result = value == null ? "" : value.strip();
        if (result.length() > limit) throw new IllegalArgumentException("Item profile field exceeds " + limit + " characters");
        return result;
    }
}
