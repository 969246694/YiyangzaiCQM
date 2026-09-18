# -*- coding: utf-8 -*-
"""端到端验证：启动服务端 -> 挂探针跑示例应用 -> 逐项检查 8 个功能模块。

这是论文第 6 章"功能测试"的真实执行脚本，输出即为实测结果。
"""
import json
import os
import re
import subprocess
import sys
import time
import urllib.request

sys.stdout.reconfigure(encoding='utf-8')
ROOT = r"F:\deepseek对话\code-quality-monitor"
H2 = os.path.join(ROOT, 'lib', 'h2-2.2.224.jar')
SERVER = 'http://127.0.0.1:8080'
PASS, FAIL = [], []


def get(path, timeout=15):
    with urllib.request.urlopen(SERVER + path, timeout=timeout) as r:
        return r.read().decode('utf-8')


def check(name, cond, detail=''):
    (PASS if cond else FAIL).append(name)
    print(f'  [{"通过" if cond else "失败"}] {name}  {detail}')


def kill_server():
    r = subprocess.run(['powershell', '-NoProfile', '-Command',
                        "Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | "
                        "Where-Object { $_.CommandLine -like '*com.cqm.server.Server*' } | "
                        "ForEach-Object { $_.ProcessId }"],
                       capture_output=True, text=True, encoding='utf-8', errors='replace')
    for pid in [p.strip() for p in r.stdout.split() if p.strip().isdigit()]:
        subprocess.run(['taskkill', '/F', '/PID', pid], capture_output=True)


def run_demo(seconds=15):
    args = ['java', '-javaagent:dist/cqm-agent.jar',
            '-Dcqm.packages=com.demo', '-Dcqm.server=' + SERVER,
            '-Dcqm.interval=3000', '-Dcqm.app=demo-app',
            '-cp', 'demo/classes', 'com.demo.BizService', str(seconds)]
    subprocess.run(args, cwd=ROOT, capture_output=True, text=True,
                   encoding='utf-8', errors='replace')


