# -*- coding: utf-8 -*-
"""生成论文插图：系统总体架构图、平台用例图。

用 matplotlib 绘制，字体使用系统自带的微软雅黑/黑体，输出高分辨率 PNG，
可直接插入论文（Word 中按 14.5cm 宽排版）。
"""
import os
import sys

import matplotlib
matplotlib.use('Agg')
import matplotlib.patches as mpatches  # noqa: E402
import matplotlib.pyplot as plt  # noqa: E402
from matplotlib.patches import FancyArrowPatch, FancyBboxPatch  # noqa: E402

sys.stdout.reconfigure(encoding='utf-8')
plt.rcParams['font.sans-serif'] = ['Microsoft YaHei', 'SimHei']
plt.rcParams['axes.unicode_minus'] = False

OUT = r"F:\deepseek对话\code-quality-monitor\docs\figures"
os.makedirs(OUT, exist_ok=True)

HEAD = '#2f4f6f'      # 深蓝
LAYER = ['#e8f1fb', '#e9f7ee', '#fdf3e3']   # 三层底色
EDGE = ['#5b8fc9', '#63b07a', '#d9a441']


def box(ax, x, y, w, h, text, fc, ec, fs=10.5, bold=False, radius=0.02):
    ax.add_patch(FancyBboxPatch((x, y), w, h,
                                boxstyle=f"round,pad=0.006,rounding_size={radius}",
                                linewidth=1.3, edgecolor=ec, facecolor=fc, zorder=2))
    ax.text(x + w / 2, y + h / 2, text, ha='center', va='center',
            fontsize=fs, color='#1c2b3a', zorder=3,
            fontweight='bold' if bold else 'normal', linespacing=1.55)


def arrow(ax, p1, p2, text='', color=HEAD, style='-|>', ls='-', fs=9, dx=0, dy=0):
    ax.add_patch(FancyArrowPatch(p1, p2, arrowstyle=style, mutation_scale=15,
                                 linewidth=1.3, color=color, linestyle=ls,
                                 shrinkA=2, shrinkB=2, zorder=4))
    if text:
        ax.text((p1[0] + p2[0]) / 2 + dx, (p1[1] + p2[1]) / 2 + dy, text,
                ha='center', va='center', fontsize=fs, color=color, zorder=5,
                bbox=dict(boxstyle='round,pad=0.22', fc='white', ec='none', alpha=0.92))


