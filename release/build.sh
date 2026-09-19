#!/bin/bash
# 重新编译全部模块（仅依赖 JDK 自带的 javac / jar）
set -e
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$DIR"

if ! command -v javac >/dev/null 2>&1; then
  echo "[错误] 未找到 javac，请先安装 JDK 8 或更高版本。" >&2
  exit 1
fi

LIB=lib
BB="$LIB/byte-buddy-1.14.19.jar"
BBA="$LIB/byte-buddy-agent-1.14.19.jar"
H2="$LIB/h2-2.2.224.jar"

echo "=== 1/4 清理 ==="
rm -rf out
mkdir -p out/server out/demo out/agent out/boot

echo "=== 2/4 编译服务端 ==="
find src/server -name '*.java' > out/s.txt
javac -encoding UTF-8 -cp "$H2" -d out/server @out/s.txt

echo "=== 3/4 编译示例应用 ==="
find src/demo -name '*.java' > out/d.txt
javac -encoding UTF-8 -d out/demo @out/d.txt

echo "=== 4/4 编译探针并打包 ==="
find src/agent -name '*.java' > out/a.txt
javac -encoding UTF-8 -cp "$BB:$BBA" -d out/agent @out/a.txt

# 引导辅助类：单独打包，并从探针主 JAR 中排除，
# 以保证全 JVM 只有一份聚合器静态状态
mkdir -p out/boot/com/cqm/agent
mv out/agent/com/cqm/agent/MetricsAggregator*.class out/boot/com/cqm/agent/
jar cf agent/cqm-agent-bootstrap.jar -C out/boot .
jar cf agent/cqm-agent.jar -C out/agent .

echo
echo "构建完成："
echo "  out/server  out/demo  out/agent"
echo "  agent/cqm-agent.jar  agent/cqm-agent-bootstrap.jar"
echo
echo "提示：如需替换 server/classes，请手动复制 out/server 下的内容。"
