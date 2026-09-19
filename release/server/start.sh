#!/bin/bash
# 启动代码质量监控平台服务端
# 用法: ./start.sh [端口] [源码目录]
set -e

PORT="${1:-8080}"
SRC="${2:-../demo/src}"
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$DIR"

if ! command -v java >/dev/null 2>&1; then
  echo "[错误] 未找到 java 命令，请先安装 JDK 8 或更高版本并配置 PATH。" >&2
  exit 1
fi

echo "正在启动代码质量监控平台..."
echo "  端口:     $PORT"
echo "  源码目录: $SRC"
echo "  监控页面: http://127.0.0.1:$PORT/"
echo

exec java -Xmx256m -Dcqm.src="$SRC" \
  -cp "classes:lib/h2-2.2.224.jar:lib/javaparser-core-3.26.4.jar" \
  com.cqm.server.Server "$PORT"