# ═══════════════ 图 4-1 系统总体架构图 ═══════════════
def arch():
    fig, ax = plt.subplots(figsize=(9.6, 7.4), dpi=200)
    ax.set_xlim(0, 10)
    ax.set_ylim(0, 10)
    ax.axis('off')

    # ── 被监控应用进程（探针端）──
    ax.add_patch(FancyBboxPatch((0.5, 6.05), 9.0, 3.45,
                                boxstyle="round,pad=0.01,rounding_size=0.04",
                                linewidth=1.6, edgecolor=EDGE[0], facecolor=LAYER[0], zorder=1))
    ax.text(0.75, 9.20, '被监控应用进程（探针端）', fontsize=11.5, color=EDGE[0],
            fontweight='bold', zorder=3)

    box(ax, 0.9, 8.05, 2.7, 0.92,
        'Java Agent\npremain / agentmain', '#ffffff', EDGE[0], fs=10)
    box(ax, 3.85, 8.05, 2.7, 0.92,
        'Byte Buddy\n类匹配与字节码改写', '#ffffff', EDGE[0], fs=10)
    box(ax, 6.80, 8.05, 2.4, 0.92,
        '业务目标类\n（方法插桩）', '#ffffff', EDGE[0], fs=10)
    arrow(ax, (3.60, 8.51), (3.85, 8.51))
    arrow(ax, (6.55, 8.51), (6.80, 8.51))

    box(ax, 0.9, 6.45, 2.7, 1.15,
        '采集切点 Advice\n进入记录时间戳\n退出计算耗时/异常', '#ffffff', EDGE[0], fs=9.5)
    box(ax, 3.85, 6.45, 2.7, 1.15,
        '指标聚合器\n本地按「类#方法」聚合\n采样 · 直方图', '#ffffff', EDGE[0], fs=9.5)
    box(ax, 6.80, 6.45, 2.4, 1.15,
        '上报线程\n异步批量上报\n失败静默重试', '#ffffff', EDGE[0], fs=9.5)
    arrow(ax, (3.60, 7.02), (3.85, 7.02))
    arrow(ax, (6.55, 7.02), (6.80, 7.02))
    arrow(ax, (7.95, 8.02), (7.95, 7.62), color='#8a97a8', ls='--')

    # ── 服务端 ──
    ax.add_patch(FancyBboxPatch((0.5, 2.85), 9.0, 2.85,
                                boxstyle="round,pad=0.01,rounding_size=0.04",
                                linewidth=1.6, edgecolor=EDGE[1], facecolor=LAYER[1], zorder=1))
    ax.text(0.75, 5.42, '服务端', fontsize=11.5, color=EDGE[1], fontweight='bold', zorder=3)

    box(ax, 0.9, 4.10, 2.7, 1.05, '数据接收与清洗\n字段校验 · 批量写入', '#ffffff', EDGE[1], fs=9.5)
    box(ax, 3.85, 4.10, 2.7, 1.05, '指标聚合与存储\n原始表 · 汇总表', '#ffffff', EDGE[1], fs=9.5)
    box(ax, 6.80, 4.10, 2.4, 1.05, '规则检测与评分\n阈值判定 · 加权评分', '#ffffff', EDGE[1], fs=9.5)
    arrow(ax, (3.60, 4.62), (3.85, 4.62), color=EDGE[1])
    arrow(ax, (6.55, 4.62), (6.80, 4.62), color=EDGE[1])
    box(ax, 3.85, 3.05, 5.35, 0.82, 'REST 接口（/api/metrics、/api/table、/api/stats）',
        '#ffffff', EDGE[1], fs=9.5)

    # ── 前端 ──
    ax.add_patch(FancyBboxPatch((0.5, 0.55), 9.0, 1.85,
                                boxstyle="round,pad=0.01,rounding_size=0.04",
                                linewidth=1.6, edgecolor=EDGE[2], facecolor=LAYER[2], zorder=1))
    ax.text(0.75, 2.20, '前端（浏览器）', fontsize=11.5, color=EDGE[2],
            fontweight='bold', zorder=3)
    box(ax, 0.9, 0.80, 2.7, 1.10, '项目总览\n质量得分 · 告警数', '#ffffff', EDGE[2], fs=9.5)
    box(ax, 3.85, 0.80, 2.7, 1.10, '指标趋势\n方法级明细', '#ffffff', EDGE[2], fs=9.5)
    box(ax, 6.80, 0.80, 2.4, 1.10, '规则与权重配置\n报告导出', '#ffffff', EDGE[2], fs=9.5)

    # ── 跨层数据流 ──
    arrow(ax, (9.75, 6.45), (9.75, 5.70), '', color=HEAD)
    arrow(ax, (8.0, 5.72), (8.0, 6.42), '', color=HEAD)
    ax.text(9.62, 6.10, '上报\nJSON', ha='right', va='center', fontsize=8.6,
            color=HEAD, linespacing=1.4)
    ax.text(7.66, 6.10, '接收\n响应', ha='right', va='center', fontsize=8.6,
            color=HEAD, linespacing=1.4)

    arrow(ax, (9.75, 3.02), (9.75, 2.42), '', color=HEAD)
    arrow(ax, (0.25, 2.44), (0.25, 3.04), '', color=HEAD)
    ax.text(9.62, 2.72, '查询', ha='right', va='center', fontsize=8.6, color=HEAD)
    ax.text(0.12, 2.72, '呈现', ha='left', va='center', fontsize=8.6, color=HEAD)

    ax.text(5.0, 0.16, '数据流：业务方法执行 → 插桩采集 → 探针本地聚合 → 异步批量上报 '
                       '→ 服务端清洗聚合 → 规则检测与评分 → 前端可视化',
            ha='center', va='center', fontsize=9.2, color='#5b6777')
    plt.tight_layout()
    p = os.path.join(OUT, 'Fig4-1-architecture.png')
    fig.savefig(p, bbox_inches='tight', facecolor='white')
    plt.close(fig)
    print('  ', p, f'{os.path.getsize(p):,} 字节')


