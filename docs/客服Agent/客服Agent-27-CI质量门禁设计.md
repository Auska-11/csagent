# 客服Agent-27-CI质量门禁设计

## 1. 为什么要加 CI

项目已经有比较完整的测试和评测体系：

- 单元测试。
- Controller 接口测试。
- 工具注册与调用测试。
- 多轮记忆测试。
- Monitor 反馈闭环测试。
- 路由评测。
- 知识库召回评测。
- 回复质量规则评测。
- Agent 链路回复评测。
- LLM-as-Judge 默认 Fake Judge 评测。

如果这些测试只在本地手动运行，就容易出现一个问题：代码改了，但忘记跑测试，直到面试或演示时才发现某条链路坏了。

CI 的作用就是把测试变成提交后的自动质量门禁：

```text
提交代码 / 提交 PR
-> GitHub Actions 自动拉取代码
-> 安装 Java 17
-> 缓存 Maven 依赖
-> 运行 ./mvnw -B test
-> 测试通过才认为本次改动可信
```

这能说明项目不是只追求功能数量，也重视工程稳定性和可回归验证。

## 2. 工作流位置

```text
.github/workflows/ci.yml
```

当前工作流会在以下场景触发：

- push 到 `main` 或 `master`。
- 向 `main` 或 `master` 发起 Pull Request。

## 3. 工作流做了什么

核心步骤：

1. `actions/checkout@v4` 拉取仓库代码。
2. `actions/setup-java@v4` 安装 Temurin JDK 17。
3. 开启 Maven 依赖缓存，减少重复下载时间。
4. 执行 `./mvnw -B test`。

其中 `-B` 表示 batch mode，适合 CI 环境，日志更稳定，也不会等待交互输入。

## 4. 为什么 CI 不需要真实模型 Key

项目的 `application.yml` 已经给模型配置提供了 dummy 默认值：

```yaml
OPENAI_API_KEY: dummy-test-key
OPENAI_BASE_URL: https://api.openai.com/v1
OPENAI_MODEL_NAME: dummy-model
```

默认测试不会调用真实 LLM-as-Judge，而是使用 Fake Judge 保持稳定。真实模型 Judge 仍然保留为手动脚本：

```powershell
.\scripts\run-real-judge-eval.ps1
```

这样设计的原因是：

- CI 不依赖外部网络质量。
- CI 不消耗真实模型额度。
- CI 不需要保存敏感 API Key。
- 测试结果更稳定，适合做质量门禁。

## 5. 面试怎么讲

可以这样讲：

> 我在项目里补了 GitHub Actions 质量门禁。每次 push 或 PR 都会自动使用 Java 17 运行 Maven 测试，覆盖单元测试、接口测试、工具调用、RAG、Monitor 和 Eval。真实 LLM-as-Judge 不放进默认 CI，而是保留成手动脚本，这样能避免外部模型和网络波动影响基础质量门禁。

这个讲法体现两个能力：

- 你知道如何让项目从“本地能跑”变成“协作开发可验证”。
- 你知道哪些测试适合放进 CI，哪些外部依赖测试应该手动运行。

## 6. 后续可扩展

后续可以继续增强：

- 增加 Docker 镜像构建检查。
- 增加只运行核心 Eval 的快速 smoke test。
- 把 `target/eval-reports/summary.json` 上传为 CI artifact。
- 在 PR 评论中展示 Eval 指标摘要。
- 当项目接入真实数据库后，在 CI 中增加 Testcontainers 集成测试。

当前先保持最小稳定版本：自动运行 `mvn test`，确保核心质量门禁先跑起来。

