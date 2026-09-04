#!/usr/bin/env bash
# 把项目同步到服务器（rsync 直传，不依赖 GitHub 连通性）。
# 密钥文件（.env / sidecar/xianyu/.env）不走本脚本，见部署手册的单独 scp 步骤。
#
# 用法：sh scripts/deploy/sync-to-server.sh [SSH主机别名，默认 myserver] [远端目录，默认 /opt/csagent]
set -euo pipefail
cd "$(dirname "$0")/../.."

HOST="${1:-myserver}"
REMOTE_DIR="${2:-/opt/csagent}"

JAR=target/smart-cs-agent-0.0.1-SNAPSHOT.jar
if [ ! -f "$JAR" ]; then
  echo "缺 $JAR —— 先在开发机执行：sh mvnw -DskipTests package" >&2
  exit 1
fi

# --delete 保持远端与源码一致；被 --exclude 的路径（远端 .env、data 等）不会被删除。
rsync -az --delete \
  --exclude .git \
  --exclude target \
  --exclude frontend/node_modules \
  --exclude node_modules \
  --exclude sidecar/xianyu/.venv \
  --exclude sidecar/xianyu/__pycache__ \
  --exclude sidecar/xianyu/.env \
  --exclude .env \
  --exclude data \
  ./ "$HOST:$REMOTE_DIR/"

# 预打包 JAR（含 skills/ 与 React UI）单独补传，供 Dockerfile.prebuilt 使用。
rsync -az "$JAR" "$HOST:$REMOTE_DIR/target/smart-cs-agent-0.0.1-SNAPSHOT.jar"

echo "同步完成 → $HOST:$REMOTE_DIR"
