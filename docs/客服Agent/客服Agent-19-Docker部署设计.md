# 客服Agent-19-Docker部署设计

## 1. 为什么要做 Docker 部署

这个项目是简历和面试项目，除了“能在本地跑”，还应该能说明如何复现运行环境。

Docker 第一版的目标不是上生产，而是解决三件事：

- 面试演示时可以一条命令启动。
- 不把 API Key 写进镜像或配置文件。
- 让项目看起来更完整，有基本部署意识。

## 2. 当前新增文件

```text
Dockerfile
docker-compose.yml
.dockerignore
.env.example
```

同时更新：

```text
src/main/resources/application.yml
.gitignore
README.md
src/main/java/com/smartcs/smart_cs_agent/controller/HealthCheckController.java
```

## 3. 配置安全

原来模型配置如果直接写在 `application.yml`，会有泄露风险。

当前已经改成环境变量：

```yaml
api-key: ${OPENAI_API_KEY:dummy-test-key}
base-url: ${OPENAI_BASE_URL:https://api.openai.com/v1}
model-name: ${OPENAI_MODEL_NAME:dummy-model}
```

注意：

- `dummy-test-key` 只是为了让本地测试和 Spring 上下文能启动。
- 真正调用模型时必须设置真实环境变量。
- `.env` 已加入 `.gitignore`，只提交 `.env.example`。

## 4. Dockerfile 设计

当前使用两阶段构建：

```text
maven:3.9.9-eclipse-temurin-17  -> 编译打包
eclipse-temurin:17-jre          -> 运行 jar
```

这样做的好处：

- 构建阶段有 Maven。
- 运行阶段只保留 JRE 和 jar，镜像更干净。
- 不需要用户本机安装 Maven 也能构建。

## 5. docker-compose 设计

`docker-compose.yml` 暴露：

```text
8080:8080
```

并注入：

```text
OPENAI_API_KEY
OPENAI_BASE_URL
OPENAI_MODEL_NAME
```

## 6. 启动方式

复制环境变量示例：

```powershell
Copy-Item .env.example .env
```

修改 `.env` 中的真实值后启动：

```powershell
docker compose up --build
```

访问：

```text
http://localhost:8080
```

健康检查：

```text
http://localhost:8080/api/health
```

停止：

```powershell
docker compose down
```

## 7. 健康检查设计

当前新增 `GET /api/health`：

```json
{
  "status": "UP",
  "application": "smart-cs-agent",
  "timestamp": 1783350000000
}
```

这个接口第一版只检查 Spring Boot 服务本身是否能响应 HTTP。

为什么先不检查模型服务？

- 模型服务依赖网络和 API Key，面试演示环境不一定稳定。
- 健康检查如果强依赖外部模型，可能导致“服务已经启动，但因为模型网络波动被误判为不可用”。
- 第一版先保证部署可复现，后续再增加更细的依赖检查。

## 8. 面试表达

如果面试官问：“你这个项目怎么部署？”

可以这样回答：

> 我做了 Docker 第一版部署，使用多阶段构建：第一阶段用 Maven + JDK17 打包，第二阶段用 JRE17 运行 jar。模型 API Key、baseUrl 和模型名都通过环境变量注入，`.env` 不提交，只提交 `.env.example`。这样面试演示时可以用 `docker compose up --build` 一键启动，也避免把敏感配置写进镜像。同时我提供了 `/api/health` 健康检查接口，方便确认服务已经启动。

## 9. 后续优化

后续可以继续补：

- CI 中构建镜像。
- 生产 profile。
- 日志目录挂载。
- Redis/MySQL 等依赖服务编排。
