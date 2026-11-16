#!/usr/bin/env python3
"""整个系统一起跑的端到端测试（要真模型，不进 CI）：记忆（自动记笔记、找回）、主窗口、交给别的会话、工作区全部开着，
一台临时的真服务器接着测试电脑和样例项目。说的都是人平常会说的话，不提任何工具名、不提“交给别的会话”“工作区”这些做法，
做法由模型自己定。

一个人五天的工作（同一台服务器、同一个人，后面几天靠前面留下的记忆、会话和工作区）：
  第一天（主窗口）：自我介绍和习惯 → 看一个数 → 改它并跑测试 → 同一处接着做一件事
  第二天（新的主窗口）：问昨天定了什么 → 另一个目录里的活 → 两边各查一件事 → 一件要用到“习惯”的活 → 让它忘掉一条
  第三天（新的主窗口 + 一个普通会话）：忘掉的不该再出现；普通会话里也认得人、知道现状
  第四天：一句话两件事（两个目录）→ 说得含糊（该先问我）→ 反悔，只撤一处
  第五天：问昨天动了什么，不能把撤回的说成现状

判定看三样：电脑上的文件真的变了没有、会话和工作区是怎么安排的、回答里有没有该有的（不该有的）内容。
全程的原话、回答、每个会话用过的工具都写进结果文件，供人读。

用法：python3 story.py --model claude        结果在 scenarios/results/<时间>-story-<模型>.md
"""
import argparse, collections, glob, json, os, re, secrets, subprocess, sys, time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import fixture  # noqa: E402
import run  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
CAPTURE = {"claude": "claude-sonnet-5-5", "haiku": "claude-haiku-5-5", "opus": "claude-opus-5-5", "fable": "claude-fable-5-2"}
EDITING = {"edit_file", "write_file", "bash"}


def entries(session):
    path = f"{run.DATA}/sessions/dev/{session}.jsonl"
    found = []
    if os.path.exists(path):
        for line in open(path, encoding="utf-8"):
            try: found.append(json.loads(line))
            except ValueError: pass
    return found


def replies(session, start=0):
    """这个会话从第 start 条事件起：模型说的话（每次回复的文字）、调过的工具（名字和参数）、token。"""
    texts, calls, tokens = [], [], collections.Counter()
    for entry in entries(session)[start:]:
        if entry.get("type") != "ModelReplied": continue
        payload = entry.get("payload") or {}
        for key in ("input", "output", "cacheRead", "cacheWrite"): tokens[key] += (payload.get("usage") or {}).get(key, 0)
        said = "\n".join(block.get("text", "") for block in payload.get("content", []) if block.get("kind") == "text").strip()
        if said: texts.append(said)
        calls += [(block.get("name"), block.get("arguments") or {}) for block in payload.get("content", []) if block.get("kind") == "toolCall"]
    return texts, calls, tokens


def all_sessions(user):
    return (user.call("GET", "/api/sessions?limit=200")[1] or {}).get("sessions", [])


