#!/usr/bin/env python3
"""会话之间说话的端到端测试（要真模型，不进 CI）：一台临时的真服务器，真模型自己决定调 delegate。

三件事，每件都有判定标准：
  T1 不等：主窗口交出去就结束这一轮；另一个会话做完，结果作为一条消息回来，把主窗口叫醒再跑一轮。
  T2 等：普通会话问另一个会话并等回话，回话就是工具结果；等的时候运行停在 AwaitingReply。
  T3 重启：等回话的时候服务器被杀掉再起。等的一方不被打断；对方那一轮被打断，“被重启打断”作为回话送到，等的一方接着走完。

每件事都查两本账：另一个会话的第一条事件是 SessionMessage（不是 UserPrompt），所以交来的话不算用户说的。
数据在临时目录里，跑完删掉；不碰 ~/.local/share/tantalum。结果写到 scenarios/results/<时间>-delegate-e2e-<模型>.md。
"""
import argparse, json, os, secrets, shutil, signal, sys, tempfile, time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import memory_eval as me  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
# 联网工具关掉：本机模型拿到一个简单问题也会一页接一页地抓网页停不下来（2026-11-16），那是另一个问题，这里只测会话之间传话
EXTRA = ("--tantalum.web.fetch.enabled=false",)


def journal(data, user, session):
    path = os.path.join(data, "sessions", user, session + ".jsonl")
    entries = []
    if os.path.exists(path):
        for line in open(path, encoding="utf-8"):
            try: entries.append(json.loads(line))
            except ValueError: pass
    return entries


def raw(data, user, session):
    path = os.path.join(data, "sessions", user, session + ".jsonl")
    return open(path, encoding="utf-8").read() if os.path.exists(path) else ""


def events(data, user, session):
    return [entry.get("type") for entry in journal(data, user, session) if entry.get("type")]


def runs(client, session):
    status, body = client.call("GET", f"/api/sessions/{session}/runs")
    return (body or {}).get("runs", []) if status == 200 else []


def sessions(client):
    status, body = client.call("GET", "/api/sessions?limit=100")
    return (body or {}).get("sessions", []) if status == 200 else []


def opened_by(client, opener):
    return [s for s in sessions(client) if s.get("parent") == opener]


def wait_for(condition, timeout, step=1.0):
    started = time.time()
    while time.time() - started < timeout:
        value = condition()
        if value: return value
        time.sleep(step)
    return None


def ended(client, session, count):
    """这个会话至少有 count 轮，而且最后一轮结束了。"""
    listed = runs(client, session)
    return listed if len(listed) >= count and listed[-1].get("endReason") else None


def submit(client, session, text, model):
    status, body = client.call("POST", f"/api/sessions/{session}/submissions",
                               {"requestId": secrets.token_hex(6), "mode": "prompt", "text": text, "model": model})
    if status != 200: raise SystemExit(f"submit {status}: {body}")


HOLD = {"on": False}


def user(admin, name):
    # --hold 时用固定密码，方便跑完以后登录进去看画面
    password = f"e2e-{name}-look-at-the-screen" if HOLD["on"] else secrets.token_urlsafe(18)
    status, body = admin.call("POST", "/api/users", {"name": name, "password": password, "admin": False})
    assert status == 200, (status, body)
    return password


def login(name, password):
    client = me.Client()
    assert client.call("POST", "/api/auth/login", {"username": name, "password": password})[0] == 200
    return client


class Report:
    def __init__(self): self.rows = []

    def check(self, case, what, passed, detail=""):
        self.rows.append((case, what, bool(passed), detail))
        print(f"{'PASS' if passed else 'FAIL'} {case} {what} {('· ' + str(detail)[:160]) if detail and not passed else ''}", flush=True)


