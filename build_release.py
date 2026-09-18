# -*- coding: utf-8 -*-
"""组装可下载的发行包 CQM-v1.0.0.zip。

原则：
  · 开箱即用——预编译产物齐全，使用者只需一个 JDK，无需 Maven/Gradle/Python
  · 不含任何密钥——部署令牌、数据库文件、日志一律排除
  · 含完整源码、建表脚本与文档，便于二次开发
"""
import os
import shutil
import subprocess
import sys
import zipfile

sys.stdout.reconfigure(encoding='utf-8')
ROOT = r"F:\deepseek对话\code-quality-monitor"
REL = os.path.join(ROOT, 'release')
OUT = os.path.join(ROOT, 'dist')
VER = 'v1.0.0'
NAME = f'CQM-{VER}'
STAGE = os.path.join(OUT, NAME)
ZIP = os.path.join(OUT, NAME + '.zip')

DEPLOY = os.path.join(ROOT, 'dist', 'cqm-server-deploy')
H2 = os.path.join(ROOT, 'lib', 'h2-2.2.224.jar')
BB = os.path.join(ROOT, 'lib', 'byte-buddy-1.14.19.jar')
BBA = os.path.join(ROOT, 'lib', 'byte-buddy-agent-1.14.19.jar')

# 严禁进入发行包的内容（密钥 / 运行数据 / 日志 / 编译中间产物）
FORBIDDEN = ('token', '.mv.db', '.trace.db', '.lock.db', '.log', '__pycache__')


def cp(src, dst):
    os.makedirs(os.path.dirname(dst), exist_ok=True)
    if os.path.isdir(src):
        if os.path.isdir(dst):
            shutil.rmtree(dst)
        shutil.copytree(src, dst)
    else:
        shutil.copy2(src, dst)


