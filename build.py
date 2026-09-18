# -*- coding: utf-8 -*-
"""构建脚本：编译 server / demo / agent，并打包带 Manifest 的探针 JAR。

无需 Maven / Gradle，直接用 JDK 自带的 javac / jar。
"""
import os
import shutil
import subprocess
import sys

sys.stdout.reconfigure(encoding='utf-8')
ROOT = r"F:\deepseek对话\code-quality-monitor"
LIB = os.path.join(ROOT, 'lib')
JDK = r"C:\Program Files\Java\jdk-22"
JAVAC = os.path.join(JDK, 'bin', 'javac.exe')
JAR = os.path.join(JDK, 'bin', 'jar.exe')

BB = os.path.join(LIB, 'byte-buddy-1.14.19.jar')
BBA = os.path.join(LIB, 'byte-buddy-agent-1.14.19.jar')
H2 = os.path.join(LIB, 'h2-2.2.224.jar')


def run(cmd, cwd=None):
    r = subprocess.run(cmd, cwd=cwd, capture_output=True, text=True,
                       encoding='utf-8', errors='replace')
    if r.returncode != 0:
        print('命令失败:', ' '.join(cmd))
        print(r.stdout)
        print(r.stderr)
        raise SystemExit(1)
    return r.stdout


def srcdir(mod):
    out = []
    for dirpath, _, files in os.walk(os.path.join(ROOT, mod, 'src')):
        for f in files:
            if f.endswith('.java'):
                out.append(os.path.join(dirpath, f))
    return out


def build(mod, cp=None, clean=True):
    classes = os.path.join(ROOT, mod, 'classes')
    if clean and os.path.isdir(classes):
        shutil.rmtree(classes)
    os.makedirs(classes, exist_ok=True)
    files = srcdir(mod)
    cmd = [JAVAC, '-encoding', 'UTF-8', '-d', classes]
    if cp:
        cmd += ['-cp', cp]
    cmd += files
    run(cmd)
    print(f'  {mod}: 编译 {len(files)} 个源文件 -> {classes}')
    return classes


def main():
    print('=== 1) 编译 server ===')
    print('=== 1) 编译 server ===')
    srv = build('server', cp=H2)

    print('=== 2) 编译 demo ===')
    demo = build('demo')

    print('=== 3) 编译 agent ===')
    # 注意：MetricsAggregator 需要出现在 agent 的编译期类路径上，
    # 否则 Advice 内联时会解析不到它（错误会被 suppress 吞掉，表现为采集不到数据）。
    # 但打包时**不能**把它放进探针 JAR —— 它只存在于引导 JAR，
    # 这样才能保证全 JVM 只有一份静态状态。
    ag = build('agent', cp=f'{BB}{os.pathsep}{BBA}')

    print('=== 4) 打包探针 JAR（含 Manifest 与依赖）===')
    stage = os.path.join(ROOT, 'agent', 'stage')
    if os.path.isdir(stage):
        shutil.rmtree(stage)
    shutil.copytree(ag, stage)
    # 把 Byte Buddy 解包进探针，使探针自身可独立运行
    for jar in (BB, BBA):
        run([JAR, 'xf', jar], cwd=stage)

    # 4.1) 生成 bootstrap.jar：只含插桩运行时必需的辅助类。
    # 只有"被内联进业务类字节码、由业务类加载器执行"的类才需要放进引导加载器。
    # 探针自身的其他类（AgentConfig、Reporter、CqmAgent）继续由系统类加载器加载，
    # 若也移出探针 JAR，探针自己反而会 ClassNotFoundException。
    HELPER_CLASSES = [
        os.path.join('com', 'cqm', 'agent', 'MetricsAggregator.class'),
        os.path.join('com', 'cqm', 'agent', 'MetricsAggregator$MethodMetric.class'),
        os.path.join('com', 'cqm', 'agent', 'MetricsAggregator$Snapshot.class'),
    ]
    boot_stage = os.path.join(ROOT, 'agent', 'boot')
    if os.path.isdir(boot_stage):
        shutil.rmtree(boot_stage)
    for rel in HELPER_CLASSES:
        src = os.path.join(stage, rel)
        dst = os.path.join(boot_stage, rel)
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        if not os.path.isfile(src):
            print(f'  !! 缺少辅助类: {rel}')
            raise SystemExit(1)
        shutil.copy2(src, dst)
    # 主探针 JAR 中移除这些类，保证全 JVM 只有引导加载器里的一份
    for rel in HELPER_CLASSES:
        os.remove(os.path.join(stage, rel))

    dist = os.path.join(ROOT, 'dist')
    os.makedirs(dist, exist_ok=True)
    boot_jar = os.path.join(dist, 'cqm-agent-bootstrap.jar')
    if os.path.exists(boot_jar):
        os.remove(boot_jar)
    run([JAR, 'cf', boot_jar, '-C', boot_stage, '.'])
    print(f'  引导辅助类 JAR: {boot_jar} ({os.path.getsize(boot_jar):,} 字节)')

    manifest = os.path.join(ROOT, 'agent', 'MANIFEST.MF')
    with open(manifest, 'w', encoding='utf-8', newline='\n') as f:
        f.write('Manifest-Version: 1.0\n')
        f.write('Premain-Class: com.cqm.agent.CqmAgent\n')
        f.write('Agent-Class: com.cqm.agent.CqmAgent\n')
        f.write('Can-Redefine-Classes: true\n')
        f.write('Can-Retransform-Classes: true\n')
        f.write('Can-Set-Native-Method-Prefix: true\n')
        f.write('\n')

    agent_jar = os.path.join(dist, 'cqm-agent.jar')
    if os.path.exists(agent_jar):
        os.remove(agent_jar)
    run([JAR, 'cfm', agent_jar, manifest, '-C', stage, '.'])
    print(f'  探针: {agent_jar} ({os.path.getsize(agent_jar):,} 字节)')

    print('=== 构建完成 ===')
    print(f'  服务端 class: {srv}')
    print(f'  示例应用 class: {demo}')
    print(f'  探针 JAR: {agent_jar}')


if __name__ == '__main__':
    main()
