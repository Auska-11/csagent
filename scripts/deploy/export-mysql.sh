#!/usr/bin/env bash
# 导出本地 MySQL 数据用于服务器部署迁移。
# 用法：sh scripts/deploy/export-mysql.sh [输出文件.gz]（默认 target/deploy/ 下带时间戳）
set -euo pipefail
cd "$(dirname "$0")/../.."

# 只取 MySQL 相关变量，避免 .env 中含 & 的 JDBC URL 破坏 source
set -a
source <(grep -E '^SMARTCS_MYSQL_' .env)
set +a

OUT="${1:-target/deploy/${SMARTCS_MYSQL_DATABASE}-$(date +%Y%m%d-%H%M%S).sql.gz}"
mkdir -p "$(dirname "$OUT")"

docker exec smart-cs-mysql mysqldump \
  -uroot -p"$SMARTCS_MYSQL_ROOT_PASSWORD" \
  --single-transaction --routines --triggers --set-gtid-purged=OFF \
  "$SMARTCS_MYSQL_DATABASE" | gzip > "$OUT"

echo "导出完成: $OUT ($(du -h "$OUT" | cut -f1))"
echo "下一步: scp 到服务器后在项目根目录执行 sh scripts/deploy/import-mysql.sh <该文件>"
