# 智能客服 Agent：Skill 拆分与接口 DTO 设计

> 本文档记录当前阶段的一次小型重构：把 `CustomerServiceSkill` 中的部分职责拆出去，并把聊天接口请求体从 `Map` 改成明确的 DTO。

## 1. 为什么要做这次重构

项目早期最重要的是先跑通主链路，所以很多逻辑会先集中写在 `CustomerServiceSkill` 里：

- 判断用户意图。
- 从文本中提取订单号、退货单号、退货原因。
- 判断是否需要转人工。
- 构造给 LLM 的 Prompt。
- 编排下一步 Action。

这样做适合快速验证，但项目继续变大后会出现两个问题：

- 类越来越长，新手读代码时很难知道“我现在看的这段属于哪种职责”。
- 修改一个小规则时，容易影响到 Action 编排、Prompt 构造等其他逻辑。

所以这次先做第一轮轻量拆分，不追求一步到位，只把最明显的三类职责拆出去。

## 2. 拆分后的类职责

| 类 | 职责 | 可以怎么理解 |
| --- | --- | --- |
| `CustomerServiceSkill` | 编排下一步 Action | 客服大脑，决定下一步做什么 |
| `CustomerServiceSlotExtractor` | 抽取槽位 | 从用户话里找订单号、退货单号、原因、商品关键词 |
| `CustomerServiceIntentRules` | 维护规则 | 判断退货、政策、转人工等关键词规则 |
| `CustomerServicePromptBuilder` | 构造 Prompt | 把上下文整理成 LLM 能稳定理解的文本 |

这次拆分后，`CustomerServiceSkill` 仍然是核心类，但它不再亲自做所有细节。

## 3. 什么是槽位抽取

槽位就是完成一个任务必须收集的信息。

例如退货申请至少需要：

- `order_id`：用户要退哪个订单。
- `return_reason`：为什么要退货。

如果用户第一轮只说“我要退货”，Agent 不应该直接调用退货工具，因为参数不完整。正确流程是：

```text
用户：我要退货
Agent：请提供需要退换货的订单号，例如 ORD-004。
用户：ORD-004
Agent：请说明退货原因。
用户：质量问题
Agent：调用 return_apply 工具
```

`CustomerServiceSlotExtractor` 就负责从每一轮用户文本中提取这些字段。

## 4. 为什么规则要单独拆

规则判断看起来很简单，比如：

```java
message.contains("退货")
```

但客服场景里规则会不断增加：

- “我要退货”是退货申请。
- “超过七天还能退吗”是政策问答。
- “我要投诉”是人工转接。
- “赔偿、维权、起诉”是高风险人工转接。

如果所有关键词都堆在 `CustomerServiceSkill` 里，后续会很难维护。

拆出 `CustomerServiceIntentRules` 后，规则有统一入口，也更容易单独写测试。

## 5. 为什么 Prompt 要单独拆

Prompt 不是普通字符串，它决定了 LLM 如何理解上下文。

当前项目里至少有三类 Prompt：

- 意图识别 Prompt：要求 LLM 只返回一个意图名。
- 闲聊回复 Prompt：让 LLM 用中文客服语气回复。
- 工具结果总结 Prompt：让 LLM 基于工具结果生成最终回复。

这些 Prompt 和“是否调用工具”不是同一种职责，所以拆到 `CustomerServicePromptBuilder` 更清楚。

## 6. 接口 DTO 的改动

这次还新增了 `ChatApiRequest`，并将 `ChatApiController` 的入参从：

```java
@RequestBody Map<String, String> request
```

改成：

```java
@RequestBody ChatApiRequest request
```

## 7. 为什么不直接用 Map

`Map` 的问题是字段边界不清楚：

- 不知道前端到底应该传哪些字段。
- 字段名写错时不容易发现。
- 后续想加参数校验时不自然。
- Controller 代码里会出现很多 `request.get("xxx")`。

明确 DTO 的好处是：

- 接口字段一眼可见。
- 当前已经可以加校验注解，例如 `@NotBlank` 和 `@Size`。
- Controller 只负责把 HTTP 请求转换成 Agent 请求。
- 文档、测试和代码更容易对齐。