def t1(report, admin, data, model, timeout):
    """不等：主窗口交出去，结果回来把它叫醒。"""
    name, window = "t1", "main-2026-11-16"
    client = login(name, user(admin, name))
    submit(client, window, "请调用 delegate 工具开一个新会话，task 写“计算 17 乘以 23 等于多少，只回答数字”。不要填 wait，也不要自己算。"
                           "工具返回以后先回复“已交出”。之后那个会话的答案回来时，把它的答案告诉我。", model)
    first = wait_for(lambda: ended(client, window, 1), timeout)
    report.check("T1", "主窗口第一轮结束", first, runs(client, window))
    if not first: return
    text = raw(data, name, window)
    report.check("T1", "主窗口的账里有一条送话命令（Send）", '"Send"' in text)
    report.check("T1", "主窗口没有停下来等（账里没有 AwaitingReply）", "AwaitingReply" not in text)
    opened = wait_for(lambda: opened_by(client, window), 30)
    report.check("T1", "开出了一个会话，列表里写着是主窗口开的", opened and len(opened) == 1, sessions(client))
    if not opened: return
    other = opened[0]["id"]
    done = wait_for(lambda: ended(client, other, 1), timeout)
    report.check("T1", "那个会话跑完了一轮", done, runs(client, other))
    kinds = events(data, name, other)
    report.check("T1", "那个会话的第一条事件是 SessionMessage，没有 UserPrompt", kinds[:1] == ["SessionMessage"] and "UserPrompt" not in kinds, kinds[:6])
    answer, _, _ = me.answer_of(data, name, other)
    report.check("T1", "那个会话算出了 391", "391" in answer, answer[:200])
    second = wait_for(lambda: ended(client, window, 2), timeout)
    report.check("T1", "结果回来把主窗口叫醒，跑了第二轮（没有人再说话）", second and len(second) == 2, runs(client, window))
    kinds = events(data, name, window)
    report.check("T1", "主窗口只有一条 UserPrompt，第二轮是 SessionMessage 开的", kinds.count("UserPrompt") == 1 and "SessionMessage" in kinds, kinds)
    answer, _, _ = me.answer_of(data, name, window)
    report.check("T1", "主窗口第二轮的回答里有 391", "391" in answer, answer[:200])


def t2(report, admin, data, model, timeout):
    """等：问另一个会话并等回话。"""
    name, session = "t2", "s-ordinary"
    client = login(name, user(admin, name))
    submit(client, session, "请调用 delegate 工具，task 写“用一句话说明冒泡排序是什么”，wait 设为 true。"
                            "拿到结果以后，用“对方回答：”开头把结果转述给我。", model)
    done = wait_for(lambda: ended(client, session, 1), timeout)
    report.check("T2", "这一轮结束了", done, runs(client, session))
    if not done: return
    text = raw(data, name, session)
    report.check("T2", "运行经过了“等会话回答”（账里有 AwaitingReply）", "AwaitingReply" in text)
    report.check("T2", "回话写成了工具结果（Answer from conversation）", "Answer from conversation s-" in text)
    listed = runs(client, session)
    report.check("T2", "只有一轮，正常完成", len(listed) == 1 and listed[0].get("endReason") == "COMPLETED", listed)
    answer, _, _ = me.answer_of(data, name, session)
    report.check("T2", "最后的回答转述了对方的话", "对方回答" in answer, answer[:200])
    opened = opened_by(client, session)
    report.check("T2", "开出的会话在列表里挂在它名下", len(opened) == 1, sessions(client))
    if opened:
        kinds = events(data, name, opened[0]["id"])
        report.check("T2", "那个会话的第一条事件是 SessionMessage，没有 UserPrompt", kinds[:1] == ["SessionMessage"] and "UserPrompt" not in kinds, kinds[:6])


