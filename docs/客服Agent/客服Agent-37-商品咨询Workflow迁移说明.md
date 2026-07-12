# 客服Agent-37：商品咨询 Workflow 迁移说明

## 1. 本次迁移解决什么问题

迁移前，商品价格、库存、推荐和长期偏好读取都集中在 `CustomerServiceSkill` 中。商品查询本身虽然
只有一次工具调用，但它同时依赖路由规则、商品槽位、用户长期记忆和旧工具 DTO，继续堆在大 Skill
中会让后续增加新商品场景越来越难维护。

本批次新增独立 `ProductAdvisoryWorkflow` 和商品 Skill Package，并把本地商品目录迁移为独立
Capability Provider。旧商品分支暂时保留，用于偏好写入和紧急回滚。

## 2. 新增组件

| 组件 | 职责 |
| --- | --- |
| `skills/product-advisory/manifest.yaml` | 声明商品 Skill、Workflow 绑定、优先级和最小权限 |
| `skills/product-advisory/SKILL.md` | 约束商品事实、业务边界和推荐规则 |
| `skills/product-advisory/references/catalog-guidance.md` | 说明价格库存快照和无结果回复规则 |
| `workflow/productadvisory/ProductAdvisoryWorkflow.java` | 匹配商品咨询、追问关键词、读取偏好并组织回复 |
| `workflow/productadvisory/ProductAdvisoryState.java` | 独立保存搜索词、偏好来源和步骤 |
| `capability/local/LocalProductCatalogCapabilityProvider.java` | 直接访问商品 Repository 并返回稳定契约 |
| `capability/api/contracts/ProductSummary.java` | 单个商品的稳定输出契约 |
| `capability/api/contracts/ProductSearchResult.java` | 商品搜索列表的稳定输出契约 |
| `capability/local/LegacyCapabilityOverride.java` | 让独立 Provider 通用声明自己接管的旧能力 |

## 3. 状态机

```text
START
  -> 明确商品词        -> SEARCHING -> COMPLETED
  -> 泛化推荐 + 有偏好 -> SEARCHING -> COMPLETED
  -> 泛化推荐 + 无偏好 -> 搜索全部目录 -> COMPLETED
  -> 只问“多少钱”      -> WAITING_KEYWORD

WAITING_KEYWORD
  -> 补充商品或品类 -> SEARCHING -> COMPLETED
  -> 要求人工       -> HandoffAction
  -> 取消           -> CANCELLED

SEARCHING
  -> ProductSearchResult 有结果 -> COMPLETED
  -> 空列表                    -> COMPLETED（如实说明无匹配）
  -> 技术失败或契约错误         -> FAILED
```

等待商品词时，用户第二轮可以只说“蓝牙耳机”。Router 会根据活跃 Workflow 恢复
`product_advisory`，不要求用户重新表达“我想问价格”。

## 4. 长期偏好如何平滑迁移

现有项目已经支持：

```text
用户：“我喜欢蓝牙耳机”
  -> CustomerServiceSkill 写入 UserMemoryStore

用户：“给我推荐一下”
  -> 读取最近商品偏好“蓝牙耳机”
  -> 搜索商品目录
```

本批次没有把“偏好写入”强行塞进商品咨询 Workflow。新的匹配规则遇到“我喜欢、偏好、以后推荐”
等表达时主动返回 `noMatch`，继续由 Legacy 用户记忆分支写入；泛化推荐则由新 Workflow 读取最近偏好。

这样可以把迁移拆成两个可回滚步骤：

1. 本批先迁移商品查询和偏好读取。
2. 后续 `UserProfileWorkflow` 再接管偏好写入和用户画像管理。

## 5. 稳定商品契约

旧本地工具返回：

- `ProductItemData`
- `ProductSearchToolData`
- `ProductInfoToolData`

远程 MCP 返回 JSON Map。现在两种来源都会在 Provider 边界转换为：

- `ProductSummary`
- `ProductSearchResult`

`ProductAdvisoryWorkflow` 不再 import 旧 `tools` 包。以后商品目录改成数据库服务、独立 MCP Server，
或增加缓存时，Workflow 的状态机和回复逻辑可以保持不变。

## 6. 为什么增加通用 LegacyCapabilityOverride

迁移退货申请和退货查询时，`ToolRegistryCapabilityProvider` 使用了两个业务布尔字段来决定是否隐藏
旧工具。如果每迁移一个能力都继续增加字段，最后会变成：

```text
includeLegacyReturnApply
includeLegacyReturnLookup
includeLegacyProductSearch
includeLegacyProductLookup
includeLegacyUserLookup
...
```

这仍然会让兼容 Adapter 知道越来越多业务。

现在独立本地 Provider 实现 `LegacyCapabilityOverride`，返回自己已经接管的 canonical ID：

```text
LocalReturnApplyCapabilityProvider -> return.apply
LocalReturnLookupCapabilityProvider -> return.lookup
LocalProductCatalogCapabilityProvider -> product.search、product.lookup
```

Spring 会把全部 Override 作为列表注入 `ToolRegistryCapabilityProvider`。兼容 Provider 只做通用集合过滤，
不再为每个新业务增加字段或 `if`。后续迁移用户查询时，只需让新 Provider 声明
`user.lookup`，无需再次修改过滤算法。

## 7. 路由边界

商品关键词很容易和其他任务同时出现，因此新 Workflow 主动让路：

| 消息 | 处理方 |
| --- | --- |
| `蓝牙耳机多少钱` | `ProductAdvisoryWorkflow` |
| `蓝牙耳机保修多久` | `WarrantyQueryWorkflow` |
| `我想退蓝牙耳机` | `ReturnApplyWorkflow` |
| `订单里的商品到哪了` | `OrderQueryWorkflow` |
| `我喜欢蓝牙耳机` | Legacy 用户记忆写入，后续由 `UserProfileWorkflow` 接管 |
| `我要投诉耳机质量` | Legacy 人工升级，后续由 `EscalationWorkflow` 接管 |

这类“主动 noMatch”是职责边界的一部分，不是漏识别。

## 8. 测试证据

| 测试 | 主要验证内容 |
| --- | --- |
| `ProductAdvisoryWorkflowTest` | 询价、跨轮追问、目录推荐、偏好读取、空结果和人工打断 |
| `ProductAdvisoryWorkflowIntegrationTest` | 真实 Agent、Router、长期记忆、Gateway 和 Repository 完整链路 |
| `LocalProductCatalogCapabilityProviderTest` | 两个商品 Capability 的元数据、搜索、详情和无结果契约 |
| `ToolRegistryCapabilityProviderTest` | Override 集合通用过滤与旧商品 DTO 转稳定契约 |
| `McpCapabilityProviderTest` | 远程商品 JSON 恢复为同一稳定契约 |
| `NewArchitectureBoundaryTest` | 核心 Runtime、Router、Registry、Gateway 不 import 商品 Workflow |

## 9. 回滚与下一步

旧 `CustomerServiceSkill` 商品分支和 customer-service Package 中的商品权限仍保留。新 Workflow 出现
回归时，可以停用独立 Package/Bean，Legacy 路径仍能提供原有商品查询。

下一批迁移 `PolicyQaWorkflow`，把保修之外的退货政策、发票和配送问答从大 Skill 中拆出，同时复用
已有 `knowledge.search` 稳定契约。
