# -*- coding: utf-8 -*-
"""静态分析器回归测试：用对抗性样本验证分析结果，防止修复回退。

覆盖的边界写法：
  · 字符串/字符字面量中含大括号（含不成对的情况）
  · 注释中含大括号与关键字
  · 匿名内部类、lambda、数组初始化
  · 空 catch 与"仅打印日志的 catch"的区分
  · try-with-resources / finally 关闭资源与"未关闭资源"的区分
  · 嵌套类中的方法

用法：
    python test_analyzer.py          # 运行全部断言
退出码 0 表示全部通过。
"""
import os
import subprocess
import sys

sys.stdout.reconfigure(encoding='utf-8')
ROOT = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(ROOT, 'audit', 'src')
AUDIT_CLASSES = os.path.join(ROOT, 'audit', 'classes')

# 服务端 class 的查找顺序：
#   1) 环境变量 CQM_SERVER_CLASSES（便于在任意布局下运行）
#   2) out/server            —— build.sh / build.bat 的产物（CI 与使用者路径）
#   3) dist/cqm-server-deploy/server/classes —— build_deploy.py 的产物
# 这样同一份测试脚本在本地开发、发行包与 CI 中都能直接运行。
CANDIDATES = [
    os.environ.get('CQM_SERVER_CLASSES'),
    os.path.join(ROOT, 'out', 'server'),
    os.path.join(ROOT, 'dist', 'cqm-server-deploy', 'server', 'classes'),
]


def find_server_classes():
    for c in CANDIDATES:
        if c and os.path.isdir(os.path.join(c, 'com', 'cqm', 'server')):
            return c
    return None


def find_jdk():
    """优先使用 JAVA_HOME，其次 PATH；便于跨平台与 CI。"""
    jh = os.environ.get('JAVA_HOME')
    if jh:
        j = os.path.join(jh, 'bin')
        if os.path.isfile(os.path.join(j, 'javac.exe')) or \
           os.path.isfile(os.path.join(j, 'javac')):
            return j
    win = r"C:\Program Files\java\jdk-17.0.2\bin"
    if os.path.isdir(win):
        return win
    return None

# 期望结果：方法名 -> (LOC, 复杂度, 空catch, 资源未关闭)
EXPECT = {
    'bracesInString':          (6, 2, False, False),
    'braceChar':               (4, 2, False, False),
    'commentsWithBraces':      (2, 1, False, False),
    'anonymousClass':          (6, 1, False, False),
    'lambda':                  (2, 1, False, False),
    'realEmptyCatch':          (4, 2, True,  False),
    'arrayInit':               (4, 2, False, False),
    'afterEdgeCases':          (2, 1, False, False),
    'unbalancedBraceInString': (3, 1, False, False),   # 修复前 LOC 24 且吞掉后续方法
    'shouldBeDetected':        (4, 2, False, False),
    'properResource':          (3, 1, False, False),   # try-with-resources，不应报未关闭
    'closedInFinally':         (6, 1, False, False),   # finally 关闭，不应报未关闭
    'catchWithLog':            (5, 2, False, False),   # 有日志，不算空 catch
    'nestedMethod':            (2, 1, False, False),
}


def run(cmd, **kw):
    return subprocess.run(cmd, capture_output=True, text=True,
                          encoding='utf-8', errors='replace', **kw)


def build():
    print('=== 1) 编译审计驱动 ===')
    server_classes = find_server_classes()
    if server_classes is None:
        print('  !! 未找到服务端 class，请先执行：')
        print('       ./build.sh              （仓库布局，产物在 out/server）')
        print('       python build_deploy.py  （部署包布局）')
        print('     或用环境变量 CQM_SERVER_CLASSES 指定目录')
        return None, None
    print(f'  服务端 class: {server_classes}')

    jdk = find_jdk()
    javac = os.path.join(jdk, 'javac.exe' if os.name == 'nt' else 'javac') if jdk else 'javac'
    java = os.path.join(jdk, 'java.exe' if os.name == 'nt' else 'java') if jdk else 'java'
    print(f'  JDK: {jdk or "PATH 中的默认 javac/java"}')

    os.makedirs(AUDIT_CLASSES, exist_ok=True)
    r = run([javac, '-encoding', 'UTF-8', '-cp', server_classes,
             '-d', AUDIT_CLASSES, os.path.join(ROOT, 'audit', 'AuditRunner.java')])
    if r.returncode != 0:
        print(r.stdout, r.stderr)
        return None, None
    print('  编译完成')
    return java, server_classes


def main():
    java, sc = build()
    if java is None:
        return 1

    print('=== 2) 运行静态分析器 ===')
    # 注意：Java 18 起（JEP 400）file.encoding 不再决定 System.out 的编码，
    # 还需显式指定 stdout.encoding，否则中文判定列会以本地代码页输出，
    # 被按 UTF-8 解码后变成乱码，导致断言误判。此处同时给出两个参数，
    # 以兼容 JDK 8~17（认 file.encoding）与 18+（认 stdout.encoding）。
    r = run([java,
             '-Dfile.encoding=UTF-8',
             '-Dstdout.encoding=UTF-8',
             '-Dstderr.encoding=UTF-8',
             '-cp', f'{sc}{os.pathsep}{AUDIT_CLASSES}',
             'com.cqm.server.AuditRunner', SRC])
    rows = {}
    for line in r.stdout.splitlines():
        parts = line.split()
        if len(parts) == 6 and parts[2].isdigit() and parts[3].isdigit():
            # 判定列只可能是"是"或"-"；用"非 - 即真"避免编码差异引发误判
            rows[parts[1]] = (int(parts[2]), int(parts[3]),
                              parts[4] != '-', parts[5] != '-')
    print(f'  识别到 {len(rows)} 个方法，期望 {len(EXPECT)} 个')
    if not rows:
        print('  !! 未解析到任何结果，原始输出：')
        print(r.stdout[:800])
        print(r.stderr[:800])
        return 1

    print('=== 3) 断言 ===')
    pass_n = fail_n = 0
    for name, exp in EXPECT.items():
        got = rows.get(name)
        if got is None:
            print(f'  [失败] {name:<26} 未被识别（漏报）')
            fail_n += 1
        elif got == exp:
            print(f'  [通过] {name:<26} LOC={got[0]} 复杂度={got[1]}'
                  f' 空catch={got[2]} 未关闭={got[3]}')
            pass_n += 1
        else:
            print(f'  [失败] {name:<26} 期望 {exp}，实际 {got}')
            fail_n += 1

    extra = sorted(set(rows) - set(EXPECT))
    if extra:
        print(f'  [注意] 多识别出方法: {extra}')

    print()
    print('=' * 58)
    print(f'静态分析器回归测试：通过 {pass_n} 项，失败 {fail_n} 项')
    print('=' * 58)
    return 0 if fail_n == 0 else 1


if __name__ == '__main__':
    sys.exit(main())
