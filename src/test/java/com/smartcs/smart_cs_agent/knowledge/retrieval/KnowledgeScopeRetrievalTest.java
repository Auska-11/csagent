package com.smartcs.smart_cs_agent.knowledge.retrieval;

import com.smartcs.smart_cs_agent.knowledge.model.KnowledgeChunk;
import com.smartcs.smart_cs_agent.knowledge.model.KnowledgeQueryPlan;
import com.smartcs.smart_cs_agent.knowledge.model.KnowledgeType;
import com.smartcs.smart_cs_agent.tools.KnowledgeVectorStore;
import com.smartcs.smart_cs_agent.tools.RagProperties;
import com.smartcs.smart_cs_agent.tools.VectorKnowledgeHit;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KnowledgeScopeRetrievalTest {
    @Test
    void staleOrUnfilteredVectorHitsCannotCrossChannelOrItemModelBoundaries() {
        List<KnowledgeChunk> chunks = List.of(chunk("general", "xianyu", ""),
                chunk("current", "xianyu", "M2"), chunk("other", "xianyu", "Pro Max"),
                chunk("demo", "", "M2"));
        KnowledgeVectorStore store = mock(KnowledgeVectorStore.class);
        // Deliberately ignore the requested server-side filter to simulate a stale remote index.
        when(store.search(any(), anyInt(), any())).thenReturn(chunks.stream()
                .map(chunk -> new VectorKnowledgeHit(chunk.documentId(), "troubleshooting", "guide", "text", 0.99))
                .toList());
        RagProperties properties = new RagProperties();
        properties.setMode("hybrid");
        var retriever = new QdrantVectorRetriever(chunks, text -> List.of(0.1, 0.2), store, properties);
        var plan = new KnowledgeQueryPlan("蓝牙连不上", KnowledgeType.TROUBLESHOOTING,
                "headphones", "M2", "", List.of("蓝牙连不上"), List.of("蓝牙"), "xianyu");
        assertEquals(List.of("general", "current"), retriever.retrieve(plan, 10).candidates().stream()
                .map(candidate -> candidate.chunk().documentId()).toList());
    }

    private KnowledgeChunk chunk(String id, String channel, String model) {
        return new KnowledgeChunk(id, id + "#chunk-1", KnowledgeType.TROUBLESHOOTING, "troubleshooting",
                "headphones", model, "guide", "project", "1", null, "text", List.of("蓝牙"), channel);
    }
}
