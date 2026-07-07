# 智能客服 Agent：接口校验与错误响应设计

> 本文档记录聊天接口从“手写空值判断”升级为“Spring Validation + 全局异常处理”的设计。

## 1. 为什么要做接口校验

客服 Agent 的核心链路会调用 LLM 和工具，如果非法请求直接进入 Agent，会带来几个问题：

- 空消息也可能触发模型调用，浪费成本。
- 参数太长可能污染 Prompt 或日志。
- 前端拿到的错误结构不稳定，联调困难。
- 测试时很难判断错误到底来自接口层还是 Agent 层。

所以接口层应该先把明显不合法的请求挡住，让 Agent 主链路只处理“已经满足基本格式”的输入。

## 2. 当前涉及的类

| 类 | 职责 |
| --- | --- |
| `ChatApiRequest` | Controller 层请求 DTO，声明 HTTP JSON 字段和校验规则 |
| `ChatApiController` | 接收请求，将 `ChatApiRequest` 转换成 Agent 内部 `ChatRequest` |
| `ApiErrorResponse` | 统一错误响应结构 |
| `GlobalExceptionHandler` | 把不同异常统一转换成稳定 HTTP 错误响应 |

这里要注意：`ChatApiRequest` 和 `ChatRequest` 不是重复设计。

- `ChatApiRequest` 面向外部 HTTP 接口。
- `ChatRequest` 面向 Agent 内部执行链路。

分开以后，后续接口字段变更、校验规则调整、兼容旧字段，都不会直接污染 Agent 核心模型。

## 3. 当前校验规则

`ChatApiRequest` 当前使用了 Spring Validation：

```java
@NotBlank(message = "Message cannot be empty")
@Size(max = 1000, message = "Message is too long")
private String message;

@Size(max = 64, message = "UserId is too long")
private String userId;

@Size(max = 128, message = "ConversationId is too long")
private String conversationId;
```

含义：

- `message` 不能为空，也不能全是空格。
- `message` 最长 1000 字符，避免异常长输入。
- `userId` 和 `conversationId` 允许不传，但传了就不能过长。

## 4. 错误响应格式

统一错误响应使用 `ApiErrorResponse`：

```json
{
  "errorCode": "VALIDATION_ERROR",
  "message": "Message cannot be empty",
  "timestamp": 1783350000000
}
```

字段说明：

- `errorCode`：程序可读，前端可以据此做不同处理。
- `message`：人可读，方便调试或展示。
- `timestamp`：错误发生时间，方便排查。

## 5. 当前错误码

| 错误码 | HTTP 状态码 | 触发场景 |
| --- | --- | --- |
| `VALIDATION_ERROR` | 400 | `@Valid` 校验失败，例如消息为空或字段过长 |
| `INVALID_REQUEST_BODY` | 400 | 请求体不是合法 JSON |
| `BAD_REQUEST` | 400 | Controller 或业务代码主动抛出 `IllegalArgumentException` |
| `INTERNAL_ERROR` | 500 | 未预料到的系统异常 |

注意：未预料到的系统异常不会把内部异常信息直接返回给用户，避免泄露敏感细节。

## 6. 为什么 Controller 里还保留兜底判断

真实 HTTP 请求会在进入 Controller 方法前触发 `@Valid`。

但单元测试或内部代码直接调用 `sendMessage(...)` 时，不会自动触发 Spring Validation。

所以 `ChatApiController` 里保留了一层很轻的兜底判断：

```java
if (message == null || message.isBlank()) {
    throw new IllegalArgumentException("Message cannot be empty");
}
```

这不是和 `@NotBlank` 冲突，而是为了防止绕过 Web 层时非法请求进入 Agent。

## 7. 测试覆盖

当前新增和调整了三类测试：

| 测试类 | 目的 |
| --- | --- |
| `ChatApiControllerTest` | 不启动 Spring，直接验证 DTO 到 Agent Request 的转换，以及直接调用时的兜底判断 |
| `ChatApiControllerMvcTest` | 启动轻量 Web 层，验证 `@Valid`、`@RequestBody` 和全局异常处理是否按 HTTP 方式工作 |
| `GlobalExceptionHandlerTest` | 直接验证不同异常会被包装成稳定的 `ApiErrorResponse` |

其中 MVC 测试会验证：

- 空白消息返回 HTTP 400。
- 错误码为 `VALIDATION_ERROR`。
- 校验失败时不会调用 `CustomerServiceAgent`。
- 合法请求能正常返回 `ChatResponse`。

## 8. 面试表达

如果面试官问：“为什么要单独做接口校验？”

可以这样回答：

> Agent 链路后面会调用工具和模型，所以我把输入校验放在 Controller DTO 层，先拦截空消息、超长字段和非法 JSON。这样 Agent 主链路只处理基本合法的请求，避免浪费模型调用，也让前端拿到稳定的错误结构。

如果面试官问：“为什么不用 Map 接收请求？”

可以这样回答：

> Map 虽然方便，但字段边界不清楚，也不适合加校验注解。我改成了 `ChatApiRequest` DTO，用 `@NotBlank` 和 `@Size` 声明接口契约，再由 Controller 转换成 Agent 内部的 `ChatRequest`。这样外部接口和内部执行模型是解耦的。

如果面试官问：“你怎么保证接口错误返回稳定？”

可以这样回答：

> 我新增了 `GlobalExceptionHandler`，统一处理 JSON 解析失败、参数校验失败、业务参数异常和未预期异常。所有错误都会包装成 `ApiErrorResponse`，包含 `errorCode`、`message` 和 `timestamp`，并且有 MVC 测试覆盖真实 HTTP 行为。

## 9. 后续可优化

后续可以继续增强：

1. 错误码进一步细分，例如认证失败、权限不足、资源不存在。
2. 增加 `requestId`，方便日志和前端错误定位。
3. 对 `userId`、`conversationId` 增加格式校验，例如只允许字母、数字、短横线和下划线。
4. 把错误响应和 Trace 关联起来，让调试面板能看到请求失败原因。

当前阶段已经完成接口 DTO、基础校验、统一错误响应和 Web 层测试，足够支撑简历项目里的“工程化接口边界”表达。
