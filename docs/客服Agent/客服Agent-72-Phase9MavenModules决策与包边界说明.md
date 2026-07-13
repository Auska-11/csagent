# 客服 Agent Phase 9 Maven Modules 决策与包边界说明

## 1. 决策

Phase 9 决定：**当前继续使用单 Maven Module，不立即拆父 POM 和多个子 Module。**

这不是因为项目规模太小，也不是放弃模块化，而是因为目前真正的发布边界仍是一个 Spring Boot 应用：

- 一个 `pom.xml`；
- 一个可执行 JAR；
- 一个 Agent Docker 镜像；
- Compose 中一个 Agent 服务；
- Controller、Runtime、本地 Provider、MCP Server Adapter 共用同一启动和版本生命周期。

Maven Module 解决的是编译、发布、复用和团队所有权边界，不会自动修复代码耦合。当前先用 Java package、
接口/Port 和架构测试稳定依赖方向，收益更直接。

## 2. 本次审计证据

当前主代码约 298 个 Java 文件，分布在 18 个顶层 package；测试源码约 121 个 Java 文件。业务侧有：

- 9 个 Agent Skill Package；
- 8 个专业业务 Workflow 加 1 个通用兜底 Workflow；
- 9 个稳定 Capability ID；
- 7 个独立 Local Capability Provider。

`pom.xml` 没有 `<modules>`，Dockerfile 只复制 `smart-cs-agent-0.0.1-SNAPSHOT.jar`，Compose 也只运行
一个 Agent 镜像。因此目前没有“某个模块必须单独部署或独立发版”的真实需求。

## 3. 为什么现在强拆反而有风险

源码 import 和 `jdeps` 审计发现，下列顶层包仍存在双向关系：

```text
agent <-> capability
agent <-> session
agent <-> skill
agent <-> workflow
skill <-> workflow
controller <-> security
handoff <-> tools
```

其中一部分是因为多个稳定接口都引用 `ExecutionContext`、`TurnContext` 等共享上下文；另一部分是同一
Spring Boot 应用里的装配和 DTO 位置尚未完全独立。如果直接按当前目录创建 Maven 子 Module，会出现：

1. Maven 循环依赖，无法构建；
2. 为了让 POM 通过而创建过大的 `common` Module；
3. 同一业务改动需要同时修改多个版本号和 POM；
4. Spring 自动扫描、测试 Fixture、资源打包和 Docker 构建显著变复杂；
5. 最终仍只发布一个 JAR，没有得到独立部署收益。

“目录多了”不等于架构更解耦。正确顺序应是先消除反向依赖，再让真实发布需求决定物理拆分。

## 4. 当前怎样保证模块边界

新增 `Phase9PackageBoundaryTest`，在单 Module 内保护四条依赖规则：

1. `agent` 核心不能直接依赖 Repository、Domain、Local Provider、MCP 或 Controller；
2. `workflow` 只能通过 Agent/Capability 契约协作，不能直接访问业务 Adapter；
3. `capability.api` 不能反向依赖 Gateway、幂等、Local Provider、Repository 或 MCP 实现；
4. `mcp` 只能依赖 Capability/安全上下文，不能进入本地业务实现。

Phase 9 的 Legacy 门禁还会保证 ToolManager、ToolRegistry 和旧 Tool Schema 文件不会重新出现。

这些测试的作用不是替代良好设计，而是把已经达成的依赖方向变成自动化规则，防止后续需求悄悄破坏。

## 5. 未来什么时候应该拆

满足以下任一条件时，重新评估 Maven Modules：

- MCP Server 要作为独立进程部署、扩缩容或版本发布；
- 第二个 Agent 应用要复用 Runtime、Workflow SPI 或 Capability Contracts；
- Local Provider 由不同团队维护，需要独立发布节奏；
- 单体构建时间已经明显影响开发效率，并能通过子 Module 增量构建改善；
- 安全或合规要求必须把外部 Adapter 与 Agent Core 物理隔离。

如果只是“文件很多”或“看起来更专业”，不构成拆分理由。

## 6. 未来候选 Module

触发条件满足后，可以按下面顺序演进，而不是一次拆完：

```text
smart-cs-contracts
  ExecutionContext、Capability API、稳定输出 DTO

smart-cs-agent-core
  Runtime、Workflow/Handler SPI、Router

smart-cs-local-adapters
  Domain、Repository、Local Capability Provider

smart-cs-mcp-adapter
  MCP Client/Server、Transport

smart-cs-application
  Spring Boot 启动、Controller、安全装配、最终镜像
```

拆分前必须先把共享上下文移到 contracts，并消除 `agent <-> capability/workflow/skill` 的循环。不能把
所有循环依赖都塞进一个无边界的 `common` Module。

## 7. 对简历和面试的表达

可以这样回答“为什么项目这么大还不拆多 Module”：

> 我区分了逻辑模块和物理发布模块。Phase 9 先删除旧 Skill/Tool 双轨链路，用 Workflow、Handler、
> Capability API 和 Provider 形成稳定包边界，并用架构测试禁止核心依赖 Adapter。依赖审计显示系统
> 目前仍是一个 JAR、一个镜像、一个发布生命周期，而且共享上下文还存在双向引用，所以立即拆 Maven
> Module 只会增加循环依赖和构建成本。等 MCP Server 独立部署或 Agent Core 被第二个应用复用时，
> 再先抽 contracts、后拆 core/adapters/application，收益会更明确。

这个回答比“项目大了就拆 Module”更能体现对模块化成本和发布边界的理解。

## 8. 验证

```bash
sh mvnw -q -Dtest=Phase9PackageBoundaryTest,Phase9LegacyDependencyBoundaryTest test
sh mvnw -q clean test
```

两组架构门禁已通过；加入 4 条 package 边界测试后的干净全量测试共 499 项，失败 0、错误 0、跳过 0。
Docker/MySQL 最终验收将在 Phase 9.5 统一记录。
