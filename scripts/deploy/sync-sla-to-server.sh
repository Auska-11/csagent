#!/usr/bin/env bash
# 同步 SLA 平台（after-sales-sla-platform，源仓库只读）到服务器 /opt/sla。
# 传输内容：deploy/sla/ 编排文件 + 开发机构建好的 SLA JAR（含前端）。
# 密钥 .env 不走本脚本——单独 scp 落盘 chmod 600，见部署手册。
#
# 用法：sh scripts/deploy/sync-sla-to-server.sh [SSH主机别名，默认 myserver] [远端目录，默认 /opt/sla]
# SLA 仓库不在默认位置时：SLA_REPO=/path/to/after-sales-sla-platform sh scripts/deploy/sync-sla-to-server.sh
set -euo pipefail
cd "$(dirname "$0")/../.."

HOST="${1:-myserver}"
REMOTE_DIR="${2:-/opt/sla}"
SLA_REPO="${SLA_REPO:-$HOME/after-sales-sla-platform}"

JAR="$SLA_REPO/target/after-sales-sla-platform-0.0.1-SNAPSHOT.jar"
if [ ! -f "$JAR" ]; then
  echo "缺 $JAR —— 先在 SLA 仓库执行：mvn -DskipTests package（前端 dist 需先构建）" >&2
  exit 1
fi

# --exclude .env：密钥文件单独 scp，重复同步绝不能把远端密钥删掉。
# 先同步编排再建 target 目录：编排 rsync 带 --delete，会删掉源里不存在的
# 空 target/ 目录，必须放在它后面建。
rsync -az --delete --exclude .env deploy/sla/ "$HOST:$REMOTE_DIR/"
ssh "$HOST" "mkdir -p '$REMOTE_DIR/target'"
rsync -az "$JAR" "$HOST:$REMOTE_DIR/target/after-sales-sla-platform-0.0.1-SNAPSHOT.jar"

echo "SLA 同步完成 -> ${HOST}:${REMOTE_DIR}（.env 需单独 scp 并 chmod 600）"
