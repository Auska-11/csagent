# 售后工单与 SLA 履约平台：SLA 设计

## 1. SLA 是什么

SLA 是 Service Level Agreement，表示服务响应和解决的时限承诺。

在售后工单系统中，SLA 主要回答两个问题：

- 工单多久必须首次响应？
- 工单多久必须解决？

如果超时，系统需要自动提醒、升级或重新分配。

## 2. SLA 类型

| 类型 | 说明 |
| --- | --- |
| 首次响应 SLA | 工单创建或分配后，负责人多久内必须接单或回复 |
| 解决 SLA | 工单创建后，多久内必须解决 |

## 3. SLA 规则

规则维度：

- 问题分类。
- 优先级。
- 技能组。
- 是否工作时间。

第一版可以先使用分类 + 优先级。

| 分类 | 优先级 | 首响时限 | 解决时限 |
| --- | --- | --- | --- |
| 支付异常 | P0 | 10 分钟 | 2 小时 |
| 退款问题 | P1 | 30 分钟 | 8 小时 |
| 商品咨询 | P2 | 2 小时 | 24 小时 |
| 普通建议 | P3 | 8 小时 | 72 小时 |

## 4. 截止时间计算

第一版：

```text
deadline = created_at + rule.minutes
```

增强版：

- 只计算工作时间。
- 跳过节假日。
- 不同技能组有不同工作时段。

秋招项目建议：

- 第一版先实现自然时间。
- 文档中说明后续可扩展工作时间日历。
- 面试时讲清楚复杂度和演进方案。

## 5. SLA 任务生命周期

| 状态 | 说明 |
| --- | --- |
| `PENDING` | 等待触发 |
| `TRIGGERED` | 已触发超时处理 |
| `CANCELLED` | 工单已响应或解决，任务取消 |

任务类型：

- `FIRST_RESPONSE`
- `RESOLVE`

## 6. 创建工单时的 SLA 流程

```text
创建工单
  -> 匹配 SLA 规则
  -> 计算首次响应 deadline
  -> 计算解决 deadline
  -> 写入 ticket 表 deadline 字段
  -> 写入 sla_task 表
```

需要与工单创建在同一事务中完成。

## 7. SLA 扫描任务

定时任务每隔一段时间扫描：

```sql
SELECT *
FROM sla_task
WHERE status = 'PENDING'
  AND deadline_at <= NOW()
ORDER BY deadline_at
LIMIT 100;
```

处理流程：

```text
扫描到超时任务
  -> 查询工单
  -> 判断工单当前状态
  -> 判断任务是否仍有效
  -> 标记任务 TRIGGERED
  -> 写入 Outbox 事件
  -> MQ 异步通知和升级
```

## 8. 如何避免重复触发

重复触发风险：

- 定时任务并发执行。
- 任务重试。
- MQ 重复消费。

解决方案：

- `sla_task` 使用状态控制。
- 更新时加条件 `WHERE status = 'PENDING'`。
- 事件使用唯一 `event_id`。
- 消费端记录已处理事件。

```sql
UPDATE sla_task
SET status = 'TRIGGERED', triggered_at = NOW()
WHERE id = ? AND status = 'PENDING';
```

如果影响行数为 0，说明任务已被其他线程处理。

## 9. SLA 取消

首响 SLA 取消：

- 负责人接单。
- 客服首次回复用户。
- 设置 `ticket.first_responded_at`。
- 将 `FIRST_RESPONSE` 类型任务标记为 `CANCELLED`。

解决 SLA 取消：

- 工单进入 `RESOLVED` 或 `CLOSED`。
- 工单取消。
- 设置 `resolved_at`。
- 将 `RESOLVE` 类型任务标记为 `CANCELLED`。

## 10. 超时升级策略

第一版策略：

- 首响超时：通知负责人和技能组组长。
- 解决超时：通知管理员，并将工单标记为已超时。
- P0 工单超时：立即升级给管理员。

增强版策略：

- 自动提高优先级。
- 自动转派给空闲客服。
- 多次超时进入专项队列。

## 11. 面试表达

> SLA 设计中我把“计时任务”和“超时后的动作”解耦。数据库中的 `sla_task` 负责记录任务状态，定时任务只扫描并标记超时，再通过 Outbox 事件发布 MQ。通知、升级和统计由不同消费者异步处理。为了避免重复升级，我在更新 SLA 任务时使用状态条件更新，并在消费端基于事件 ID 做幂等。