# ═══════════════ 图 3-1 平台用例图 ═══════════════
def usecase():
    fig, ax = plt.subplots(figsize=(10.4, 6.8), dpi=200)
    ax.set_xlim(0, 10.6)
    ax.set_ylim(0, 10)
    ax.axis('off')

    # 系统边界（加宽，保证左右两列用例不重叠）
    ax.add_patch(FancyBboxPatch((2.62, 0.55), 5.36, 8.9,
                                boxstyle="round,pad=0.02,rounding_size=0.05",
                                linewidth=1.6, edgecolor=HEAD, facecolor='#f7fafd', zorder=1))
    ax.text(5.30, 9.16, '代码质量监控平台', ha='center', va='center',
            fontsize=12, color=HEAD, fontweight='bold', zorder=3)

    EW, EH = 2.42, 0.74          # 椭圆宽高
    DX, MX = 3.98, 6.62          # 开发者列 / 管理者列 中心 x
    dev_cases = [
        (7.62, '登记被监控项目'),
        (6.60, '配置探针采集范围'),
        (5.58, '查看运行期指标'),
        (4.56, '查看方法级明细'),
        (3.54, '查看告警信息'),
        (2.52, '导出质量报告'),
    ]
    mgr_cases = [
        (6.60, '配置质量规则与阈值'),
        (5.58, '调整指标权重'),
        (4.56, '查看质量得分与趋势'),
        (3.54, '审批质量门禁结果'),
    ]
    for y, t in dev_cases:
        ax.add_patch(mpatches.Ellipse((DX, y), EW, EH, linewidth=1.2,
                                      edgecolor=EDGE[0], facecolor='#eef5fd', zorder=2))
        ax.text(DX, y, t, ha='center', va='center', fontsize=9.4, zorder=3)
    for y, t in mgr_cases:
        ax.add_patch(mpatches.Ellipse((MX, y), EW, EH, linewidth=1.2,
                                      edgecolor=EDGE[2], facecolor='#fdf6e9', zorder=2))
        ax.text(MX, y, t, ha='center', va='center', fontsize=9.4, zorder=3)

    # 参与者（火柴人）
    def actor(x, y, label):
        ax.add_patch(mpatches.Circle((x, y + 0.62), 0.20, linewidth=1.4,
                                     edgecolor='#3d4c5c', facecolor='white', zorder=3))
        ax.plot([x, x], [y + 0.42, y - 0.12], color='#3d4c5c', linewidth=1.4, zorder=3)
        ax.plot([x - 0.36, x + 0.36], [y + 0.20, y + 0.20], color='#3d4c5c',
                linewidth=1.4, zorder=3)
        ax.plot([x, x - 0.30], [y - 0.12, y - 0.66], color='#3d4c5c', linewidth=1.4, zorder=3)
        ax.plot([x, x + 0.30], [y - 0.12, y - 0.66], color='#3d4c5c', linewidth=1.4, zorder=3)
        ax.text(x, y - 1.02, label, ha='center', va='center', fontsize=10.5,
                color='#1c2b3a', fontweight='bold', zorder=3)

    actor(0.85, 5.15, '开发者')
    actor(9.75, 5.15, '项目管理者')

    # 关联线：连到椭圆左右端点，避免穿过椭圆
    for y, _ in dev_cases:
        ax.plot([1.21, DX - EW / 2], [5.15, y], color='#9aa8b8', linewidth=0.9, zorder=1.5)
    for y, _ in mgr_cases:
        ax.plot([9.39, MX + EW / 2], [5.15, y], color='#9aa8b8', linewidth=0.9, zorder=1.5)

    ax.text(5.30, 0.16, '关联线表示参与者可执行的用例；蓝色为开发者用例，橙色为项目管理者用例',
            ha='center', va='center', fontsize=9, color='#5b6777')
    plt.tight_layout()
    p = os.path.join(OUT, 'Fig3-1-usecase.png')
    fig.savefig(p, bbox_inches='tight', facecolor='white')
    plt.close(fig)
    print('  ', p, f'{os.path.getsize(p):,} 字节')


if __name__ == '__main__':
    print('生成论文插图:')
    arch()
    usecase()
