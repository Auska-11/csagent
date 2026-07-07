# 客服Agent-18-LLM-as-Judge评测设计

## 1. 为什么需要 LLM-as-Judge

前面我们已经有规则评测：

- 路由评测：看意图、Action、工具是否命中。
- 知识库召回评测：看政策问题是否召回正确文档。
- 回复质量规则评测：看关键词是否覆盖、危险承诺是否被拦截。
- Agent 链路回复评测：真实跑过 Agent / Runtime / Tool，再检查最终回复和 Trace。

这些评测稳定、便宜、可解释，但也有局限：

- 很难判断回复语气是否自然。
- 很难判断开放式回复是否完整。
- 很难判断“基本正确但表达不够好”的中间状态。

LLM-as-Judge 的目标是补充一层语义评测，让模型像评审员一样从多个维度打分。

## 2. 当前第一版取舍

第一版不直接调用真实模型，而是使用 Fake Judge。

原因：

- 默认测试不能依赖网络和 API Key。
- 真实模型输出不稳定，容易让单元测试偶发失败。
- 当前优先验证评测框架是否完整：Prompt、JSON 解析、指标统计、报告输出。

也就是说，当前做的是“可选真实模型评测的框架骨架”。

后续如果要接真实模型，只需要把测试里的 `FakeJudgeModel` 换成真实 `ChatLanguageModel`。

## 3. 四个评分维度

当前 Judge Prompt 要求输出四个维度：

| 维度 | 字段 | 含义 |
| --- | --- | --- |
| 准确性 | `accuracyScore` | 是否忠实于工具结果、知识库和参考事实 |
| 完整性 | `completenessScore` | 是否回答了用户核心问题 |
| 客服语气 | `toneScore` | 是否礼貌、清晰、有安抚感 |
| 安全性 | `safetyScore` | 是否避免危险承诺、越权表达、内部实现泄露 |

此外还有：

- `overallScore`
- `passed`
- `reason`
- `issues`

## 4. 当前文件

新增评测样例：

```text
src/test/resources/eval/llm_judge_eval_cases.json
```

新增评测代码：

```text
src/test/java/com/smartcs/smart_cs_agent/eval/LlmJudgeEvalCase.java
src/test/java/com/smartcs/smart_cs_agent/eval/LlmJudgePromptBuilder.java
src/test/java/com/smartcs/smart_cs_agent/eval/LlmJudgeEvaluator.java
src/test/java/com/smartcs/smart_cs_agent/eval/LlmJudgeResult.java
src/test/java/com/smartcs/smart_cs_agent/eval/LlmAsJudgeEvaluationTest.java
```

评测报告输出：

```text
target/eval-reports/llm-as-judge.json
```

## 5. 样例覆盖

当前第一版有 3 条样例：

- 安全的退货政策回复：应该通过。
- 超过 7 天仍承诺无条件退款：应该不通过。
- 订单回复过于简略：应该不通过。

这些样例不是为了覆盖全部客服表达，而是为了验证 Judge 框架能区分：

- 忠实回复。
- 危险承诺。
- 信息不完整。

## 6. 面试表达

如果面试官问：“你为什么要做 LLM-as-Judge？”

可以这样回答：

> 规则评测适合保护确定性业务链路，比如工具是否命中、关键词是否覆盖、危险承诺是否出现。但客服回复还涉及自然度、完整性和语气，这些很难完全用规则覆盖。所以我补了一层 LLM-as-Judge，把回复按准确性、完整性、客服语气和安全性四个维度打分。当前默认测试先用 Fake Judge 保证稳定，后续可以替换成真实模型作为可选评测。

如果面试官问：“为什么测试里不用真实模型？”

可以这样回答：

> 因为默认 CI 或本地测试应该稳定、便宜、可复现。真实模型依赖网络和 API Key，而且输出有随机性。我的第一版先把 Judge Prompt、JSON 解析和报告结构固定下来，真实模型可以作为可选评测入口，不影响主链路测试稳定性。

## 7. 后续优化

后续可以继续做：

- 增加真实 Judge 模型可选开关，例如 `-Djudge.real=true`。
- 保存真实 Agent 回复作为 candidateReply。
- 扩展 Judge 样本到 20 条以上。
- 对 Judge 输出做更严格的 schema 校验。
- 增加“人工标注结果”和 Judge 结果的一致性对比。
