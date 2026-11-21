"""记忆评测的第二层：真模型（memory-final-plan 第 14 节）。在固定的种子数据上，每道题 3 种说法、各跑几次，
代码判分（关键词）加模型裁判（评分标准），和两个对照组比：不用记忆（none）、只给原话（raw）。

用法（先在仓库根目录 mvn -o package -DskipTests 打好包）：

    python3 tantalum-examples/scenarios/memory_eval.py --model qwen --judge none            # 本机模型、只用关键词判分，不花钱
    python3 tantalum-examples/scenarios/memory_eval.py --model claude --arms memory,none,raw  # 裁判默认 Sonnet 5.5，要 ANTHROPIC_API_KEY
    python3 tantalum-examples/scenarios/memory_eval.py --model qwen --only update.false-premise --repeat 1

题目和种子和第一层共用：tantalum-shell/src/test/resources/memory-eval/questions.json、seed.json。
只跑有 tier2 的题；phase 还没到的题照样跑（看差距），表里标出来。结果写到 scenarios/results/memory-*.json 和 .md（不进 Git）。
不碰 ~/.local/share/tantalum：服务器的数据目录在临时目录里，跑完删掉（--keep 保留）。
"""
import argparse, collections, datetime, glob, json, os, secrets, shlex, shutil, signal, subprocess, sys, tempfile, time
import urllib.error, urllib.request, http.cookiejar

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(os.path.dirname(HERE))
EVAL = os.path.join(REPO, "tantalum-shell", "src", "test", "resources", "memory-eval")
PORT = 8095
BASE = f"http://127.0.0.1:{PORT}"
LOCAL = {"url": "http://127.0.0.1:8090/v1", "model": "qwen3.8-flash-next-unsloth-ud-q4_k_xl"}
MODELS = {"qwen": {"route": "local", "model": LOCAL["model"], "effort": "off"},
          "qwen-think": {"route": "local", "model": LOCAL["model"], "effort": "on"},
          "claude": {"route": "claude", "model": "claude-sonnet-5-5", "effort": "medium"},
          "opus": {"route": "claude", "model": "claude-opus-5-5", "effort": "medium"},
          "haiku": {"route": "claude", "model": "claude-haiku-5-5", "effort": "medium"}}
# 每百万 token 的美元价：输入、输出、读缓存、写缓存（和 run.py 一样，2026-11-13 照 claude-api 技能的价格表）
PRICES = {"claude": (2.0, 10.0, 0.10, 2.5), "opus": (4.0, 20.0, 0.20, 5.0), "haiku": (0.10, 0.50, 0.01, 0.125)}
JUDGE_PRICE = {"claude-sonnet-5-5": (2.0, 10.0), "claude-opus-5-5": (4.0, 20.0), "claude-haiku-5-5": (0.10, 0.50)}
RAW_INTRO = "下面是我以前在别的对话里说过的原话，按时间先后（供参考，可能已经过时）：\n"


class Client:
    def __init__(self):
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))

    def call(self, method, path, body=None, timeout=30):
        data = None if body is None else json.dumps(body).encode()
        request = urllib.request.Request(BASE + path, data=data, method=method, headers={"Content-Type": "application/json", "Origin": BASE})
        try:
            with self.opener.open(request, timeout=timeout) as response:
                text = response.read().decode()
                return response.status, (json.loads(text) if text else None)
        except urllib.error.HTTPError as error:
            text = error.read().decode()
            try: return error.code, json.loads(text)
            except ValueError: return error.code, text


def env_file():
    env = dict(os.environ)
    path = os.path.expanduser("~/.config/tantalum/env")
    if os.path.exists(path):
        for line in open(path):
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                key, value = line.split("=", 1)
                env[key.replace("export ", "").strip()] = value.strip().strip('"').strip("'")
    return env


JARS = {}   # --shell-jar、--examples-jar 给了就用它们（拷到别处的包，跑的时候重新打包不受影响）


def shell_jar():
    if JARS.get("shell"): return JARS["shell"]
    found = sorted(glob.glob(os.path.join(REPO, "tantalum-shell", "target", "tantalum-shell-*-exec.jar")))
    if not found: raise SystemExit(f"tantalum-shell is not packaged; run `mvn -o package -DskipTests` in {REPO} first")
    return found[-1]


