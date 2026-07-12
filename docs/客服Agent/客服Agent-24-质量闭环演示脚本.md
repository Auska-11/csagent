# 客服Agent-24-质量闭环演示脚本

## 1. 为什么需要演示脚本

项目已经具备 Trace、Monitor、用户反馈、Eval 候选样本、人工审核、草稿导出和半自动合并正式评测集。

但是面试时只讲“我做了闭环”还不够有说服力，最好能用一个可复现脚本跑出来：

```text
用户点击无用
-> 生成 Eval 候选样本
-> 人工审核 APPROVED
-> 补齐 requiredKeywords / forbiddenKeywords
-> 合并到 response_quality_eval_cases.json
-> 运行回复质量评测
```

这说明项目不只是能聊天，还能把线上质量信号沉淀成可回归验证的测试资产。

## 2. 脚本位置

```text
scripts/demo-quality-loop.ps1
```

这个脚本面向 Windows PowerShell，本地开发和面试演示都可以使用。

## 3. 演示前启动服务

为了不污染正式评测文件，建议先让应用写入临时评测文件：

```powershell
$env:SMARTCS_MONITOR_RESPONSE_QUALITY_EVAL_FILE="target/demo-quality-loop/response_quality_eval_cases.json"
$env:SMARTCS_MONITOR_EVAL_CANDIDATES_FILE="target/demo-quality-loop/feedback-eval-candidates.json"
.\mvnw.cmd spring-boot:run
```

启动完成后，另开一个 PowerShell 窗口运行：

```powershell
.\scripts\demo-quality-loop.ps1
```

脚本会自动把正式 `response_quality_eval_cases.json` 复制到 `target/demo-quality-loop/`，再把本次演示生成的 case 合并到临时文件里。

## 4. 脚本做了什么

脚本会依次执行：

1. 调用 `/api/health` 检查服务是否启动。
2. 调用 `POST /api/auth/demo/tokens` 获取 USER-001 的短期 JWT。
3. 携带 Bearer JWT 调用 `POST /api/chat/send`，创建真实会话并取得服务端助手回复。
4. 调用 `POST /api/monitor/feedback` 提交一条匹配该会话的 `UNHELPFUL` 反馈。
5. 以 ADMIN 角色调用候选查询、审核和 merge 接口，把补齐断言后的样本合并进临时评测文件。
6. 使用下面命令运行回复质量评测：

```powershell
mvn "-Dmaven.repo.local=.m2\repository" "-Dsmartcs.eval.response-quality-file=target/demo-quality-loop/response_quality_eval_cases.json" "-Dtest=ResponseQualityEvaluationTest" test
```

`ResponseQualityEvaluationTest` 默认仍然读取 `src/test/resources/eval/response_quality_eval_cases.json`，只有传入 `smartcs.eval.response-quality-file` 时才读取覆盖文件。

这样既能演示闭环，又不会把演示数据直接写入源码目录。

## 5. 面试讲法

可以这样讲：

> 我给质量闭环补了一个可复现 Demo 脚本。脚本会模拟用户点击无用，后端生成 Eval 候选样本，然后通过审核接口把它标记为 APPROVED，再用合并接口把人工补齐断言后的样本写入临时 response_quality_eval_cases.json，最后只运行 ResponseQualityEvaluationTest。这样面试时可以现场展示“线上反馈如何变成回归测试”，而且不会污染正式评测文件。

## 6. 后续扩展

- 脚本可以继续扩展成 CI smoke test。
- 可以增加 `--start-server` 模式，由脚本自动启动和关闭 Spring Boot。
- 可以把演示结果输出成 Markdown 报告，放进 `target/demo-quality-loop/`。
- 后续接入真实模型 Judge 后，也可以增加 Judge 评测演示脚本。