class Story:
    def __init__(self, user, model):
        self.user, self.model = user, model
        self.turns, self.cards, self.tokens = [], [], collections.Counter()
        self.seen = {}   # 会话 -> 已经读到第几条事件（算每一轮的新内容）

    def answer_cards(self):
        """对话页上出现的卡片：新建工作区的点“新建”；模型自己多问的，选第一个选项或者说“你定”。"""
        for row in all_sessions(self.user):
            if not row.get("awaitingAnswer"): continue
            for question in (self.user.call("GET", f"/api/sessions/{row['id']}/questions")[1] or {}).get("questions", []):
                title = question.get("title"); title = title.get("zh") or next(iter(title.values()), "") if isinstance(title, dict) else str(title or "")
                detail = run.detail_text(question)
                if question["id"].startswith("workspace-"): payload = {"choice": self.workspace_choice}
                elif self.reply and question.get("freeText"): payload = {"text": self.reply}
                elif question.get("choices"):
                    # 我有话要答时挑说的是同一个意思的选项，没有就选第一个
                    wanted = [choice for choice in question["choices"] if self.reply and any(word in json.dumps(choice, ensure_ascii=False).lower()
                                                                                          for word in self.reply_words)]
                    payload = {"choice": (wanted or question["choices"])[0]["id"]}
                else: payload = {"text": self.reply or "你定就行。"}
                self.user.call("POST", f"/api/sessions/{row['id']}/questions/{question['id']}", payload)
                self.cards.append({"session": row["id"], "title": title, "detail": detail, "answered": payload})

    def say(self, session, text, checks, note="", folder=None, workspace_choice="add", timeout=600, reply=None, reply_words=()):
        """说一句话，等到所有会话都停下来（交出去的活做完、结果回来把这边叫醒的那一轮也跑完），再按 checks 判定。"""
        self.workspace_choice = workspace_choice
        self.reply, self.reply_words = reply, reply_words   # 模型反过来问我时怎么答
        before_cards = len(self.cards)
        known = {row["id"] for row in all_sessions(self.user)}
        for each in known: self.seen.setdefault(each, len(entries(each)))
        body = {"requestId": secrets.token_hex(6), "mode": "prompt", "text": text.replace("{root}", run.ROOT).replace("{outside}", run.OUTSIDE),
                "model": self.model}
        if folder: body["dir"] = folder
        status, reply = self.user.call("POST", f"/api/sessions/{session}/submissions", body)
        problem = None if status == 200 else f"submit {status}: {reply}"
        started, quiet = time.time(), 0
        while problem is None:
            time.sleep(2)
            self.answer_cards()
            rows = all_sessions(self.user)
            busy = [row["id"] for row in rows if row.get("runStatus") != "idle"]
            mine = (self.user.call("GET", f"/api/sessions/{session}/runs")[1] or {}).get("runs", [])
            quiet = quiet + 1 if not busy and mine and mine[-1].get("endReason") else 0
            if quiet >= 4: break
            if time.time() - started > timeout:
                problem = f"{timeout} 秒还没停下来，还在跑的：{busy}"
        turn = {"session": session, "said": body["text"], "note": note, "problem": problem, "seconds": round(time.time() - started),
                "cards": self.cards[before_cards:], "others": []}
        turn["answers"], turn["calls"], used = replies(session, self.seen.get(session, 0))
        self.tokens.update(used); self.seen[session] = len(entries(session))
        for row in all_sessions(self.user):
            if row["id"] == session: continue
            texts, calls, used = replies(row["id"], self.seen.get(row["id"], 0))
            self.tokens.update(used); self.seen[row["id"]] = len(entries(row["id"]))
            if texts or calls:
                turn["others"].append({"id": row["id"], "title": row.get("title"), "dir": row.get("dir"), "parent": row.get("parent"),
                                       "new": row["id"] not in known, "tools": [name for name, _ in calls], "calls": calls, "answers": texts})
        turn["checks"] = ([("这一轮正常结束", False, problem)] if problem else []) + [self.judge(turn, check) for check in checks]
        self.turns.append(turn)
        ok = all(passed for _, passed, _ in turn["checks"])
        print(("PASS " if ok else "FAIL ") + f"{len(self.turns):>2}. {text[:36]}… {turn['seconds']}s  " +
              "; ".join(label + ("" if detail is None else f"（{detail}）") for label, passed, detail in turn["checks"] if not passed), flush=True)
        return turn

    @staticmethod
    def judge(turn, check):
        label, test = check
        try:
            outcome = test(turn)
        except Exception as failure:   # 判定本身出错也算没过，写明
            return label, False, f"判定出错：{failure!r}"
        return (label, outcome, None) if isinstance(outcome, bool) else (label, False, str(outcome))


def file(root, path):
    try: return open(os.path.join(root, path), encoding="utf-8").read()
    except OSError as failure: return f"<{failure}>"


