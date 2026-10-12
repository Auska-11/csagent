package com.smartcs.smart_cs_agent.knowledge.index;

import com.smartcs.smart_cs_agent.knowledge.model.KnowledgeChunk;
import com.smartcs.smart_cs_agent.tools.KnowledgeDocument;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 按 Markdown 二级/三级标题切块；无子标题的旧测试文档仍形成一个块。 */
public class KnowledgeChunker {

    public List<KnowledgeChunk> chunk(List<KnowledgeDocument> documents) {
        List<KnowledgeChunk> chunks = new ArrayList<>();
        for (KnowledgeDocument document : documents) {
            chunks.addAll(chunk(document));
        }
        return List.copyOf(chunks);
    }

    public List<KnowledgeChunk> chunk(KnowledgeDocument document) {
        List<Section> sections = splitSections(document.getContent(), document.getTitle());
        List<KnowledgeChunk> chunks = new ArrayList<>();
        for (int i = 0; i < sections.size(); i++) {
            Section section = sections.get(i);
            List<String> keywords = mergeKeywords(document.getKeywords(), section.content());
            String chunkTitle = section.title().equals(document.getTitle())
                    ? document.getTitle()
                    : document.getTitle() + " / " + section.title();
            chunks.add(new KnowledgeChunk(
                    document.getId(),
                    document.getId() + "#chunk-" + (i + 1),
                    document.getKnowledgeType(),
                    document.getCategory(),
                    document.getProductCategory(),
                    document.getProductModel(),
                    chunkTitle,
                    document.getSource(),
                    document.getVersion(),
                    document.getEffectiveDate(),
                    section.content(),
                    keywords,
                    document.getChannel()
            ));
        }
        return List.copyOf(chunks);
    }

    private List<Section> splitSections(String markdown, String documentTitle) {
        String content = markdown == null ? "" : markdown;
        List<Section> sections = new ArrayList<>();
        String currentTitle = documentTitle;
        StringBuilder body = new StringBuilder();
        boolean sawSectionHeading = false;

        for (String line : content.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("## ") || trimmed.startsWith("### ")) {
                if (sawSectionHeading && !body.toString().isBlank()) {
                    sections.add(new Section(currentTitle, body.toString().trim()));
                }
                currentTitle = trimmed.replaceFirst("^#{2,3}\\s+", "").trim();
                body.setLength(0);
                sawSectionHeading = true;
                continue;
            }
            if (trimmed.startsWith("# ") || isFrontMatterLine(trimmed)) {
                continue;
            }
            body.append(line).append('\n');
        }
        if (!body.toString().isBlank()) {
            sections.add(new Section(currentTitle, body.toString().trim()));
        }
        if (sections.isEmpty()) {
            sections.add(new Section(documentTitle, content.trim()));
        }
        return sections;
    }

    private boolean isFrontMatterLine(String line) {
        String normalized = line.toLowerCase(Locale.ROOT);
        return line.equals("---")
                || normalized.startsWith("channel:")
                || normalized.startsWith("documentid:")
                || normalized.startsWith("knowledgetype:")
                || normalized.startsWith("productcategory:")
                || normalized.startsWith("productmodel:")
                || normalized.startsWith("source:")
                || normalized.startsWith("version:")
                || normalized.startsWith("effectivedate:");
    }

    private List<String> mergeKeywords(List<String> documentKeywords, String content) {
        Set<String> merged = new LinkedHashSet<>(documentKeywords);
        for (String line : content.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("关键词：")) {
                addKeywords(merged, trimmed.substring("关键词：".length()));
            } else if (trimmed.toLowerCase(Locale.ROOT).startsWith("keywords:")) {
                addKeywords(merged, trimmed.substring("keywords:".length()));
            }
        }
        return List.copyOf(merged);
    }

    private void addKeywords(Set<String> target, String raw) {
        for (String keyword : raw.split("[,，、\\s]+")) {
            if (!keyword.isBlank()) {
                target.add(keyword.trim());
            }
        }
    }

    private record Section(String title, String content) {
    }
}
