# Smart CS React Frontend

新的客服 SaaS 控制台使用 React、TypeScript、Vite 和 Hash Router。旧页面仍保留在
`src/main/resources/static/index.html` 与 `app.css` 作为源码回退；默认 Maven 构建会把
React 产物发布到 Spring Boot 的静态目录。

## 本地开发

先启动 Spring Boot（默认 `http://127.0.0.1:8080`），再启动 Vite：

```bash
./mvnw spring-boot:run
cd frontend
npm ci
npm run dev
```

Vite 会把 `/api` 和 `/actuator` 同源代理到 8080 端口。访问
`http://127.0.0.1:5173/#/workspace`。

## 验证

```bash
cd frontend
npm run typecheck
npm test
npm run build
```

## Spring Boot 静态托管

打包时默认会启用 `frontend` Maven profile（显式写出也兼容旧脚本）：

```bash
./mvnw package
java -jar target/smart-cs-agent-0.0.1-SNAPSHOT.jar
```

Profile 会执行 `npm ci`、`npm run build`，再把 `frontend/dist` 叠加到
`target/classes/static`。它不会覆盖仓库中的旧静态源码。Docker 构建采用同样的独立前端阶段。

路由使用 Hash 模式，刷新管理详情不需要 Spring MVC fallback：

- `/#/workspace`
- `/#/admin/monitor`
- `/#/admin/handoffs`
- `/#/admin/evals`
- `/#/admin/capabilities`

Demo JWT 仅驻留内存；生产必须关闭 Demo issuer 并接入真实身份系统。
