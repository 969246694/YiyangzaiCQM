# -*- coding: utf-8 -*-
"""用 JDK 17 编译并打包服务端部署包（线上服务器只有 OpenJDK 17）。

产出 dist/cqm-server-deploy/
  server/classes/       服务端 class（target 17）
  lib/h2-2.2.224.jar    H2 驱动
  demo/classes/         示例应用 class（含坏味道样本）
  demo/src/             示例应用源码（供服务端静态分析）
  agent/cqm-agent.jar   探针（含 bootstrap 辅助 JAR）
"""
import os
import shutil
import subprocess
import sys

sys.stdout.reconfigure(encoding='utf-8')
ROOT = r"F:\deepseek对话\code-quality-monitor"
LIB = os.path.join(ROOT, 'lib')
OUT = os.path.join(ROOT, 'dist', 'cqm-server-deploy')

JDK17 = r"C:\Program Files\java\jdk-17.0.2"
JAVAC = os.path.join(JDK17, 'bin', 'javac.exe')
JAR = os.path.join(JDK17, 'bin', 'jar.exe')

BB = os.path.join(LIB, 'byte-buddy-1.14.19.jar')
BBA = os.path.join(LIB, 'byte-buddy-agent-1.14.19.jar')
H2 = os.path.join(LIB, 'h2-2.2.224.jar')
RELEASE = '17'


def run(cmd, cwd=None):
    r = subprocess.run(cmd, cwd=cwd, capture_output=True, text=True,
                       encoding='utf-8', errors='replace')
    if r.returncode != 0:
        print('命令失败:', ' '.join(cmd))
        print(r.stdout)
        print(r.stderr)
        raise SystemExit(1)
    return r.stdout


def sources(mod):
    out = []
    for dirpath, _, files in os.walk(os.path.join(ROOT, mod, 'src')):
        for f in files:
            if f.endswith('.java'):
                out.append(os.path.join(dirpath, f))
    return sorted(out)


def compile_mod(mod, out_dir, cp=None):
    if os.path.isdir(out_dir):
        shutil.rmtree(out_dir)
    os.makedirs(out_dir, exist_ok=True)
    files = sources(mod)
    cmd = [JAVAC, '--release', RELEASE, '-encoding', 'UTF-8', '-d', out_dir]
    if cp:
        cmd += ['-cp', cp]
    cmd += files
    run(cmd)
    print(f'  {mod}: {len(files)} 个源文件 -> {out_dir}')
    return files


def main():
    if not os.path.isdir(JDK17):
        print('!! 未找到 JDK 17:', JDK17)
        return 1
    print('使用 JDK 17 编译（--release 17）')
    if os.path.isdir(OUT):
        shutil.rmtree(OUT)
    os.makedirs(OUT)

    print('=== 1) 编译 server ===')
    compile_mod('server', os.path.join(OUT, 'server', 'classes'), cp=H2)

    print('=== 2) 编译 demo ===')
    compile_mod('demo', os.path.join(OUT, 'demo', 'classes'))

    print('=== 3) 编译 agent 并打包探针 ===')
    ag = os.path.join(ROOT, 'agent', 'classes17')
    compile_mod('agent', ag, cp=f'{BB}{os.pathsep}{BBA}')

    stage = os.path.join(OUT, '_agent_stage')
    shutil.copytree(ag, stage)
    for jar in (BB, BBA):
        run([JAR, 'xf', jar], cwd=stage)

    # 引导辅助类单独成 JAR，并从探针主 JAR 移除（保证全 JVM 只有一份）
    HELPER = [
        os.path.join('com', 'cqm', 'agent', 'MetricsAggregator.class'),
        os.path.join('com', 'cqm', 'agent', 'MetricsAggregator$MethodMetric.class'),
        os.path.join('com', 'cqm', 'agent', 'MetricsAggregator$Snapshot.class'),
    ]
    boot_stage = os.path.join(OUT, '_boot_stage')
    for rel in HELPER:
        src = os.path.join(stage, rel)
        dst = os.path.join(boot_stage, rel)
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        if not os.path.isfile(src):
            print('  !! 缺少辅助类', rel)
            return 1
        shutil.copy2(src, dst)
        os.remove(src)

    agent_dir = os.path.join(OUT, 'agent')
    os.makedirs(agent_dir, exist_ok=True)
    run([JAR, 'cf', os.path.join(agent_dir, 'cqm-agent-bootstrap.jar'),
         '-C', boot_stage, '.'])

    manifest = os.path.join(OUT, '_MANIFEST.MF')
    with open(manifest, 'w', encoding='utf-8', newline='\n') as f:
        f.write('Manifest-Version: 1.0\n')
        f.write('Premain-Class: com.cqm.agent.CqmAgent\n')
        f.write('Agent-Class: com.cqm.agent.CqmAgent\n')
        f.write('Can-Redefine-Classes: true\n')
        f.write('Can-Retransform-Classes: true\n\n')
    run([JAR, 'cfm', os.path.join(agent_dir, 'cqm-agent.jar'), manifest, '-C', stage, '.'])

    print('=== 4) 拷贝依赖与源码 ===')
    os.makedirs(os.path.join(OUT, 'lib'), exist_ok=True)
    shutil.copy2(H2, os.path.join(OUT, 'lib'))
    # demo 源码：服务端静态分析需要
    shutil.copytree(os.path.join(ROOT, 'demo', 'src'), os.path.join(OUT, 'demo', 'src'))

    for d in ('_agent_stage', '_boot_stage'):
        shutil.rmtree(os.path.join(OUT, d), ignore_errors=True)
    if os.path.isfile(manifest):
        os.remove(manifest)
    shutil.rmtree(ag, ignore_errors=True)

    print('=== 5) 校验 class 版本 ===')
    cls = os.path.join(OUT, 'server', 'classes', 'com', 'cqm', 'server', 'Server.class')
    with open(cls, 'rb') as f:
        head = f.read(8)
    major = int.from_bytes(head[6:8], 'big')
    print(f'  Server.class 主版本 {major}（52=Java8, 61=Java17, 66=Java22）'
          f'  {"OK" if major <= 61 else "!! 过高，线上跑不了"}')

    total = 0
    for dirpath, _, files in os.walk(OUT):
        for f in files:
            total += os.path.getsize(os.path.join(dirpath, f))
    print(f'\n部署包: {OUT}  共 {total/1024/1024:.1f} MB')
    for dirpath, dirnames, files in os.walk(OUT):
        rel = os.path.relpath(dirpath, OUT)
        if len(files) > 6:
            print(f'  {rel}/  ({len(files)} 个文件)')
        else:
            for f in sorted(files):
                print(f'  {os.path.join(rel, f) if rel != "." else f}'
                      f'  {os.path.getsize(os.path.join(dirpath, f)):,} B')
    return 0


if __name__ == '__main__':
    sys.exit(main())