同一阶段还新增了 `ApiErrorResponse`，用于表示接口错误响应。

例如空白消息会返回：

```json
{
  "errorCode": "VALIDATION_ERROR",
  "message": "Message cannot be empty",
  "timestamp": 1783350000000
}
```

这样前端或测试就不用猜错误字段叫什么，也方便后续统一错误码。

目前还新增了 `GlobalExceptionHandler`，用于统一处理：

- 请求体 JSON 解析失败。
- Spring Validation 参数校验失败。
- `IllegalArgumentException` 参数异常。
- 未预料到的系统异常。

未预料到的异常不会直接把内部错误细节返回给用户，而是统一返回：

```json
{
  "errorCode": "INTERNAL_ERROR",
  "message": "Internal server error",
  "timestamp": 1783350000000
}
```

## 8. 为什么不直接复用 ChatRequest

`ChatApiRequest` 和 `ChatRequest` 字段很像，但它们属于不同层：

- `ChatApiRequest`：Controller 层对象，表示前端传来的 JSON。
- `ChatRequest`：Agent 层对象，表示本轮 Agent 执行需要的输入。

把它们分开，是为了避免 HTTP 接口变化直接影响 Agent 内部模型。

这个设计可以在面试中这样讲：

> 我把接口层 DTO 和 Agent 内部请求对象分开。Controller 先接收 ChatApiRequest，再转换成 ChatRequest 进入 Agent 主链路。这样做可以让外部接口演进和内部执行模型解耦，后续加参数校验、兼容旧字段或调整前端协议时，不需要改 Agent 核心逻辑。

## 9. 测试覆盖

本次新增了 `ChatApiControllerTest`，主要覆盖：

- 空消息不会进入 Agent。
- 空请求体不会导致空指针。
- 传入 `userId` 和 `conversationId` 时，会原样进入 `ChatRequest`。
- 不传 `userId` 和 `conversationId` 时，会使用默认值：
  - `userId = anonymous`
  - `conversationId = default-anonymous`

这类测试虽然不复杂，但能保护接口边界，是后端项目里很实用的测试。

后续又补充了 `ChatApiControllerMvcTest` 和 `GlobalExceptionHandlerTest`：

- `ChatApiControllerMvcTest` 会启动轻量 Web 层，验证真实 HTTP 请求下 `@Valid` 是否生效。
- `GlobalExceptionHandlerTest` 验证 JSON 解析失败、参数校验失败、业务参数异常和未预期异常都会返回稳定错误结构。

## 10. 面试表达

如果面试官问：“你为什么要拆 CustomerServiceSkill？”

可以这样回答：

> 项目早期为了快速跑通，我把规则路由、槽位抽取、Prompt 构造和 Action 编排都放在 CustomerServiceSkill 里。后面功能增加后，这个类职责开始变重，所以我做了第一轮轻量拆分：SlotExtractor 负责参数抽取，IntentRules 负责关键词规则，PromptBuilder 负责 Prompt 组织，CustomerServiceSkill 只保留整体编排。这样既保留了当前实现的简单性，又让后续扩展售前、售后、投诉 Skill 更自然。

如果面试官问：“这次重构有什么实际收益？”

可以这样回答：

> 收益主要有三个：第一，可读性更好，新人可以按职责读代码；第二，可测试性更好，槽位抽取、意图规则、Prompt 构造都能单独测；第三，扩展更稳，后续新增规则或改 Prompt，不需要在一个很长的 Skill 类里到处找。

## 11. 后续可以继续优化什么

当前只是第一轮拆分，后续还可以继续做：

1. 把 `CustomerServiceSkill` 按业务域拆成售前、售后、投诉三个 Skill。
2. 继续完善工具 outputSchema 的嵌套结构，例如商品列表、订单列表的元素 schema。
3. 把 `ToolRegistry` 抽到独立 MCP Server。
4. 继续细化错误码，例如区分认证失败、权限不足、资源不存在等。
5. 对 `userId`、`conversationId` 增加格式校验，例如只允许字母、数字、短横线和下划线。

当前阶段不急着一次性全做完。先保证主链路稳定、文档清楚、测试能回归，项目就已经比普通 Demo 扎实很多。
