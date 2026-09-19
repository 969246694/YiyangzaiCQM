#!/bin/bash
# 构建全部模块（仅依赖 JDK 自带的 javac / jar，无需 Maven / Gradle）
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
JP="$LIB/javaparser-core-3.26.4.jar"

# 类路径分隔符随平台变化：Windows 上的 javac 即使在 Git Bash 中也要求分号
SEP=":"
case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) SEP=";" ;;
esac

for j in "$BB" "$BBA" "$H2"; do
  [ -f "$j" ] || { echo "[错误] 缺少依赖 $j" >&2; exit 1; }
done

echo "=== 1/5 清理 ==="
rm -rf out agent/cqm-agent.jar agent/cqm-agent-bootstrap.jar
mkdir -p out/server out/demo out/agent out/boot

echo "=== 2/5 编译服务端 ==="
find server/src -name '*.java' > out/s.txt
javac -encoding UTF-8 -cp "$H2$SEP$JP" -d out/server @out/s.txt

echo "=== 3/5 编译示例应用 ==="
find demo/src -name '*.java' > out/d.txt
javac -encoding UTF-8 -d out/demo @out/d.txt

echo "=== 4/5 编译诊断探针 ==="
if [ -d diag/src ]; then
  mkdir -p out/diag
  find diag/src -name '*.java' > out/g.txt
  javac -encoding UTF-8 -cp "$BB$SEP$BBA" -d out/diag @out/g.txt
fi

echo "=== 5/5 编译探针并打包 ==="
find agent/src -name '*.java' > out/a.txt
javac -encoding UTF-8 -cp "$BB$SEP$BBA" -d out/agent @out/a.txt

# 把 Byte Buddy 解包进探针 JAR：探针要随 JVM 启动即用，不能依赖外部类路径，
# 因此必须打成自包含的 fat jar，否则加载时报 NoClassDefFoundError。
ROOT_ABS="$(pwd)"
( cd out/agent && jar xf "$ROOT_ABS/$BB" && jar xf "$ROOT_ABS/$BBA" )
rm -f out/agent/META-INF/MANIFEST.MF

# 聚合器必须单独放进引导类加载器，并从探针主 JAR 中移除：
# 插桩代码被内联进业务类后由业务类的类加载器执行，若聚合器同时存在于
# 应用类加载器，会出现多份静态状态导致指标不一致。
mkdir -p out/boot/com/cqm/agent
mv out/agent/com/cqm/agent/MetricsAggregator*.class out/boot/com/cqm/agent/

cat > out/MANIFEST.MF <<'MF'
Manifest-Version: 1.0
Premain-Class: com.cqm.agent.CqmAgent
Agent-Class: com.cqm.agent.CqmAgent
Can-Redefine-Classes: true
Can-Retransform-Classes: true

MF

jar cf agent/cqm-agent-bootstrap.jar -C out/boot .
jar cfm agent/cqm-agent.jar out/MANIFEST.MF -C out/agent .

echo
echo "构建完成："
echo "  out/server  out/demo  out/agent"
echo "  agent/cqm-agent.jar  agent/cqm-agent-bootstrap.jar"
echo
echo "运行服务端："
echo "  java -Dcqm.src=demo/src -cp \"out/server$SEP$H2$SEP$JP\" com.cqm.server.Server 8080"
