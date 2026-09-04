#!/usr/bin/env bash
# 在服务器项目根目录执行：把导出的 MySQL 数据导入 compose 的 MySQL 容器。
# 用法：sh scripts/deploy/import-mysql.sh smart_cs_agent-xxxx.sql.gz
# 前提：docker compose up -d mysql 已就绪（库与用户已由 compose 环境变量创建）。
set -euo pipefail
cd "$(dirname "$0")/../.."

FILE="${1:?用法: sh scripts/deploy/import-mysql.sh <export-mysql.sh 导出的 .sql.gz>}"
[ -f "$FILE" ] || { echo "文件不存在: $FILE"; exit 1; }

set -a
source <(grep -E '^SMARTCS_MYSQL_' .env)
set +a

gunzip -c "$FILE" | docker exec -i smart-cs-mysql mysql \
  -uroot -p"$SMARTCS_MYSQL_ROOT_PASSWORD" "$SMARTCS_MYSQL_DATABASE"

echo "导入完成。验证: docker exec smart-cs-mysql mysql -u$SMARTCS_MYSQL_USER -p*** $SMARTCS_MYSQL_DATABASE -e 'show tables;'"
