package com.smartcs.smart_cs_agent.knowledge.index;

import com.smartcs.smart_cs_agent.knowledge.model.KnowledgeType;
import com.smartcs.smart_cs_agent.tools.KnowledgeDocument;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 从 classpath Markdown 加载文档级元数据，Chunk 切分由 {@link KnowledgeChunker} 完成。 */
public class MarkdownKnowledgeDocumentLoader {

    public List<KnowledgeDocument> load() {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        try {
            Resource[] resources = resolver.getResources("classpath*:/knowledge/**/*.md");
            List<KnowledgeDocument> documents = new ArrayList<>();
            for (Resource resource : resources) {
                documents.add(read(resource));
            }
            return List.copyOf(documents);
        } catch (IOException e) {
            throw new IllegalStateException("加载知识库 Markdown 文件失败", e);
        }
    }

    private KnowledgeDocument read(Resource resource) throws IOException {
        String content = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> metadata = extractFrontMatter(content);
        String pathId = buildDocumentId(resource);
        String documentId = metadata.getOrDefault("documentid", pathId);
        String category = extractCategory(documentId);
        String title = extractTitle(content, resource.getFilename());
        List<String> keywords = extractKeywords(content, title);
        KnowledgeType defaultType = "troubleshooting".equalsIgnoreCase(category)
                ? KnowledgeType.TROUBLESHOOTING
                : KnowledgeType.POLICY;
        return new KnowledgeDocument(
                documentId,
                category,
                title,
                content,
                keywords,
                KnowledgeType.parse(metadata.get("knowledgetype"), defaultType),
                metadata.getOrDefault("productcategory", ""),
                metadata.getOrDefault("productmodel", ""),
                metadata.getOrDefault("source", "classpath:/knowledge/" + pathId),
                metadata.getOrDefault("version", ""),
                parseDate(metadata.get("effectivedate"))
        );
    }

    private Map<String, String> extractFrontMatter(String content) {
        Map<String, String> metadata = new LinkedHashMap<>();
        String[] lines = content.split("\\R");
        if (lines.length == 0 || !"---".equals(lines[0].trim())) {
            return metadata;
        }
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].trim();
            if ("---".equals(line)) {
                break;
            }
            int colon = line.indexOf(':');
            if (colon > 0) {
                metadata.put(
                        line.substring(0, colon).trim().toLowerCase(Locale.ROOT),
                        line.substring(colon + 1).trim()
                );
            }
        }
        return metadata;
    }

    private String buildDocumentId(Resource resource) throws IOException {
        String path = resource.getURL().toString().replace("\\", "/");
        int index = path.indexOf("/knowledge/");
        if (index >= 0) {
            return path.substring(index + "/knowledge/".length());
        }
        return resource.getFilename() == null ? "unknown.md" : resource.getFilename();
    }

    private String extractCategory(String documentId) {
        int slashIndex = documentId.indexOf("/");
        return slashIndex > 0 ? documentId.substring(0, slashIndex) : "general";
    }

    private String extractTitle(String content, String fallbackName) {
        for (String line : content.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("# ")) {
                return trimmed.substring(2).trim();
            }
        }
        return fallbackName == null ? "未命名知识文档" : fallbackName.replace(".md", "");
    }

    private List<String> extractKeywords(String content, String title) {
        List<String> keywords = new ArrayList<>();
        keywords.add(title);
        for (String line : content.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("## ")) {
                break;
            }
            if (trimmed.startsWith("关键词：")) {
                addKeywords(keywords, trimmed.substring("关键词：".length()));
            } else if (trimmed.toLowerCase(Locale.ROOT).startsWith("keywords:")) {
                addKeywords(keywords, trimmed.substring("keywords:".length()));
            }
        }
        return List.copyOf(keywords);
    }

    private void addKeywords(List<String> target, String raw) {
        for (String keyword : raw.split("[,，、\\s]+")) {
            if (!keyword.isBlank()) {
                target.add(keyword.trim());
            }
        }
    }

    private LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            throw new IllegalStateException("知识文档 effectiveDate 格式必须为 yyyy-MM-dd: " + value, e);
        }
    }
}
