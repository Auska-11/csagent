# Smart CS React Frontend

闲鱼卖家工作台与通用客服调试台使用 React、TypeScript、Vite 和 Hash Router。旧页面仍保留在
`src/main/resources/static/index.html` 与 `app.css` 作为源码回退；默认 Maven 构建会把
React 产物发布到 Spring Boot 的静态目录。

## 本地开发

先启动 Spring Boot（默认 `http://127.0.0.1:8080`），再启动 Vite：

```bash
export SMARTCS_SECURITY_JWT_SECRET="$(openssl rand -base64 48)"
export SMARTCS_SECURITY_DEMO_ISSUER_ENABLED=true
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

- `/#/admin/xianyu`（商品审核、授权及人工接管）
- `/#/workspace`（通用商城对话调试）
- `/#/admin/monitor`
- `/#/admin/handoffs`
- `/#/admin/evals`
- `/#/admin/capabilities`

所有服务启动方式都必须提供私有 JWT 密钥。上面的 Demo issuer 仅用于本地工作台，JWT 仅驻留内存；生产必须关闭 Demo issuer 并接入真实身份系统。

## 闲鱼卖家登录

真实工作台在登录页接收身份系统签发的 JWT，经 `/api/auth/me` 验证后仅保存在 React 内存中。JWT 需要 ADMIN 角色，且 `tenant_id` 与 `SMARTCS_XIANYU_TENANT_ID` 一致；浏览器不能使用侧车服务令牌。已验证的闲鱼 ADMIN 从首页进入卖家工作台。

本地开发可显式打开 Demo issuer，并将 `SMARTCS_SECURITY_DEFAULT_TENANT_ID=xianyu` 后直接访问 `/#/admin/xianyu` 检查页面；通用商城脚本需使用 default 租户。Demo issuer 默认关闭。