def said(turn, *needles):
    """主窗口这一轮说过的话里（包括结果回来以后又说的）有没有这些。"""
    text = "\n".join(turn["answers"])
    missing = [needle for needle in needles if needle.lower() not in text.lower()]
    return True if not missing else f"回答里没有 {missing}：{text[-300:]!r}"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--model", default="claude", choices=sorted(CAPTURE))
    parser.add_argument("--keep", action="store_true", help="跑完不删数据，留着看")
    args = parser.parse_args()
    run.MODEL_NAME = args.model
    extra = ["--tantalum.memory-capture.enabled=true", "--tantalum.memory-capture.route=claude",
             f"--tantalum.memory-capture.model={CAPTURE[args.model]}"]
    user = run.start(extra, "outside")   # “outside”：测试电脑多允许一个不在 Git 里的目录（笔记目录）
    story = Story(user, run.MODELS[args.model]())
    root, outside = run.ROOT, run.OUTSIDE
    fixture.reset(root)   # 样例项目回到起点：加急附加费 12，折扣有个 bug（两条测试是红的），报表里有条 TODO
    shipping = lambda: file(root, "inventory/shipping.py")
    others_edited = lambda turn: (True if any(EDITING & set(other["tools"]) for other in turn["others"])
                                  else f"没有别的会话动手改：{[(o['id'], o['tools']) for o in turn['others']]}")
    main_did_not_edit = lambda turn: not (EDITING & {name for name, _ in turn["calls"]})
    try:
        # ---------------- 第一天 ----------------
        day1 = "main-" + secrets.token_hex(4)
        story.say(day1, "你好，我叫小康，负责库存系统这一块。我有个习惯：凡是费用，我都要按整数算，不要小数。另外跟我说话简短点就行。", [
            ("只是聊天，没有把活交出去", lambda t: not t["others"]),
        ], note="自我介绍和习惯，之后要靠记忆")
        time.sleep(15)   # 让自动记笔记做完
        story.say(day1, "库存系统的代码在 {root} 这个目录，以后我会经常在那边干活。先帮我看一眼，加急的附加费现在是多少？", [
            ("答出现在是 12", lambda t: said(t, "12")),
        ], note="只是看，主窗口自己能读")
        story.say(day1, "把它改成 15 吧。改完跑一下测试，看看有没有弄坏什么。", [
            ("文件真的改成了 15", lambda t: "EXPRESS_SURCHARGE = 15" in shipping() or f"还是：{re.findall('EXPRESS_SURCHARGE = .*', shipping())}"),
            ("是别的会话动手改的，主窗口自己没改", lambda t: others_edited(t) if main_did_not_edit(t) else "主窗口自己改了"),
            ("回话里说了测试的情况", lambda t: said(t, "测试")),
        ], note="主窗口没有改文件的工具，要自己想到交出去；结果回来再告诉我")
        opened_after_3 = {other["id"] for turn in story.turns for other in turn["others"]}
        story.say(day1, "顺便，还是那个项目，报表那个文件里有条 TODO 说要加 CSV 导出，也一起做了吧。", [
            ("报表里有了 CSV 导出", lambda t: bool(re.search(r"def \w*csv\w*\(", file(root, "inventory/reports.py"), re.I)) or "reports.py 里没有带 csv 的函数"),
            ("是别的会话动手改的", others_edited),
            ("接着用刚才那个会话，没有另开", lambda t: all(not other["new"] for other in t["others"]) or
                f"另开了：{[other['id'] for other in t['others'] if other['new']]}（已有：{sorted(opened_after_3)}）"),
        ], note="同一件事的下一步：应该交给已经在做的那个会话")
        time.sleep(15)

        # ---------------- 第二天：新的主窗口 ----------------
        day2 = "main-" + secrets.token_hex(4)
        story.say(day2, "昨天加急的附加费，我们最后定成多少了？", [
            ("答出 15", lambda t: said(t, "15")),
        ], note="新的主窗口里没有昨天的对话，要靠记忆或者去看")
        story.say(day2, "我还有个记事的目录在 {outside}，帮我在里面 plan.md 的最后加一行待办：核对海外运费。", [
            ("那一行真的加上了", lambda t: "核对海外运费" in file(outside, "plan.md") or f"plan.md：{file(outside, 'plan.md')!r}"),
            ("是别的会话动手改的", others_edited),
            ("没有交给在库存项目里干活的那个会话", lambda t: all(other["id"] not in opened_after_3 for other in t["others"] if EDITING & set(other["tools"]))
                or "交给了库存项目的会话"),
        ], note="另一个目录的活：不该塞给库存项目那个会话")
        lines = len(file(outside, "plan.md").splitlines())
        story.say(day2, "两件事各帮我查一下：库存系统里哪些文件用到了算运费的那个函数；还有我那个 plan.md 现在一共几行。", [
            ("说出了用到它的文件", lambda t: said(t, "orders.py", "reports.py", "cli.py")),
            ("说出了行数", lambda t: said(t, str(lines))),
        ], note="两处各一件事")
        story.say(day2, "发运那边再加一项保价费：按订单金额的 1.5% 收。加好跑一下测试。", [
            ("代码里有了保价费", lambda t: bool(re.search(r"0\.015|1\.5", shipping() + file(root, "inventory/orders.py"))) or "没找到 1.5% 的计算"),
            ("记得我的习惯：按整数算（交代里写了，或者代码里取了整）", lambda t: (
                any("整数" in json.dumps(arguments, ensure_ascii=False) for name, arguments in t["calls"] if name == "delegate")
                or bool(re.search(r"round\(|int\(|//|ceil|floor", "\n".join(line for line in (shipping() + file(root, "inventory/orders.py")).splitlines()
                                                                   if re.search(r"insur|保价|0\.015|1\.5", line, re.I))))
                or "没看出按整数算：交代里没提，相关代码行也没有取整")),
            ("是别的会话动手改的", others_edited),
        ], note="第一天说的习惯要自己用上，我这次没提")
        story.say(day2, "对了，我之前说费用都按整数算，这条你忘掉吧，以后不这么要求了。", [
            ("没有把活交出去", lambda t: not any(EDITING & set(other["tools"]) for other in t["others"])),
        ], note="让它忘掉一条")
        time.sleep(15)

        # ---------------- 第三天 ----------------
        day3 = "main-" + secrets.token_hex(4)
        story.say(day3, "我对费用里的小数有什么讲究吗？", [
            ("不再把按整数算当成我现在的要求（说“提过、后来撤销了”可以）", lambda t: ("整数" not in "\n".join(t["answers"]))
                or any(said(t, word) is True for word in ("不再", "不会再", "取消", "撤销", "撤回", "忘")) or f"还在说整数：{t['answers'][-1][:200]!r}"),
        ], note="忘掉的那条不该再当成我的习惯")
        plain = "s-" + secrets.token_hex(4)
        story.say(plain, "我是谁、负责哪一块？加急的附加费现在是多少？", [
            ("认得我是小康、负责库存", lambda t: said(t, "小康", "库存")),
            ("答出 15", lambda t: said(t, "15")),
        ], note="普通会话（开在库存项目目录里）：也认得人、知道现状", folder=root)

        # ---------------- 第四天：一次两件事、说得含糊、反悔 ----------------
        tests_pass = lambda: subprocess.run([sys.executable, "-m", "unittest", "-q"], cwd=root, capture_output=True, text=True)
        overseas = lambda: (re.findall(r'"overseas":\s*([0-9.]+)', shipping()) or ["?"])[0]
        day4 = "main-" + secrets.token_hex(4)
        story.say(day4, "两件事一起办：库存系统里打折一直算错，测试是红的，帮我修好；另外我记事的那个目录里 plan.md 加一行：周五对账。", [
            ("测试全过了", lambda t: tests_pass().returncode == 0 or f"还有失败：{tests_pass().stderr[-300:]}"),
            ("记事那一行加上了", lambda t: "周五对账" in file(outside, "plan.md") or f"plan.md：{file(outside, 'plan.md')!r}"),
            ("两件事分给了两个目录里的会话", lambda t: len({other["dir"] for other in t["others"] if EDITING & set(other["tools"])}) >= 2
                or f"动手的会话：{[(o['id'], o['dir']) for o in t['others'] if EDITING & set(o['tools'])]}"),
            ("主窗口自己没改", main_did_not_edit),
        ], note="我没再说目录在哪：要靠记忆和工作区知道；两件事各归各的地方")
        before_rate = overseas()
        vague = story.say(day4, "把那个区的运费费率调一下。", [
            ("没有自己猜着改：先问了我（或者等我答了卡片才改）", lambda t: (overseas() == before_rate and ("？" in "\n".join(t["answers"]) or "?" in "\n".join(t["answers"])))
                or any(not card["session"].startswith("x") and not str(card["answered"].get("choice", "")) in ("add", "deny") for card in t["cards"])
                or f"没问就改了：海外费率 {before_rate} → {overseas()}，其余 {re.findall('ZONE_RATES = .*', shipping())}"),
        ], note="说得含糊：哪个区、调多少都没说", reply="海外那个区，调到 4。", reply_words=("海外", "overseas"))
        if overseas() not in ("4", "4.0"):
            story.say(day4, "海外那个区，调到 4。", [], note="补上它问的")
        story.turns[-1]["checks"].append(("海外费率改成了 4", overseas() in ("4", "4.0"), None if overseas() in ("4", "4.0") else f"现在是 {overseas()}"))
        story.say(day4, "算了，刚才调费率那一下撤回去吧，还是用原来的。别的改动留着。", [
            ("海外费率回到了原来的数", lambda t: overseas() == before_rate or f"原来 {before_rate}，现在是 {overseas()}"),
            ("别的改动还在：测试还是全过，保价费还在", lambda t: (tests_pass().returncode == 0 and "insurance" in shipping().lower())
                or f"测试 {tests_pass().returncode}，保价费在不在：{'insurance' in shipping().lower()}"),
        ], note="反悔：只撤一处")
        time.sleep(15)

        # ---------------- 第五天 ----------------
        day5 = "main-" + secrets.token_hex(4)
        story.say(day5, "昨天库存那边我们都动了什么？现在测试是好的吗？", [
            ("说到了修打折", lambda t: said(t, "折") ),
            ("说到了费率调了又撤回", lambda t: any(said(t, word) is True for word in ("撤", "恢复", "改回", "还原")) or f"没提撤回：{t['answers'][-1][:300]!r}"),
            ("没有说费率现在是 4", lambda t: not re.search(r"(现在|目前|当前)[^。\n]{0,20}海外[^。\n]{0,12}4\b", "\n".join(t["answers"]))),
        ], note="隔天问昨天：靠记忆和过去的对话，不能把撤回的说成现状")
    finally:
        report(story, user, args)
        run.stop_all(keep=args.keep)
        if args.keep: print("kept", run.WORK)