def main():
    print('=== 1) 准备暂存目录 ===')
    if os.path.isdir(STAGE):
        shutil.rmtree(STAGE)
    os.makedirs(STAGE)

    print('=== 2) 复制预编译产物（开箱即用）===')
    # 探针
    cp(os.path.join(DEPLOY, 'agent', 'cqm-agent.jar'),
       os.path.join(STAGE, 'agent', 'cqm-agent.jar'))
    cp(os.path.join(DEPLOY, 'agent', 'cqm-agent-bootstrap.jar'),
       os.path.join(STAGE, 'agent', 'cqm-agent-bootstrap.jar'))
    # 服务端
    cp(os.path.join(DEPLOY, 'server', 'classes'),
       os.path.join(STAGE, 'server', 'classes'))
    cp(H2, os.path.join(STAGE, 'server', 'lib', 'h2-2.2.224.jar'))
    # 示例应用
    cp(os.path.join(DEPLOY, 'demo', 'classes'),
       os.path.join(STAGE, 'demo', 'classes'))
    cp(os.path.join(DEPLOY, 'demo', 'src'),
       os.path.join(STAGE, 'demo', 'src'))

    print('=== 3) 复制源码与依赖（便于二次开发）===')
    for mod in ('agent', 'server', 'demo', 'diag'):
        src = os.path.join(ROOT, mod, 'src')
        if os.path.isdir(src):
            cp(src, os.path.join(STAGE, 'src', mod))
    os.makedirs(os.path.join(STAGE, 'lib'), exist_ok=True)
    for jar in (BB, BBA, H2):
        cp(jar, os.path.join(STAGE, 'lib', os.path.basename(jar)))

    print('=== 4) 复制脚本、建表脚本与文档 ===')
    for f in ('README.md', 'LICENSE', 'build.sh', 'build.bat',
              'run-demo.sh', 'run-demo.bat'):
        cp(os.path.join(REL, f), os.path.join(STAGE, f))
    for f in ('start.sh', 'start.bat'):
        cp(os.path.join(REL, 'server', f), os.path.join(STAGE, 'server', f))
    cp(os.path.join(ROOT, 'sql', 'schema.sql'), os.path.join(STAGE, 'sql', 'schema.sql'))

    docs = os.path.join(STAGE, 'docs')
    os.makedirs(docs, exist_ok=True)
    for f in ('线上部署说明.md', '毕设进展台账.md'):
        p = os.path.join(ROOT, 'docs', f)
        if os.path.isfile(p):
            cp(p, os.path.join(docs, f))
    # 论文用图：架构图与界面截图，便于使用者快速理解
    for src, dst in ((os.path.join(ROOT, 'docs', 'figures', 'Fig4-1-architecture.png'),
                      'architecture.png'),
                     (os.path.join(ROOT, 'docs', 'screenshots', 'Dashboard.png'),
                      'dashboard.png')):
        if os.path.isfile(src):
            cp(src, os.path.join(docs, dst))

    print('=== 5) 安全检查：确认无密钥与运行数据 ===')
    bad = []
    for dirpath, _, files in os.walk(STAGE):
        for f in files:
            low = f.lower()
            if any(k in low for k in FORBIDDEN):
                bad.append(os.path.join(dirpath, f))
    if bad:
        print('  !! 发现不应打包的文件:')
        for b in bad:
            print('    ', b)
        return 1
    print('  通过：无令牌、无数据库文件、无日志')

    print('=== 6) 打包 ===')
    if os.path.exists(ZIP):
        os.remove(ZIP)
    with zipfile.ZipFile(ZIP, 'w', zipfile.ZIP_DEFLATED, compresslevel=9) as z:
        for dirpath, _, files in os.walk(STAGE):
            for f in files:
                full = os.path.join(dirpath, f)
                z.write(full, os.path.relpath(full, OUT))
    size = os.path.getsize(ZIP)
    print(f'  {ZIP}  ({size/1024/1024:.2f} MB)')

    print('=== 7) 校验包内容 ===')
    with zipfile.ZipFile(ZIP) as z:
        names = z.namelist()
        print(f'  条目数: {len(names)}')
        tops = sorted({n.split("/")[0] + "/" + (n.split("/")[1] if len(n.split("/")) > 1 else "")
                       for n in names})
        for t in tops:
            print('   ', t)
        bad = z.testzip()
        print('  完整性:', '正常' if bad is None else f'损坏于 {bad}')

    print()
    print('=== 8) 解压冒烟测试（模拟使用者开箱运行）===')
    test_dir = os.path.join(OUT, '_smoketest')
    if os.path.isdir(test_dir):
        shutil.rmtree(test_dir)
    with zipfile.ZipFile(ZIP) as z:
        z.extractall(test_dir)
    root = os.path.join(test_dir, NAME)
    jdk = r"C:\Program Files\java\jdk-17.0.2\bin\java.exe"
    # 启动服务端
    srv = subprocess.Popen(
        [jdk, '-Xmx192m', '-Dcqm.src=demo/src',
         '-cp', 'server/classes' + os.pathsep + 'server/lib/h2-2.2.224.jar',
         'com.cqm.server.Server', '8099'],
        cwd=root, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    import time
    import urllib.request
    ok = False
    for _ in range(30):
        time.sleep(0.5)
        try:
            urllib.request.urlopen('http://127.0.0.1:8099/api/overview', timeout=2).read()
            ok = True
            break
        except Exception:
            continue
    print('  服务端启动:', '成功' if ok else '失败')
    if ok:
        # 跑一次示例应用
        r = subprocess.run(
            [jdk, '-javaagent:agent/cqm-agent.jar', '-Dcqm.packages=com.demo',
             '-Dcqm.server=http://127.0.0.1:8099', '-Dcqm.interval=2000',
             '-Dcqm.app=smoketest', '-cp', 'demo/classes', 'com.demo.BizService', '6'],
            cwd=root, capture_output=True, text=True, encoding='utf-8', errors='replace')
        time.sleep(2)
        import json
        with urllib.request.urlopen('http://127.0.0.1:8099/api/overview', timeout=5) as resp:
            ov = json.loads(resp.read().decode('utf-8'))
        print(f"  探针采集: 应用={ov['app']} 方法数={ov['methods']} 覆盖率={ov['coverage']}% "
              f"违规={ov['violations']} 得分={ov['score']}")
        print('  结论:', '发行包开箱即用，验证通过'
              if ov['methods'] > 0 and ov['app'] == 'smoketest' else '!! 采集异常')
    srv.terminate()
    time.sleep(1)
    shutil.rmtree(test_dir, ignore_errors=True)
    return 0


if __name__ == '__main__':
    sys.exit(main())