def examples_jar():
    if JARS.get("examples"): return JARS["examples"]
    found = [p for p in sorted(glob.glob(os.path.join(REPO, "tantalum-examples", "target", "tantalum-examples-*.jar"))) if "-exec" not in p]
    if not found: raise SystemExit(f"tantalum-examples is not packaged; run `mvn -o package -DskipTests` in {REPO} first")
    return found[-1]


# ---- 种子：和第一层的 MemoryEvalTest 一样换成记忆账的行 ----

def iso(moment):
    return moment.strftime("%Y-%m-%dT%H:%M:%S.%f") + "Z"


def ledger_lines(items, now, projects):
    """seed.json 里一个人的条目 → 记忆账的行（{"put": {...}}）。daysAgo 从 now 倒推；scope 里的 {名字} 换成项目路径。"""
    lines = []
    for item in items:
        at = now - datetime.timedelta(days=item.get("daysAgo", 1))
        attrs = dict(item.get("attrs") or {})
        if item["kind"] == "page":
            attrs.update(type=item["type"], description=item["description"])
        if item["kind"] == "card":
            attrs.setdefault("day", at.strftime("%Y-%m-%d"))
        scope = item.get("scope", "personal")
        for name, path in projects.items():
            scope = scope.replace("{" + name + "}", path)
        put = {"id": item["id"], "version": 1, "state": item.get("state", "confirmed"), "kind": item["kind"]}
        if item.get("page"): put["page"] = item["page"]
        put.update(scope=scope, source=item.get("source", "user_said"), actor="user" if item.get("source", "user_said") == "user_said" else "agent",
                   validFrom=iso(at), confidence="observed" if item.get("source", "user_said") == "user_said" else "inferred")
        if item.get("derivedFrom"): put["derivedFrom"] = item["derivedFrom"]
        if item.get("pendingConfirm"): put["pendingConfirm"] = True
        if item.get("status"): put["status"] = item["status"]
        put["text"] = item.get("text", "")
        if attrs: put["attrs"] = attrs
        put.update(created=iso(at), updated=iso(at))
        lines.append(json.dumps({"put": put}, ensure_ascii=False))
    return lines


def raw_quotes(items):
    """“只给原话”对照组：种子里有原话（quote）、确认过的条目，按时间先后。"""
    quoted = [i for i in items if i.get("quote") and not i.get("pendingConfirm")]
    quoted.sort(key=lambda i: -i.get("daysAgo", 1))
    return [f"（{i.get('daysAgo', 1)} 天前）{i['quote']}" for i in quoted]


# ---- 服务器 ----

def start(data, password, extra=()):
    """起一台服务器，自动记笔记关掉（评测只看想起，不让后台调用抢模型）。用户由 new_user 每问一次建一个。extra 是另外加的启动参数。"""
    env = env_file(); env.update(TANTALUM_ADMIN_USER="root", TANTALUM_ADMIN_PASSWORD=password)
    server = subprocess.Popen(["java", "--enable-native-access=ALL-UNNAMED", f"-Dloader.path={examples_jar()}", "-cp", shell_jar(),
        "org.springframework.boot.loader.launch.PropertiesLauncher", f"--server.port={PORT}", f"--tantalum.data-dir={data}",
        f"--tantalum.workspace={data}-ws", "--tantalum.examples.enabled=true", "--tantalum.provider=local",
        f"--tantalum.openai-routes.local.base-url={LOCAL['url']}", f"--tantalum.openai-routes.local.model={LOCAL['model']}",
        "--tantalum.openai-routes.local.thinking=chat-template", "--tantalum.openai-routes.local.context-window=131072",
        "--tantalum.memory-capture.enabled=false", *extra, *shlex.split(os.environ.get("TANTALUM_EVAL_ARGS", ""))],
        env=env, stdout=open(f"{data}-server.log", "w"), stderr=subprocess.STDOUT)
    admin = Client()
    for _ in range(90):
        try:
            if admin.call("POST", "/api/auth/login", {"username": "root", "password": password})[0] == 200: break
        except OSError: pass
        time.sleep(1)
    else:
        server.kill()
        raise SystemExit("server did not start: " + open(f"{data}-server.log", errors="replace").read()[-2500:])
    return server, admin


COUNTER = [0]