def report(story, user, args):
    stamp = time.strftime("%m%d-%H%M") + "-story-" + args.model
    os.makedirs(os.path.join(HERE, "results"), exist_ok=True)
    out = os.path.join(HERE, "results", stamp + ".md")
    checks = [check for turn in story.turns for check in turn["checks"]]
    passed = sum(1 for _, ok, _ in checks if ok)
    projects = (user.call("GET", "/api/projects")[1] or {}).get("projects", [])
    rows = all_sessions(user)
    memory = (user.call("GET", "/api/plugin-api/memory/entries")[1] or {}).get("entries", [])
    lines = [f"# 整个系统一起跑：{args.model}", "",
             f"提交 {run.commit()} · {len(story.turns)} 句话 · 判定 {passed}/{len(checks)} 过 · 对话部分费用约 ${run.cost(args.model, story.tokens):.2f}"
             f"（不含后台记笔记和找回的调用）", "",
             "## 判定", "", "| # | 说的话 | 判定 | 结果 | 说明 |", "|---|---|---|---|---|"]
    for index, turn in enumerate(story.turns, 1):
        for label, ok, detail in turn["checks"]:
            lines.append(f"| {index} | {turn['said'][:30].replace('|', ' ')} | {label} | {'过' if ok else '**没过**'} | {(detail or '').replace('|', ' ').replace(chr(10), ' ')[:300]} |")
    lines += ["", "## 最后的样子", "", "工作区：" + ("、".join(f"{p['title']}（{p['path']}）" for p in projects) or "没有"), "",
              "| 会话 | 标题 | 目录 | 谁开的 |", "|---|---|---|---|"]
    for row in sorted(rows, key=lambda each: each["id"]):
        lines.append(f"| {row['id']} | {(row.get('title') or '').replace('|', ' ')[:40]} | {row.get('dir') or ''} | {row.get('parent') or ''} |")
    lines += ["", "记忆里的条目：" + ("、".join(entry.get("name", "") for entry in memory) or "没有"), ""]
    for entry in memory:
        body = user.call("GET", "/api/plugin-api/memory/entries/" + entry["name"])[1] or {}
        lines += [f"### 记忆 {entry['name']}", "", "```", str(body.get("body") or body.get("text") or json.dumps(body, ensure_ascii=False))[:1500], "```", ""]
    # 工作记忆：做完的事不该还挂着（“等别的会话做完”“等我回答”这类由系统自己管，不该记进来）
    # 直接读记忆的账：还活着的任务、在等、承诺
    listed, dead = {}, set()
    ledger = f"{run.DATA}/plugins/memory/dev/ledger.jsonl"
    if os.path.exists(ledger):
        for line in open(ledger, encoding="utf-8"):
            try: entry = json.loads(line)
            except ValueError: continue
            if "put" in entry: listed[entry["put"]["id"]] = entry["put"]
            for key in ("tombstone", "tomb"):
                if key in entry: dead.add(entry[key].get("id") if isinstance(entry[key], dict) else entry[key])
    listed = [item for item in listed.values() if item["id"] not in dead and item.get("kind") in ("task", "waiting", "commitment")]
    lines += [f"## 工作记忆里现在挂着的（{len(listed)} 条）", ""]
    lines += ["- " + str(item.get("kind") or "") + "：" + str(item.get("text") or "")[:160].replace(chr(10), " ") + ("（待确认）" if item.get("pendingConfirm") else "")
              for item in listed] + [""]
    print(f"工作记忆里挂着 {len(listed)} 条")
    lines += ["## 全程", ""]
    for index, turn in enumerate(story.turns, 1):
        lines += [f"### {index}. {turn['note']}（{turn['session']}，{turn['seconds']} 秒）", "", "**我说：** " + turn["said"], ""]
        if turn["calls"]: lines += ["主窗口用的工具：" + "、".join(name for name, _ in turn["calls"]), ""]
        for name, arguments in turn["calls"]:
            if name in ("delegate", "workspace_add", "memory_note", "memory_write", "memory_delete", "now"):
                lines += [f"- `{name}` " + json.dumps(arguments, ensure_ascii=False)[:700]]
        for card in turn["cards"]:
            lines += ["", f"卡片（{card['session']}）：{card['title']} — {card['detail'][:200]!r} → {card['answered']}"]
        for other in turn["others"]:
            lines += ["", f"别的会话 {other['id']}（{'新开' if other['new'] else '已有'}，目录 {other['dir'] or '无'}）用的工具：" + "、".join(other["tools"]),
                      "它的回话：" + (other["answers"][-1][:600] if other["answers"] else "（没有）")]
        for position, text in enumerate(turn["answers"], 1):
            lines += ["", f"**它说（{position}）：** " + text[:1500]]
        lines.append("")
    import contextlib, io
    import audit_states
    captured = io.StringIO()
    with contextlib.redirect_stdout(captured):
        audit_states.main(f"{run.DATA}/sessions/dev")
    lines += ["## 状态有没有闭环（对着账核对）", "", "```", captured.getvalue().strip(), "```", ""]
    open(out, "w", encoding="utf-8").write("\n".join(lines) + "\n")
    print(captured.getvalue().strip())
    print(f"{passed}/{len(checks)} -> {out}")


if __name__ == "__main__":
    main()