def t3(report, state, data, model, timeout):
    """重启：等回话的时候服务器被杀掉再起。"""
    name, session = "t3", "s-waits"
    password = user(state["admin"], name)
    client = login(name, password)
    submit(client, session, "请调用 delegate 工具，task 写“写一篇一千五百字左右的散文，题目是秋天的山”，wait 设为 true。"
                            "拿到结果以后，如果对方没做成，不要重试，直接告诉我它为什么没做成；做成了就只回复“收到”。", model)
    waiting = wait_for(lambda: any(s["id"] == session and s.get("runStatus") == "AwaitingReply" for s in sessions(client)), timeout)
    report.check("T3", "重启前：这一轮停在 AwaitingReply", waiting, sessions(client))
    working = wait_for(lambda: [s for s in opened_by(client, session) if s.get("runStatus") not in ("idle", None)], 60)
    report.check("T3", "重启前：对方那一轮正在跑", working, sessions(client))
    if not (waiting and working): return
    other = working[0]["id"]
    state["server"].send_signal(signal.SIGKILL); state["server"].wait(timeout=30)
    state["server"], state["admin"] = me.start(data, state["password"], EXTRA)
    client = login(name, password)
    listed = sessions(client)
    mine = next((s for s in listed if s["id"] == session), {})
    done = wait_for(lambda: ended(client, session, 1), timeout)
    report.check("T3", "重启后：等的一方走完了这一轮", done, runs(client, session))
    text = raw(data, name, session)
    report.check("T3", "等的一方没有被“重启打断”结束", done and done[-1].get("endReason") == "COMPLETED" and len(done) == 1,
                 (mine.get("runStatus"), done))
    report.check("T3", "“被重启打断”作为回话送到，写成了工具结果", "interrupted by a server restart" in text)
    theirs = runs(client, other)
    report.check("T3", "对方被打断的那一轮以被取消结束，原因是重启", theirs and theirs[0].get("endReason") == "CANCELLED"
                 and "restart" in str(theirs[0].get("detail")), theirs)
    answer, _, _ = me.answer_of(data, name, session)
    report.check("T3", "模型把没做成的原因告诉了用户", any(word in answer for word in ("重启", "中断", "打断", "restart", "interrupted")), answer[:300])


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--model", default="qwen", choices=sorted(me.MODELS))
    parser.add_argument("--timeout", type=int, default=300)
    parser.add_argument("--only", default="", help="逗号分隔：T1,T2,T3")
    parser.add_argument("--port", type=int, default=8096)
    parser.add_argument("--keep", action="store_true")
    parser.add_argument("--hold", type=int, default=0, help="跑完以后服务器再开这么多秒（用固定密码），登录进去看画面")
    parser.add_argument("--shell-jar", default=""); parser.add_argument("--examples-jar", default="")
    args = parser.parse_args()
    me.JARS.update(shell=args.shell_jar, examples=args.examples_jar)
    me.PORT = args.port; me.BASE = f"http://127.0.0.1:{args.port}"
    HOLD["on"] = args.hold > 0
    wanted = set(args.only.split(",")) if args.only else {"T1", "T2", "T3"}
    model = me.MODELS[args.model]
    work = tempfile.mkdtemp(prefix="tantalum-delegate-e2e-")
    data = os.path.join(work, "data")
    state = {"password": secrets.token_urlsafe(18)}
    state["server"], state["admin"] = me.start(data, state["password"], EXTRA)
    report = Report()
    started = time.time()
    try:
        if "T1" in wanted: t1(report, state["admin"], data, model, args.timeout)
        if "T2" in wanted: t2(report, state["admin"], data, model, args.timeout)
        if "T3" in wanted: t3(report, state, data, model, args.timeout)
        if args.hold > 0:
            print(f"holding {args.hold}s at {me.BASE}; users t1, t2, t3 with password e2e-<name>-look-at-the-screen", flush=True)
            time.sleep(args.hold)
    finally:
        if state["server"].poll() is None:
            state["server"].send_signal(signal.SIGTERM)
            try: state["server"].wait(timeout=30)
            except Exception: state["server"].kill()
        if args.keep: print("kept", work)
        else: shutil.rmtree(work, ignore_errors=True)
    passed = sum(1 for row in report.rows if row[2])
    stamp = time.strftime("%m%d-%H%M") + "-delegate-e2e-" + args.model
    lines = [f"# {stamp}", "", f"模型 {args.model} · {passed}/{len(report.rows)} 项通过 · 用时 {round((time.time() - started) / 60, 1)} 分钟", "",
             "| 用例 | 判定 | 结果 | 没过时看到的 |", "|---|---|---|---|"]
    for case, what, ok, detail in report.rows:
        lines.append(f"| {case} | {what} | {'过' if ok else '**没过**'} | {'' if ok else str(detail)[:200].replace('|', '/')} |")
    out = os.path.join(HERE, "results", stamp + ".md")
    os.makedirs(os.path.dirname(out), exist_ok=True)
    open(out, "w", encoding="utf-8").write("\n".join(lines) + "\n")
    print(f"{passed}/{len(report.rows)} -> {out}")
    sys.exit(0 if passed == len(report.rows) else 1)


if __name__ == "__main__":
    main()