def new_user(admin, data, seed, person, memory):
    """每问一次一个新用户，种子在他第一次被访问之前写进记忆账：模型在上一问里改了记忆，不能带进下一问（11-16 试跑时 qwen 把决定改成了 MySQL）。"""
    COUNTER[0] += 1
    name = f"{person}-{COUNTER[0]}"
    state = os.path.join(data, "plugins", "memory", name)
    os.makedirs(state, exist_ok=True)
    now = datetime.datetime.now(datetime.timezone.utc).replace(tzinfo=None)
    with open(os.path.join(state, "ledger.jsonl"), "w", encoding="utf-8") as out:
        out.write("\n".join(ledger_lines(seed["users"][person], now, {"alpha": os.path.join(data, "alpha")})) + "\n")
    password = secrets.token_urlsafe(18)
    status, body = admin.call("POST", "/api/users", {"name": name, "password": password, "admin": False})
    assert status == 200, (name, status, body)
    client = Client(); assert client.call("POST", "/api/auth/login", {"username": name, "password": password})[0] == 200
    if not memory: set_memory(client, False)
    return name, client


def set_memory(client, on):
    """“不用记忆”对照组：这个人自己把记忆插件关掉（插件页上的个人开关）。"""
    status, body = client.call("PUT", "/api/plugins/plugin/memory", {"enabled": on})
    if status != 200: raise SystemExit(f"cannot switch memory {'on' if on else 'off'}: {status} {body}")


def answer_of(data, user, session):
    """这个会话最后一次模型回答的文字、token、模型调用次数（读 journal）。"""
    path = os.path.join(data, "sessions", user, session + ".jsonl")
    text, tokens, calls = "", collections.Counter(), 0
    if not os.path.exists(path): return text, tokens, calls
    for line in open(path, encoding="utf-8"):
        try: entry = json.loads(line)
        except ValueError: continue
        if entry.get("type") != "ModelReplied": continue
        payload = entry.get("payload") or {}
        calls += 1
        for key in ("input", "output", "cacheRead", "cacheWrite"): tokens[key] += (payload.get("usage") or {}).get(key, 0)
        texts = [b.get("text", "") for b in payload.get("content", []) if b.get("kind") == "text"]
        if texts: text = "\n".join(texts)
    return text, tokens, calls


def seen_of(data, user, session):
    """这个会话里模型从记忆得到的全部文字：系统提示（含档案）、每轮前找回的那一段、每次工具返回（读 journal）。"""
    path = os.path.join(data, "sessions", user, session + ".jsonl")
    parts = []
    if not os.path.exists(path): return ""
    for line in open(path, encoding="utf-8"):
        try: entry = json.loads(line)
        except ValueError: continue
        payload = entry.get("payload") or {}
        if entry.get("type") == "ContextAssembled": parts.append(payload.get("system") or "")
        elif entry.get("type") == "WorkDone": parts.append(payload.get("continueText") or "")
        elif entry.get("type") == "ToolFinished": parts.append(payload.get("text") or "")
    return "\n\n".join(part for part in parts if part)


def ask(client, session, text, model, timeout):
    status, body = client.call("POST", f"/api/sessions/{session}/submissions",
                               {"requestId": secrets.token_hex(6), "delivery": "queue", "text": text, "model": model})
    if status != 200: return f"submit {status}: {body}"
    started = time.time()
    while time.time() - started < timeout:
        time.sleep(1.5)
        status, runs = client.call("GET", f"/api/sessions/{session}/runs")
        listed = (runs or {}).get("runs", []) if status == 200 else []
        if listed and listed[-1].get("endReason"):
            return None if listed[-1]["endReason"] == "COMPLETED" else f"run ended {listed[-1]['endReason']}"
        # 模型自己多问了一句：答“不知道，你按记得的说”
        status, questions = client.call("GET", f"/api/sessions/{session}/questions")
        for question in (questions or {}).get("questions", []) if status == 200 else []:
            payload = {"choice": question["choices"][0]["id"]} if question.get("choices") else {"text": "我不知道，按你记得的说。"}
            client.call("POST", f"/api/sessions/{session}/questions/{question['id']}", payload)
    for run in (client.call("GET", f"/api/sessions/{session}/runs")[1] or {}).get("runs", [])[-1:]:
        client.call("POST", f"/api/runs/{run['run']}/cancel", {})
    return f"timeout {timeout}s"


# ---- 判分 ----

def keyword_pass(answer, groups):
    lowered = answer.lower()
    return all(any(word.lower() in lowered for word in group) for group in groups)


