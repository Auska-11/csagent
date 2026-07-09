package com.smartcs.smart_cs_agent.tools;

import java.util.List;

/**
 * 文本向量化客户端。
 *
 * 真实运行时由 OpenAI-compatible embedding API 实现；
 * 单元测试里可以用假实现，避免测试依赖外部模型服务。
 */
public interface TextEmbeddingClient {

    List<Double> embed(String text);
}