def main():
    print('=== 0) 清理旧进程与旧数据 ===')
    kill_server()
    time.sleep(1)
    for f in ('cqm.mv.db', 'cqm.trace.db'):
        p = os.path.join(ROOT, 'data', f)
        if os.path.exists(p):
            os.remove(p)
    # 预置静态分析样本：故意写入一个高复杂度/空catch/未关闭资源的方法，验证规则能检出
    fixture = os.path.join(ROOT, 'demo', 'src', 'com', 'demo', 'CodeSmellSample.java')
    with open(fixture, 'w', encoding='utf-8') as f:
        f.write('''package com.demo;

import java.io.FileInputStream;
import java.io.InputStream;

/** 刻意编写的坏味道样本，用于验证静态规则检测能力（非业务代码）。 */
public class CodeSmellSample {

    /** 圈复杂度 > 15：多重嵌套分支。 */
    public int highComplexity(int a, int b, int c) {
        int r = 0;
        if (a > 0) {
            if (b > 0) {
                if (c > 0) {
                    r += 1;
                } else if (c < -10) {
                    r += 2;
                } else {
                    r -= 1;
                }
            } else if (b < -10) {
                if (c > 5) {
                    r += 3;
                } else if (c > 0) {
                    r += 4;
                } else {
                    r += 5;
                }
            } else {
                r += 6;
            }
        } else if (a < -10) {
            if (b > 10) {
                r += 7;
            } else if (b > 0) {
                r += 8;
            } else {
                r += 9;
            }
        } else {
            r += 10;
        }
        for (int i = 0; i < 3; i++) {
            if (i % 2 == 0) {
                r += i;
            }
        }
        int k = 0;
        while (k < 5) {
            if (k > 2 && r > 0) {
                r -= k;
            } else if (k == 1 || r < 0) {
                r += k;
            }
            k++;
        }
        r += (r > 100) ? 100 : (r < -100 ? -100 : r);
        switch (a) {
            case 1:
                r += 1;
                break;
            case 2:
                r += 2;
                break;
            case 3:
                r += 3;
                break;
            default:
                break;
        }
        return r;
    }

    /** 空 catch：异常被静默吞掉。 */
    public void emptyCatch() {
        try {
            Integer.parseInt("not-a-number");
        } catch (NumberFormatException e) {
        }
    }

    /** 资源未关闭：打开流但未关闭、也未使用 try-with-resources。 */
    public void unclosedResource(String path) throws Exception {
        InputStream in = new FileInputStream(path);
        int b = in.read();
        if (b < 0) {
            throw new IllegalStateException("empty");
        }
    }
}
''')
    print('  已写入坏味道样本: CodeSmellSample.java')

    print('=== 1) 启动服务端（含启动时自动静态分析）===')
    srv = subprocess.Popen(['java', '-cp', f'server/classes;{H2}', 'com.cqm.server.Server', '8080'],
                           cwd=ROOT, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                           text=True, encoding='utf-8', errors='replace')
    started = False
    for _ in range(40):
        time.sleep(0.5)
        try:
            get('/api/stats', timeout=2)
            started = True
            break
        except Exception:
            continue
    check('服务端启动', started, SERVER)
    if not started:
        srv.terminate()
        return
    time.sleep(2)

    print('=== 2) 挂探针运行示例应用 15 秒 ===')
    run_demo(15)
    time.sleep(2)

    print('=== 3) 逐项验证功能模块 ===')
    ov = json.loads(get('/api/overview'))
    static = json.loads(get('/api/static'))
    vio = json.loads(get('/api/violations'))
    score = json.loads(get('/api/score'))
    alarms = json.loads(get('/api/alarms'))
    table = json.loads(get('/api/table'))

    # 模块1 探针接入与运行期采集
    check('① 探针接入与运行期采集', len(table) > 0,
          f'采集到 {len(table)} 个方法')
    # 模块2 异常统计正确性
    risky = [m for m in table if m['methodName'] == 'riskyOperation']
    check('② 异常路径统计', bool(risky) and risky[0]['errors'] > 0,
          f"riskyOperation 异常 {risky[0]['errors'] if risky else 0} 次、"
          f"异常率 {risky[0]['errRate'] if risky else 0:.2f}%")
    # 模块3 静态分析
    check('③ 静态代码分析', len(static) >= 5,
          f"分析出 {len(static)} 个方法，平均圈复杂度 {ov['avgComplexity']}")
    # 模块4 规则检测（4 条静态规则 + 动态规则）
    rules = {v['ruleName'] for v in vio}
    need = {'方法复杂度过高', '异常被忽略', '资源未关闭'}
    check('④ 规则检测', need.issubset(rules),
          f"检出 {len(vio)} 条违规，覆盖规则: {'、'.join(sorted(rules))}")
    # 模块5 质量评分
    check('⑤ 质量评分', score['total'] > 0 and len(score['items']) >= 5,
          f"得分 {score['total']}（{score['grade']}），评分项 {len(score['items'])} 项")
    # 模块6 覆盖率
    check('⑥ 方法覆盖率', ov['instrumented'] > 0,
          f"{ov['covered']}/{ov['instrumented']} = {ov['coverage']}%")
    # 模块7 告警
    check('⑦ 阈值告警', len(alarms) > 0, f"产生 {len(alarms)} 条告警")
    # 模块8 持久化（H2 文件已生成）
    db_file = os.path.join(ROOT, 'data', 'cqm.mv.db')
    check('⑧ 数据库持久化', os.path.exists(db_file),
          f"{os.path.getsize(db_file):,} 字节" if os.path.exists(db_file) else '未生成')
    # 模块9 报告导出
    rep = json.loads(get('/api/report'))
    rep_ok = rep.get('ok') and os.path.exists(rep.get('path', ''))
    check('⑨ 质量报告导出', rep_ok,
          f"{os.path.getsize(rep['path']):,} 字节" if rep_ok else rep)

    print()
    print('=' * 60)
    print(f'功能测试结果：通过 {len(PASS)} 项，失败 {len(FAIL)} 项')
    if FAIL:
        print('失败项:', '、'.join(FAIL))
    print('=' * 60)
    print()
    print('关键指标快照（写入论文第 6 章）:')
    print(f"  静态分析方法数        {ov['staticMethods']}")
    print(f"  平均圈复杂度          {ov['avgComplexity']}")
    print(f"  运行期监控方法数      {ov['methods']}")
    print(f"  方法覆盖率            {ov['coverage']}%  ({ov['covered']}/{ov['instrumented']})")
    print(f"  违规记录数            {ov['violations']}  (高 {ov['high']} / 中 {ov['mid']})")
    print(f"  质量得分              {score['total']}  ({score['grade']})")
    print(f"  告警条数              {len(alarms)}")
    print(f"  上报次数              {ov['reports']}   累计 {ov['reportBytes']:,} 字节")
    print(f"  报告文件              {rep.get('path')}")
    print()
    print('评分构成:')
    for it in score['items']:
        print('   ', it)
    print()
    print('违规明细:')
    for v in vio[:15]:
        print(f"    [{v['level']}] {v['ruleName']} @ {v['location']}: {v['detail'][:70]}")
    print()
    print('服务端日志（最近 20 行）:')
    srv.terminate()
    try:
        out = srv.stdout.read()
        for line in out.strip().splitlines()[-20:]:
            print('   ', line)
    except Exception as e:
        print('    读取失败:', e)
    print()
    print('服务端仍在运行（PID %d），浏览器打开 %s/ 查看页面' % (srv.pid, SERVER))
    with open(os.path.join(ROOT, 'docs', 'functional-test-result.json'), 'w', encoding='utf-8') as f:
        json.dump({'overview': ov, 'score': score, 'violations': vio, 'alarms': alarms,
                   'static': static, 'runtime': table,
                   'passed': PASS, 'failed': FAIL}, f, ensure_ascii=False, indent=1)
    print('实测结果已保存: docs/functional-test-result.json')


if __name__ == '__main__':
    main()
