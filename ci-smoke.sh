#!/bin/bash
# 端到端冒烟测试（CI 与本地均可用）：
#   启动服务端 -> 以探针方式运行示例应用 -> 断言采集到指标
set -e
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$DIR"

PORT=8099
SEP=":"
case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) SEP=";" ;;
esac

TMP="$(mktemp -d)"
trap 'kill $SRV_PID 2>/dev/null || true; rm -rf "$TMP"' EXIT

echo "=== 1) 启动服务端（端口 $PORT）==="
java -Xmx192m -Dcqm.src=demo/src -Dcqm.db="jdbc:h2:file:$TMP/cqm" \
  -cp "out/server${SEP}lib/h2-2.2.224.jar${SEP}lib/javaparser-core-3.26.4.jar" \
  com.cqm.server.Server "$PORT" > "$TMP/server.log" 2>&1 &
SRV_PID=$!

ok=0
for i in $(seq 1 40); do
  if curl -sf --max-time 2 "http://127.0.0.1:$PORT/api/overview" >/dev/null 2>&1; then
    ok=1
    break
  fi
  sleep 0.5
done
if [ "$ok" != "1" ]; then
  echo "!! 服务端未能启动，日志："
  cat "$TMP/server.log"
  exit 1
fi
echo "  服务端就绪"

echo "=== 2) 以探针方式运行示例应用 ==="
java -javaagent:agent/cqm-agent.jar \
  -Dcqm.packages=com.demo \
  -Dcqm.server="http://127.0.0.1:$PORT" \
  -Dcqm.interval=2000 \
  -Dcqm.app=ci-smoke \
  -cp out/demo \
  com.demo.BizService 8

echo "=== 3) 断言采集结果 ==="
sleep 2
OV="$(curl -s --max-time 5 "http://127.0.0.1:$PORT/api/overview")"
echo "  $OV"

python3 - "$OV" <<'PY'
import json, sys
ov = json.loads(sys.argv[1])
errors = []
if ov.get('app') != 'ci-smoke':
    errors.append(f"应用名不符: {ov.get('app')}")
if ov.get('methods', 0) <= 0:
    errors.append(f"未采集到方法: {ov.get('methods')}")
if ov.get('staticMethods', 0) <= 0:
    errors.append(f"静态分析无结果: {ov.get('staticMethods')}")
if ov.get('violations', 0) <= 0:
    errors.append(f"规则未检出违规: {ov.get('violations')}")
if ov.get('score', 0) <= 0:
    errors.append(f"质量评分为 0: {ov.get('score')}")
if errors:
    print('  !! 断言失败:')
    for e in errors:
        print('    -', e)
    sys.exit(1)
print(f"  通过：应用={ov['app']} 方法数={ov['methods']} "
      f"覆盖率={ov['coverage']}% 违规={ov['violations']} 得分={ov['score']}")
PY

echo
echo "端到端冒烟测试通过"
