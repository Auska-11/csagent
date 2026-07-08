# 客服Agent-28-数据库持久化设计

## 1. 为什么要做数据库持久化

前面的版本里，订单、商品、用户、退货记录主要保存在内存 `Map` 中。这样适合学习和单元测试，但面试时很容易被追问：

- 数据重启后会不会丢？
- 真实业务系统里的订单和用户数据从哪里来？
- 你这个 Repository 后续怎么替换成数据库？

所以这一版补了数据库持久化第一版，让项目从“内存 Demo 数据”向“真实业务数据访问层”进一步靠近。

## 2. 第一版持久化范围

第一版先持久化电商客服最核心的业务数据：

- 用户表：`cs_user`
- 商品表：`cs_product`
- 订单表：`cs_order`
- 退货记录表：`cs_return_record`

暂时不迁移：

- `ConversationStore` 会话状态：仍然保留内存模式，后续更适合放 Redis 或 MySQL。
- `UserMemoryStore` 长期偏好记忆：仍然保留内存模式，后续可以单独设计用户画像表。
- `FeedbackEvalCandidateStore`：当前已经支持本地 JSON 文件持久化，后续可以再迁移到数据库。

这样做的原因是先把“业务数据持久化”跑通，不一次性把所有存储都改掉，避免项目复杂度突然变高。

## 3. 双模式设计

项目现在支持两种数据模式：

```text
memory：默认模式，使用内存 Map，适合本地学习、测试、CI。
jdbc：数据库模式，使用 JDBC 连接 MySQL，适合面试演示业务数据持久化。
```

配置项：

```yaml
smartcs:
  persistence:
    mode: ${SMARTCS_PERSISTENCE_MODE:memory}
    jdbc:
      driver-class-name: ${SMARTCS_JDBC_DRIVER:com.mysql.cj.jdbc.Driver}
      url: ${SMARTCS_JDBC_URL:jdbc:mysql://localhost:3306/smart_cs_agent?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai}
      username: ${SMARTCS_JDBC_USERNAME:root}
      password: ${SMARTCS_JDBC_PASSWORD:}
```

默认仍然是 `memory`，所以：

- 不装 MySQL 也可以启动项目。
- 单元测试和 CI 不依赖数据库。
- 面试演示时可以按需切换到 `jdbc`。

## 4. 核心类

### 4.1 `JdbcDatabaseClient`

位置：

```text
src/main/java/com/smartcs/smart_cs_agent/persistence/JdbcDatabaseClient.java
```

它负责：

- 判断是否开启 JDBC 模式。
- 加载数据库驱动。
- 打开数据库连接。
- 执行查询和更新。
- 把 `ResultSet` 映射成业务对象。

这里没有一开始就使用 JPA，是为了让第一版更容易理解。JDBC 能直接看到 SQL 和数据表结构，更适合学习 Repository 如何从内存切到数据库。

### 4.2 Repository 双模式

以下 Repository 已支持双模式：

- `UserRepository`
- `ProductRepository`
- `OrderRepository`
- `ReturnRepository`

每个 Repository 的逻辑类似：

```text
如果 smartcs.persistence.mode=jdbc
-> 建表
-> 写入演示种子数据
-> 查询/保存走数据库

否则
-> 初始化内存 Map
-> 查询/保存走内存
```

## 5. 数据表设计

### 5.1 用户表

```sql
CREATE TABLE IF NOT EXISTS cs_user (
    user_id VARCHAR(64) PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    phone VARCHAR(64),
    token VARCHAR(128) NOT NULL UNIQUE
);
```

### 5.2 商品表

```sql
CREATE TABLE IF NOT EXISTS cs_product (
    product_id VARCHAR(64) PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    price INT NOT NULL,
    stock INT NOT NULL,
    product_desc TEXT
);
```

### 5.3 订单表

```sql
CREATE TABLE IF NOT EXISTS cs_order (
    order_id VARCHAR(64) PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL,
    product_id VARCHAR(64) NOT NULL,
    status VARCHAR(64) NOT NULL,
    tracking_no VARCHAR(128)
);
```

### 5.4 退货记录表

```sql
CREATE TABLE IF NOT EXISTS cs_return_record (
    return_id VARCHAR(64) PRIMARY KEY,
    order_id VARCHAR(64) NOT NULL,
    reason TEXT,
    status VARCHAR(64) NOT NULL
);
```

第一版没有强制加外键，主要为了本地演示和反复启动更简单。后续如果做真实生产设计，可以补外键、索引、唯一约束和迁移工具。

## 6. 如何开启 JDBC 模式

先在 MySQL 中创建数据库：

```sql
CREATE DATABASE smart_cs_agent DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

PowerShell 配置环境变量：

```powershell
$env:SMARTCS_PERSISTENCE_MODE="jdbc"
$env:SMARTCS_JDBC_URL="jdbc:mysql://localhost:3306/smart_cs_agent?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai"
$env:SMARTCS_JDBC_USERNAME="root"
$env:SMARTCS_JDBC_PASSWORD="your-password"
```

启动项目：

```powershell
.\mvnw.cmd spring-boot:run
```

启动时 Repository 会自动建表并写入演示种子数据。

## 7. 验证方式

启动后可以用聊天或 MCP Adapter 验证：

```http
POST /api/mcp/tools/call
Content-Type: application/json

{
  "name": "order_lookup",
  "arguments": {
    "order_id": "ORD-001"
  }
}
```

也可以直接查数据库：

```sql
SELECT * FROM cs_user;
SELECT * FROM cs_product;
SELECT * FROM cs_order;
SELECT * FROM cs_return_record;
```

如果数据库里存在这些演示数据，说明业务 Repository 已经成功从数据库读取数据。

## 8. 面试怎么讲

可以这样讲：

> 项目最初使用内存 Map 模拟订单、商品、用户和退货数据。为了让项目更接近真实业务系统，我给 Repository 层补了 JDBC 持久化第一版。现在数据访问层支持 memory 和 jdbc 两种模式，默认 memory 保证本地测试和 CI 稳定；开启 `SMARTCS_PERSISTENCE_MODE=jdbc` 后，Repository 会自动建表、写入演示种子数据，并从 MySQL 查询业务数据。

继续补充设计取舍：

> 我没有一开始就把所有存储都迁移到数据库，而是先持久化客服场景中最核心的业务数据。会话状态后续更适合放 Redis，用户长期偏好和反馈候选样本可以单独设计表。这样能保持每一步改动可控。

## 9. 后续扩展

后续可以继续做：

- 引入 Flyway 或 Liquibase 管理数据库迁移脚本。
- 为 `user_id`、`product_id`、`order_id` 增加索引和外键。
- 将 `ConversationStore` 迁移到 Redis 或 MySQL。
- 将 `UserMemoryStore` 迁移为用户画像表。
- 将 `FeedbackEvalCandidateStore` 从 JSON 文件迁移到数据库。
- 用连接池替换当前简单 `DriverManager` 连接方式。

当前第一版先解决“业务数据可以从数据库读取和写入”的问题。

