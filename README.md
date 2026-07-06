# Smart CS Agent

一个面向电商客服场景的 Java Agent 项目，基于 Spring Boot 和 LangChain4j 构建。项目重点不是简单聊天，而是把客服能力拆成可解释、可扩展的 Agent 架构。

## 核心能力

- 订单查询：查询订单状态、商品名和物流单号
- 商品咨询：按关键词搜索商品、查看价格和库存
- 退换货：申请退换货、查询退换货进度
- 用户信息：根据当前用户查询基础资料
- Agent 执行链路：Skill 决策、Runtime 执行、Tools 调用、LLM 总结回复

## 架构亮点

- `CustomerServiceAgent`：业务入口，面向 Controller 暴露 chat 接口
- `AgentRuntime`：执行循环，统一处理 `LLMAction`、`CapabilityAction`、`AskUserAction`、`FinalAnswerAction`
- `CustomerServiceSkill`：客服决策层，先规则识别，再使用 LLM 做语义兜底
- `ToolManager`：业务工具统一调度，屏蔽订单、商品、用户、退换货工具细节
- `Repository`：用内存数据模拟数据库，后续可替换为 MySQL 或 Redis

## 开发计划

后续开发路线见：[`docs/客服Agent开发路线图.md`](docs/客服Agent开发路线图.md)

## 本地运行

先配置模型环境变量，不要把真实 key 写进配置文件：

```powershell
$env:OPENAI_API_KEY="your-api-key"
$env:OPENAI_BASE_URL="https://token-plan-sgp.xiaomimimo.com/v1"
$env:OPENAI_MODEL_NAME="mimo-v2.5-pro"
```

启动项目：

```powershell
.\mvnw.cmd spring-boot:run
```

访问：

```text
http://localhost:8080
```

## 演示用例

- `帮我查一下订单 ORD-001`
- `有什么产品推荐吗`
- `蓝牙耳机多少钱`
- `我想退 ORD-004 这个订单`
- `查一下 RET-001 的状态`
- `查看我的信息`

## 简历表达参考

智能客服 Agent：基于 Spring Boot + LangChain4j 设计并实现电商客服 Agent，采用 Skill/Runtime/Tool 分层架构，将意图识别、工具调用、业务数据查询和回复生成解耦；支持订单查询、商品咨询、退换货和用户信息查询等场景，并通过规则优先 + LLM 兜底的方式降低调用成本、提升可控性。