JUDGE_SYSTEM = """You grade one answer of an AI assistant that has a long-term memory of the user. You get the question, \
what the assistant should answer (a rubric written in Chinese), and the assistant's answer. Judge only whether the answer meets \
the rubric; wording, language and length do not matter. Reply with one JSON object and nothing else: \
{"pass": true or false, "reason": "<one short sentence>"}"""


def judge(client, model, question, phrasing, answer, usage):
    message = (f"Question: {phrasing}\n\nRubric: {question['tier2']['rubric']}\n\n"
               f"Should refuse or correct the premise: {'yes' if question['tier2'].get('refuse') else 'no'}\n\n"
               f"<answer>\n{answer}\n</answer>")
    response = client.messages.create(model=model, max_tokens=500, system=JUDGE_SYSTEM, messages=[{"role": "user", "content": message}])
    usage["input"] += response.usage.input_tokens; usage["output"] += response.usage.output_tokens
    text = "".join(block.text for block in response.content if block.type == "text").strip()
    start, end = text.find("{"), text.rfind("}")
    try:
        verdict = json.loads(text[start:end + 1])
        return bool(verdict.get("pass")), str(verdict.get("reason", ""))
    except ValueError:
        return False, "judge reply was not JSON: " + text[:120]


def cost(model, tokens):
    price = PRICES.get(model, (0, 0, 0, 0))
    return (tokens["input"] * price[0] + tokens["output"] * price[1] + tokens["cacheRead"] * price[2] + tokens["cacheWrite"] * price[3]) / 1e6


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--model", default="qwen", choices=sorted(MODELS))
    parser.add_argument("--arms", default="memory,none", help="memory（用记忆）、none（关掉记忆）、raw（关掉记忆、把种子原话贴在问题前面）")
    parser.add_argument("--judge", default="claude-sonnet-5-5", help="裁判模型；none 只用关键词判分")
    parser.add_argument("--repeat", type=int, default=3)
    parser.add_argument("--only", default="", help="逗号分隔的题目 id")
    parser.add_argument("--timeout", type=int, default=240)
    parser.add_argument("--keep", action="store_true")
    parser.add_argument("--tag", default="")
    parser.add_argument("--shell-jar", default="", help="用这个服务器包，不用 target 里的（跑的时候要重新打包时）")
    parser.add_argument("--examples-jar", default="")
    args = parser.parse_args()
    JARS.update(shell=args.shell_jar, examples=args.examples_jar)
    questions = [q for q in json.load(open(os.path.join(EVAL, "questions.json"), encoding="utf-8"))["questions"] if q.get("tier2")]
    if args.only: questions = [q for q in questions if q["id"] in set(args.only.split(","))]
    if not questions: raise SystemExit("no tier-2 questions selected")
    seed = json.load(open(os.path.join(EVAL, "seed.json"), encoding="utf-8"))
    arms = [arm for arm in args.arms.split(",") if arm]
    for arm in arms:
        if arm not in ("memory", "none", "raw"): raise SystemExit("unknown arm " + arm)
    grader = None
    if args.judge != "none":
        import anthropic
        key = env_file().get("ANTHROPIC_API_KEY")
        if not key: raise SystemExit("the judge needs ANTHROPIC_API_KEY (environment or ~/.config/tantalum/env); or pass --judge none")
        grader = anthropic.Anthropic(api_key=key)
    planned = sum(len(q["tier2"]["phrasings"]) for q in questions) * args.repeat * len(arms)
    print(f"{len(questions)} questions x 3 phrasings x {args.repeat} runs x {len(arms)} arms = {planned} asks, model {args.model}, judge {args.judge}", flush=True)

    work = tempfile.mkdtemp(prefix="tantalum-memory-eval-")
    stamp = time.strftime("%m%d-%H%M") + "-memory-" + args.model + ("-" + args.tag if args.tag else "")
    out = os.path.join(HERE, "results", stamp)
    os.makedirs(os.path.dirname(out), exist_ok=True)
    report = {"model": args.model, "judge": args.judge, "arms": arms, "repeat": args.repeat, "plan": [q["id"] for q in questions], "results": []}
    judge_usage = collections.Counter()
    servers = []
    try:
        for arm in arms:
            # 每组一台新服务器、一份新种子：前一组的问答不能变成后一组的记忆
            data = os.path.join(work, "data-" + arm)
            server, admin = start(data, secrets.token_urlsafe(18))
            servers.append(server)
            for question in questions:
                spec = question["tier2"]
                prefix = ""
                if arm == "raw":
                    prefix = RAW_INTRO + "\n".join(raw_quotes(seed["users"][spec["user"]])) + "\n\n"
                for index, phrasing in enumerate(spec["phrasings"]):
                    for run in range(1, args.repeat + 1):
                        user, client = new_user(admin, data, seed, spec["user"], arm == "memory")
                        for name in spec.get("forget", []):
                            client.call("DELETE", f"/api/plugin-api/memory/entries/{name}")
                        session = "m-" + secrets.token_hex(4)
                        started = time.time()
                        problem = ask(client, session, prefix + phrasing, MODELS[args.model], args.timeout)
                        answer, tokens, calls = answer_of(data, user, session)
                        keywords = keyword_pass(answer, spec.get("answer", []))
                        verdict, reason = (None, "")
                        if grader is not None and not problem:
                            verdict, reason = judge(grader, args.judge, question, phrasing, answer, judge_usage)
                        # 要纠正前提或拒答的题，关键词判不了（答错的话里也有 PostgreSQL）：没有裁判时记“没判”
                        if verdict is None and spec.get("refuse") and not problem:
                            passed = None
                        else:
                            passed = not problem and keywords and (verdict is None or verdict)
                        result = {"id": question["id"], "category": question["category"], "phase": question["phase"], "arm": arm,
                                  "phrasing": index + 1, "run": run, "passed": passed, "keywords": keywords, "judge": verdict,
                                  "reason": reason, "problem": problem, "answer": answer[:600], "seconds": round(time.time() - started, 1),
                                  "calls": calls, "tokens": dict(tokens), "cost": round(cost(args.model, tokens), 4)}
                        report["results"].append(result)
                        print(f"{'----' if passed is None else 'PASS' if passed else 'FAIL'} {arm:6} {question['id']:26} #{index + 1}.{run} {result['seconds']:5}s "
                              f"kw={'y' if keywords else 'n'} judge={'-' if verdict is None else 'y' if verdict else 'n'} "
                              f"{(problem or reason)[:90]}", flush=True)
                        json.dump(report, open(out + ".json", "w"), ensure_ascii=False, indent=1)
            server.send_signal(signal.SIGTERM); server.wait(timeout=30)
    finally:
        for server in servers:
            if server.poll() is None: server.kill()
        if args.keep: print("kept", work)
        else: shutil.rmtree(work, ignore_errors=True)

    judge_cost = sum(judge_usage[k] * JUDGE_PRICE.get(args.judge, (0, 0))[i] for i, k in enumerate(("input", "output"))) / 1e6
    spent = sum(r["cost"] for r in report["results"]) + judge_cost
    report["judgeTokens"] = dict(judge_usage); report["cost"] = round(spent, 4)
    json.dump(report, open(out + ".json", "w"), ensure_ascii=False, indent=1)
    table = collections.defaultdict(lambda: collections.Counter())
    for r in report["results"]:
        if r["passed"] is None: continue
        table[(r["id"], r["arm"])]["runs"] += 1; table[(r["id"], r["arm"])]["passed"] += r["passed"]
    lines = [f"# {stamp}", "", f"模型 {args.model} · 裁判 {args.judge} · 每种说法 {args.repeat} 次 · 费用约 ${spent:.2f}（裁判 ${judge_cost:.2f}）", "",
             "| 题 | 类 | 应在 | " + " | ".join(arms) + " |", "|---|---|---|" + "---|" * len(arms)]
    for question in questions:
        cells = [f"{table[(question['id'], arm)]['passed']}/{table[(question['id'], arm)]['runs']}" if table[(question['id'], arm)]['runs'] else "没判"
                 for arm in arms]
        lines.append(f"| {question['id']} | {question['category']} | {question['phase']} | " + " | ".join(cells) + " |")
    totals = [sum(table[(q['id'], arm)]['passed'] for q in questions) for arm in arms]
    runs = [sum(table[(q['id'], arm)]['runs'] for q in questions) for arm in arms]
    lines.append("| 合计 | | | " + " | ".join(f"{p}/{n}" for p, n in zip(totals, runs)) + " |")
    open(out + ".md", "w", encoding="utf-8").write("\n".join(lines) + "\n")
    print("\n".join(lines[2:]), "\n->", out + ".md")


if __name__ == "__main__":
    main()
