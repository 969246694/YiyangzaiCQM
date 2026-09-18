# -*- coding: utf-8 -*-
"""性能测试：对比"关闭探针 / 开启探针"两种情况下的吞吐量与平均耗时。

输出可直接用于论文表 6-2。
"""
import os
import re
import statistics
import subprocess
import sys

sys.stdout.reconfigure(encoding='utf-8')
ROOT = r"F:\deepseek对话\code-quality-monitor"
SECONDS = 15
WARMUP = 3
ROUNDS = 3


def run_bench(with_agent, mode, round_no):
    args = ['java']
    if with_agent:
        args += [
            '-javaagent:dist/cqm-agent.jar',
            '-Dcqm.packages=com.demo',
            '-Dcqm.server=http://127.0.0.1:8080',
            '-Dcqm.interval=2000',
            '-Dcqm.app=demo-app',
        ]
    args += ['-cp', 'demo/classes', 'com.demo.Bench', mode, str(SECONDS), str(WARMUP)]
    r = subprocess.run(args, cwd=ROOT, capture_output=True, text=True,
                       encoding='utf-8', errors='replace')
    out = r.stdout + r.stderr
    m = re.search(r'BENCH_RESULT tag=\S+ ops=(\d+) elapsedSec=([\d.]+) throughput=([\d.]+) '
                  r'avgMicros=([\d.]+) heapUsedMb=(\d+)', out)
    if not m:
        print('  第%d轮解析失败，原始输出：' % round_no)
        print('\n'.join(out.splitlines()[:12]))
        return None
    return {
        'ops': int(m.group(1)),
        'sec': float(m.group(2)),
        'throughput': float(m.group(3)),
        'avgMicros': float(m.group(4)),
        'heapMb': int(m.group(5)),
    }


def summarize(label, samples):
    print(f'\n=== {label}（{len(samples)} 轮，每轮 {SECONDS}s，预热 {WARMUP}s）===')
    for i, s in enumerate(samples, 1):
        print(f'  第{i}轮: 调用 {s["ops"]:>9,} 次  吞吐 {s["throughput"]:>10,.1f} 次/秒  '
              f'平均 {s["avgMicros"]:.3f} µs/次  堆占用 {s["heapMb"]} MB')
    tp = statistics.median(s['throughput'] for s in samples)
    avg = statistics.median(s['avgMicros'] for s in samples)
    heap = statistics.median(s['heapMb'] for s in samples)
    print(f'  中位数: 吞吐 {tp:,.1f} 次/秒   平均 {avg:.3f} µs/次   堆占用 {heap:.0f} MB')
    return tp, avg, heap


def main():
    for mode, title in (('micro', '微基准：纯 CPU 短方法（放大插桩开销占比）'),
                        ('typical', '典型业务负载：含耗时方法')):
        print('\n' + '#' * 60)
        print('# ' + title)
        print('#' * 60)
        base, plug = [], []
        for i in range(1, ROUNDS + 1):
            s = run_bench(False, mode, i)
            if s:
                base.append(s)
                print(f'  关闭探针 第{i}轮: 吞吐 {s["throughput"]:>12,.1f} 次/秒  '
                      f'平均 {s["avgMicros"]:.3f} µs/次')
        for i in range(1, ROUNDS + 1):
            s = run_bench(True, mode, i)
            if s:
                plug.append(s)
                print(f'  开启探针 第{i}轮: 吞吐 {s["throughput"]:>12,.1f} 次/秒  '
                      f'平均 {s["avgMicros"]:.3f} µs/次')
        if not base or not plug:
            print('  数据不足，跳过该场景')
            continue
        tb, ab, hb = summarize('关闭探针', base)
        tp, ap, hp = summarize('开启探针', plug)
        print(f'\n  对比（{mode}）：')
        print(f'    吞吐量    {tb:>12,.1f} -> {tp:>12,.1f} 次/秒   '
              f'{(tp - tb) / tb * 100:+.1f}%')
        print(f'    平均耗时  {ab:>12.3f} -> {ap:>12.3f} µs/次     '
              f'{(ap - ab) / ab * 100:+.1f}%')
        print(f'    单次固定开销 ≈ {ap - ab:.3f} µs/次')
        print(f'    堆占用    {hb:>12.0f} -> {hp:>12.0f} MB        '
              f'{(hp - hb) / max(hb, 1) * 100:+.1f}%')


if __name__ == '__main__':
    main()
