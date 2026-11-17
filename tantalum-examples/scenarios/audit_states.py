#!/usr/bin/env python3
"""对着账核对状态有没有闭环（读会话记录，不起服务器）：每一轮运行都走到了结束，没有停在半路（等模型、等工具、等用户回答）。

用法：python3 audit_states.py <数据目录>/sessions/<用户>
"""
import glob, json, os, sys


def main(folder):
    runs = {}   # 运行 -> 最后的阶段
    for path in sorted(glob.glob(os.path.join(folder, "*.jsonl"))):
        session = os.path.basename(path)[:-6]
        active = None   # 这个会话现在正在跑的那一轮：有的事件（回答卡片）不写是哪一轮，就是它
        for line in open(path, encoding="utf-8"):
            try: entry = json.loads(line)
            except ValueError: continue
            if "type" not in entry: continue
            result, run = entry.get("result") or {}, entry.get("run")
            state = result.get("session") or {}
            for delta in result.get("runs") or []:
                ref = delta.get("ref")
                name = run if ref in (None, "\u0000=") else (ref.get("run", {}).get("value") if isinstance(ref, dict) else ref)
                if name is None: name = state["activeRun"].get("value") if isinstance(state.get("activeRun"), dict) else active
                phase = delta.get("phase")
                if phase: runs[(session, name)] = phase.get("kind") + (":" + str(phase.get("endReason") or phase.get("reason") or "") if phase.get("kind") == "Ended" else "")
            if "activeRun" in state: active = state["activeRun"].get("value") if isinstance(state["activeRun"], dict) else None
    problems = [f"运行没有结束：{session} {run} 停在 {phase}" for (session, run), phase in runs.items() if not phase.startswith("Ended")]
    ended = collections_count(runs)
    print(f"会话 {len(glob.glob(os.path.join(folder, '*.jsonl')))} 个，运行 {len(runs)} 轮：" + "、".join(f"{reason} {count}" for reason, count in sorted(ended.items())))
    for problem in problems: print("问题：" + problem)
    if not problems: print("没有发现问题")
    return problems


def collections_count(runs):
    counts = {}
    for phase in runs.values(): counts[phase] = counts.get(phase, 0) + 1
    return counts


if __name__ == "__main__":
    sys.exit(1 if main(sys.argv[1]) else 0)
