#!/usr/bin/env python3
"""对着账核对状态有没有闭环（读会话记录，不起服务器）：

  1. 每一轮运行都走到了结束，没有停在半路（等模型、等工具、等用户回答、等会话回答）
  2. 每一条“送话”命令，对方会话的账里都有对应的“会话来话”事件（送到了）
  3. 每一句交出去的活，都有一句回话送回来（成功或失败都算），而且回话被接住了（进了工具结果，或者开了新的一轮）
  4. 信箱是空的：没有来了却没处理的话，也没有送出去却没得到确认的话

用法：python3 audit_states.py <数据目录>/sessions/<用户>
"""
import collections, glob, json, os, sys


def main(folder):
    runs = {}                                   # 运行 -> 最后的阶段
    sends, arrived = {}, {}                     # 话的编号 -> 送话命令 / 来话事件
    inbox = {}                                  # 会话 -> 收件箱里还有几句
    opened_by = collections.defaultdict(list)   # 来话开的运行
    for path in sorted(glob.glob(os.path.join(folder, "*.jsonl"))):
        session = os.path.basename(path)[:-6]
        active = None   # 这个会话现在正在跑的那一轮：有的事件（回答卡片）不写是哪一轮，就是它
        for line in open(path, encoding="utf-8"):
            try: entry = json.loads(line)
            except ValueError: continue
            if "type" not in entry: continue
            result, run = entry.get("result") or {}, entry.get("run")
            state = result.get("session") or {}
            if "inbox" in state:
                box = state["inbox"] or {}
                # 信箱：等着处理的来话，和送出去还没确认的去话（旧账里信箱只是一个列表）
                inbox[session] = (len(box.get("waiting") or []), len(box.get("unconfirmed") or [])) if isinstance(box, dict) else (len(box), 0)
            if state.get("activeRun") and entry["type"] == "SessionMessage":
                opened_by[entry["payload"]["id"]["value"]].append(state["activeRun"].get("value"))
            for delta in result.get("runs") or []:
                ref = delta.get("ref")
                name = run if ref in (None, "\u0000=") else (ref.get("run", {}).get("value") if isinstance(ref, dict) else ref)
                if name is None: name = state["activeRun"].get("value") if isinstance(state.get("activeRun"), dict) else active
                phase = delta.get("phase")
                if phase: runs[(session, name)] = phase.get("kind") + (":" + str(phase.get("endReason") or phase.get("reason") or "") if phase.get("kind") == "Ended" else "")
            if "activeRun" in state: active = state["activeRun"].get("value") if isinstance(state["activeRun"], dict) else None
            for command in result.get("commands") or []:
                if command.get("type") == "Send":
                    message = command["message"]
                    sends[message["id"]["value"]] = {"from": session, "to": command["to"]["value"], "reply": message.get("answers") is not None,
                                                     "withdraw": message.get("withdraw") is True,
                                                     "answers": (message.get("answers") or {}).get("value") if isinstance(message.get("answers"), dict) else message.get("answers")}
            if entry["type"] == "SessionMessage":
                payload = entry["payload"]
                answers = payload.get("answers")
                arrived[payload["id"]["value"]] = {"in": session, "reply": answers is not None,
                                                  "answers": answers.get("value") if isinstance(answers, dict) else answers,
                                                  "handled": json.dumps(result, ensure_ascii=False)[:2000]}
    problems = []
    unfinished = {key: phase for key, phase in runs.items() if not phase.startswith("Ended")}
    for (session, run), phase in unfinished.items(): problems.append(f"运行没有结束：{session} {run} 停在 {phase}")
    for identifier, send in sends.items():
        if identifier not in arrived: problems.append(f"送出去没送到：{identifier}（{send['from']} → {send['to']}）")
    # 撤回没有回话：它让对方把还没做完的活停下，那些活各自照常回话
    requests = {identifier: send for identifier, send in sends.items() if not send["reply"] and not send["withdraw"]}
    withdrawals = sum(1 for send in sends.values() if send["withdraw"])
    answered = {send["answers"] for send in sends.values() if send["reply"]} | {item["answers"] for item in arrived.values() if item["reply"]}
    for identifier, send in requests.items():
        if identifier not in answered: problems.append(f"交出去的活没有回话：{identifier}（{send['from']} → {send['to']}）")
    for session, (waiting, unconfirmed) in inbox.items():
        if waiting: problems.append(f"信箱里还有 {waiting} 句来话没处理：{session}")
        if unconfirmed: problems.append(f"还有 {unconfirmed} 句送出去的话没得到确认：{session}")
    ended = collections.Counter(phase for phase in runs.values())
    print(f"会话 {len(glob.glob(os.path.join(folder, '*.jsonl')))} 个，运行 {len(runs)} 轮：{dict(ended)}")
    print(f"送话命令 {len(sends)} 条（交活 {len(requests)}，回话 {len(sends) - len(requests) - withdrawals}，撤回 {withdrawals}）；会话来话事件 {len(arrived)} 条")
    print("问题：" + ("没有" if not problems else ""))
    for problem in problems: print("  - " + problem)
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1]))
