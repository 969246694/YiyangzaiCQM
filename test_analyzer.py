# -*- coding: utf-8 -*-
"""静态分析器回归测试：用对抗性样本验证 AST 分析的准确性，防止修复回退。

覆盖的语法特性与考察点：
  · 字符串/字符/文本块字面量中含大括号（含不成对的情形）
  · 注释中含大括号与关键字
  · 匿名内部类、局部类、嵌套静态类、枚举中的方法归属
  · lambda 表达式中的判定点归属
  · switch case / 逻辑运算符 / 各类循环的复杂度计数
  · 空 catch 与"仅打印日志的 catch"的区分
  · try-with-resources、finally 关闭、仅正常路径关闭三种资源处理

其中"嵌套类归属"与"匿名类单独统计"是本次由正则方案升级为 AST 方案后
新增的能力，测试中以显式断言单独校验。

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
#   1) 环境变量 CQM_SERVER_CLASSES
#   2) out/server                            —— build.sh / build.bat 的产物（CI 与使用者路径）
#   3) dist/cqm-server-deploy/server/classes —— build_deploy.py 的产物
CANDIDATES = [
    os.environ.get('CQM_SERVER_CLASSES'),
    os.path.join(ROOT, 'out', 'server'),
    os.path.join(ROOT, 'dist', 'cqm-server-deploy', 'server', 'classes'),
]

# JavaParser 依赖位置（两种布局）
JP_CANDIDATES = [
    os.environ.get('CQM_JAVAPARSER_JAR'),
    os.path.join(ROOT, 'out', 'lib', 'javaparser-core-3.26.4.jar'),
    os.path.join(ROOT, 'dist', 'cqm-server-deploy', 'lib', 'javaparser-core-3.26.4.jar'),
    os.path.join(ROOT, 'lib', 'javaparser-core-3.26.4.jar'),
]

# 期望结果："类#方法" -> (LOC, 圈复杂度, 空catch, 资源未关闭)
EXPECT = {
    # ── 第一组：常规边界写法 ──
    'EdgeCases#bracesInString':          (6, 2, False, False),
    'EdgeCases#braceChar':               (4, 2, False, False),
    'EdgeCases#commentsWithBraces':      (2, 1, False, False),
    'EdgeCases#anonymousClass':          (6, 1, False, False),
    'EdgeCases#lambda':                  (2, 1, False, False),
    'EdgeCases#realEmptyCatch':          (4, 2, True,  False),
    'EdgeCases#arrayInit':               (4, 2, False, False),
    'EdgeCases#afterEdgeCases':          (2, 1, False, False),
    # ── 第二组：真正容易失效的写法 ──
    'EdgeCases2#unbalancedBraceInString': (3, 1, False, False),
    'EdgeCases2#shouldBeDetected':        (4, 2, False, False),
    'EdgeCases2#properResource':          (3, 1, False, False),
    'EdgeCases2#closedInFinally':         (6, 1, False, False),
    'EdgeCases2#catchWithLog':            (5, 2, False, False),
    # ── 第三组：AST 方案应当正确处理的语法特性 ──
    'EdgeCases3#switchCase':              (10, 4, False, False),
    'EdgeCases3#logicalOps':              (5, 6, False, False),
    'EdgeCases3#loops':                   (13, 5, False, False),
    'EdgeCases3#textBlock':               (4, 1, False, False),
    'EdgeCases3#localClass':              (8, 1, False, False),
    'EdgeCases3#lambdaComplexity':        (5, 2, False, False),
    'EdgeCases3#resourceWithCatch':       (5, 2, False, False),
    'EdgeCases3#leakOnException':         (5, 1, False, True),
    # ── 嵌套类 / 局部类 / 匿名类 / 枚举：归属必须正确 ──
    #  注意 LOC 以"方法声明行（不含注解）"为起点计算：
    #  匿名类的 run() 带 @Override，故其有效行数为 2 而非 3
    'EdgeCases$anon#run':                 (2, 1, False, False),
    'EdgeCases2.Nested#nestedMethod':     (2, 1, False, False),
    'EdgeCases3.Helper#compute':          (4, 2, False, False),
    'EdgeCases3.Level#isHigh':            (2, 1, False, False),
    'EdgeCases3.Inner#innerMethod':       (4, 2, False, False),
}


def find_first(paths, marker):
    for p in paths:
        if p and os.path.exists(os.path.join(p, marker) if os.path.isdir(p) else p):
            return p
    return None


def find_server_classes():
    return find_first(CANDIDATES, os.path.join('com', 'cqm', 'server'))


def find_javaparser():
    for p in JP_CANDIDATES:
        if p and os.path.isfile(p):
            return p
    return None


def find_jdk():
    """优先 JAVA_HOME，其次已知本地 JDK，最后回退 PATH。"""
    jh = os.environ.get('JAVA_HOME')
    if jh:
        j = os.path.join(jh, 'bin')
        if os.path.isfile(os.path.join(j, 'javac.exe')) or \
           os.path.isfile(os.path.join(j, 'javac')):
            return j
    for cand in (r"C:\Program Files\java\jdk-17.0.2\bin",):
        if os.path.isdir(cand):
            return cand
    return None


def run(cmd, **kw):
    return subprocess.run(cmd, capture_output=True, text=True,
                          encoding='utf-8', errors='replace', **kw)


def main():
    print('=== 1) 编译审计驱动 ===')
    sc = find_server_classes()
    if sc is None:
        print('  !! 未找到服务端 class，请先执行 ./build.sh 或 python build_deploy.py')
        return 1
    jp = find_javaparser()
    if jp is None:
        print('  !! 未找到 JavaParser（lib/javaparser-core-3.26.4.jar）')
        return 1
    jdk = find_jdk()
    javac = os.path.join(jdk, 'javac.exe' if os.name == 'nt' else 'javac') if jdk else 'javac'
    java = os.path.join(jdk, 'java.exe' if os.name == 'nt' else 'java') if jdk else 'java'
    print(f'  服务端 class: {sc}')
    print(f'  JavaParser : {jp}')
    print(f'  JDK        : {jdk or "PATH 默认"}')

    cp = os.pathsep.join([sc, jp])
    os.makedirs(AUDIT_CLASSES, exist_ok=True)
    r = run([javac, '-encoding', 'UTF-8', '-cp', cp,
             '-d', AUDIT_CLASSES, os.path.join(ROOT, 'audit', 'AuditRunner.java')])
    if r.returncode != 0:
        print(r.stdout, r.stderr)
        return 1
    print('  编译完成')

    print('=== 2) 运行静态分析器 ===')
    # Java 18 起（JEP 400）file.encoding 不再决定 System.out 编码，需显式指定
    # stdout.encoding，否则中文判定列会以本地代码页输出而被误解析。
    r = run([java, '-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8',
             '-cp', os.pathsep.join([cp, AUDIT_CLASSES]),
             'com.cqm.server.AuditRunner', SRC])
    rows = {}
    for line in r.stdout.splitlines():
        parts = line.split()
        if len(parts) == 7 and parts[3].isdigit() and parts[4].isdigit():
            rows[f'{parts[0]}#{parts[1]}'] = (int(parts[3]), int(parts[4]),
                                              parts[5] != '-', parts[6] != '-')
    print(f'  识别到 {len(rows)} 个方法，期望 {len(EXPECT)} 个')
    if not rows:
        print('  !! 未解析到结果，原始输出：')
        print(r.stdout[:1000], r.stderr[:600])
        return 1

    print('=== 3) 逐项断言 ===')
    pass_n = fail_n = 0
    for key, exp in EXPECT.items():
        got = rows.get(key)
        if got is None:
            print(f'  [失败] {key:<38} 未被识别（漏报）')
            fail_n += 1
        elif got == exp:
            print(f'  [通过] {key:<38} LOC={got[0]:<3} 复杂度={got[1]:<3}'
                  f' 空catch={str(got[2]):<5} 未关闭={got[3]}')
            pass_n += 1
        else:
            print(f'  [失败] {key:<38} 期望 {exp}，实际 {got}')
            fail_n += 1

    extra = sorted(set(rows) - set(EXPECT))
    if extra:
        print(f'  [注意] 多出未预期条目: {extra}')

    print('=== 4) 能力断言（AST 方案相对正则方案的关键改进）===')
    caps = []
    # 4.1 嵌套类方法必须按真实所属类型统计，而非归入外层类
    caps.append(('嵌套类方法归属正确',
                 'EdgeCases2.Nested#nestedMethod' in rows,
                 f'EdgeCases2.Nested#nestedMethod {"在" if "EdgeCases2.Nested#nestedMethod" in rows else "不在"}结果中'))
    caps.append(('嵌套类方法未被错误归入外层类',
                 'EdgeCases2#nestedMethod' not in rows,
                 '外层类下无 nestedMethod 条目'))
    # 4.2 匿名内部类中的方法必须单独统计
    caps.append(('匿名类方法单独统计',
                 'EdgeCases$anon#run' in rows,
                 'EdgeCases$anon#run 存在'))
    # 4.3 方法内局部类的方法必须被收集，不能形成统计盲区
    caps.append(('局部类方法被收集',
                 'EdgeCases3.Helper#compute' in rows,
                 'EdgeCases3.Helper#compute 存在'))
    # 4.4 字符串/文本块中的大括号不得干扰方法边界
    caps.append(('字面量中的大括号不干扰边界',
                 rows.get('EdgeCases2#unbalancedBraceInString', (99,))[0] == 3,
                 f"LOC={rows.get('EdgeCases2#unbalancedBraceInString', ('?',))[0]}（应为 3，正则方案曾误算为 24）"))
    caps.append(('文本块中的大括号不计入复杂度',
                 rows.get('EdgeCases3#textBlock', (0, 99))[1] == 1,
                 f"复杂度={rows.get('EdgeCases3#textBlock', (0, '?'))[1]}（应为 1）"))
    # 4.5 资源处理三种情形的区分
    caps.append(('try-with-resources 不误报',
                 rows.get('EdgeCases3#resourceWithCatch', (0, 0, 0, True))[3] is False,
                 '未标记资源未关闭'))
    caps.append(('finally 关闭不误报',
                 rows.get('EdgeCases2#closedInFinally', (0, 0, 0, True))[3] is False,
                 '未标记资源未关闭'))
    caps.append(('仅正常路径关闭能检出',
                 rows.get('EdgeCases3#leakOnException', (0, 0, 0, False))[3] is True,
                 '已标记资源未关闭'))

    cap_fail = 0
    for name, ok, detail in caps:
        print(f'  [{"通过" if ok else "失败"}] {name:<30} {detail}')
        if not ok:
            cap_fail += 1

    print()
    print('=' * 62)
    print(f'静态分析器回归测试：逐项断言 通过 {pass_n} / 失败 {fail_n}；'
          f'能力断言 通过 {len(caps) - cap_fail} / 失败 {cap_fail}')
    print('=' * 62)
    return 0 if (fail_n == 0 and cap_fail == 0) else 1


if __name__ == '__main__':
    sys.exit(main())
