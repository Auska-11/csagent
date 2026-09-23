# jointdebug 夹具（不参与知识库加载）

本目录存放本地双项目联调（Agent ↔ SLA 平台）使用的夹具，不会被
`MarkdownKnowledgeDocumentLoader` 的 `classpath*:/knowledge/**/*.md` 扫描。

- `knowledge-product-207700112233.md`：联调测试商品知识文档。
  需要复跑联调时，把它复制到 `src/main/resources/knowledge/product/` 并重新打包即可；
  生产构建默认不含该商品，避免“联调测试商品”进入线上知识库。

联调步骤与脱敏证据见仓库 `docs/客服Agent/` 下的验收记录文档。
