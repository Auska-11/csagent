package com.smartcs.smart_cs_agent.channel.xianyu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/** Platform text only seeds a review draft. It cannot change a published profile or seller authorization. */
@Component
public class XianyuItemSnapshotImporter {
    public static final String STATUS_CREATED = "created";
    public static final String STATUS_EXISTING = "existing";
    public static final String STATUS_SKIPPED = "skipped";
    private final ObjectMapper json = new ObjectMapper();
    private final XianyuItemProfileStore profiles;

    public XianyuItemSnapshotImporter(XianyuItemProfileStore profiles) { this.profiles = profiles; }

    public String importDraft(String itemId, String snapshot) {
        XianyuItemProfileStore.requireId(itemId);
        if (snapshot == null || snapshot.isBlank() || snapshot.length() > 512_000) return STATUS_SKIPPED;
        if (profiles.find(itemId).isPresent()) return STATUS_EXISTING;
        JsonNode data;
        try { data = json.readTree(snapshot); }
        catch (java.io.IOException invalid) { return STATUS_SKIPPED; }
        if (data == null || !data.isObject()) return STATUS_SKIPPED;
        String title = data.path("title").asText("").strip();
        if (title.isBlank()) return STATUS_SKIPPED;
        String description = data.path("desc").asText("").strip();
        if (description.startsWith(title)) description = description.substring(title.length())
                .replaceFirst("^[\\s，。：:、,.;；\\-—]+", "").strip();
        // Category, model, condition and accessories require seller review; do not infer authorization from listing text.
        var draft = new XianyuItemProfileStore.ItemProfile(limit(title, 200), "", "", limit(description, 3000), "", "", null);
        try { profiles.saveDraft(itemId, 0, draft); }
        catch (XianyuProfileConflictException concurrentReview) { return STATUS_EXISTING; }
        return STATUS_CREATED;
    }

    private String limit(String text, int length) { return text.substring(0, Math.min(text.length(), length)); }
}
