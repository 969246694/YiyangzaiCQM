#!/bin/bash
# 运行示例应用（挂载探针），用于快速查看平台效果
# 前置条件：服务端已启动（server/start.sh）
set -e
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$DIR"

SERVER="${1:-http://127.0.0.1:8080}"
SECONDS_TO_RUN="${2:-20}"

if ! command -v java >/dev/null 2>&1; then
  echo "[错误] 未找到 java 命令，请先安装 JDK 8 或更高版本。" >&2
  exit 1
fi

echo "运行示例应用 ${SECONDS_TO_RUN} 秒，探针上报到 ${SERVER}"
echo "请同时打开 ${SERVER} 观察数据"
echo

exec java -javaagent:agent/cqm-agent.jar \
  -Dcqm.packages=com.demo \
  -Dcqm.server="$SERVER" \
  -Dcqm.interval=3000 \
  -Dcqm.app=demo-app \
  -cp demo/classes \
  com.demo.BizService "$SECONDS_TO_RUN"
