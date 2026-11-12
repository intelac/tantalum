"""真实场景的工具测试：临时服务器 + 真的连接程序 + 真模型，在一份每次重新生成的小项目上跑一批真实任务。

用法（先在仓库根目录 mvn -o package -DskipTests 打好包）：

    python3 tantalum-examples/scenarios/run.py --model qwen            # 本机 OpenAI 兼容模型，默认 http://127.0.0.1:8090/v1
    python3 tantalum-examples/scenarios/run.py --model claude          # 要 ANTHROPIC_API_KEY（环境变量，或 ~/.config/tantalum/env）
    python3 tantalum-examples/scenarios/run.py --model qwen --only fix-bug,rename

不碰 ~/.local/share/tantalum：服务器的数据目录、项目目录、浏览器资料目录都在一个临时目录里，跑完删掉（--keep 保留）。
结果写到 tantalum-examples/scenarios/results/（不进 Git）：一份 .json（每个场景的每次工具调用）和一份 .md（表格）。
真模型的结果有随机性：一个场景没过，先再跑一次。判定标准只查事实（文件内容、测试结果、回答里藏着的编号），不看措辞。
"""
import argparse, glob, hashlib, json, os, re, secrets, shutil, signal, subprocess, sys, tempfile, time, urllib.request, urllib.error, http.cookiejar, collections
import fixture
from scenarios import SCENARIOS, SITE as SITE_URL

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(os.path.dirname(HERE))
WORK = tempfile.mkdtemp(prefix="tantalum-scenarios-")
ROOT = os.path.join(WORK, "inventory")
PORT, SITE_PORT = 8094, 8766
BASE = f"http://127.0.0.1:{PORT}"
LOCAL = {"url": "http://127.0.0.1:8090/v1", "model": "qwen3.8-flash-next-unsloth-ud-q4_k_xl"}
# claude 是 Sonnet 5.5（录制和基线用它）；opus、haiku、fable 用来比较不同的 Claude 模型
MODELS = {"qwen": lambda: {"route": "local", "model": LOCAL["model"], "effort": "off"},
          "qwen-think": lambda: {"route": "local", "model": LOCAL["model"], "effort": "on"},
          "claude": lambda: {"route": "claude", "model": "claude-sonnet-5-5", "effort": "medium"},
          "opus": lambda: {"route": "claude", "model": "claude-opus-5-5", "effort": "medium"},
          "haiku": lambda: {"route": "claude", "model": "claude-haiku-5-5", "effort": "medium"},
          "fable": lambda: {"route": "claude", "model": "claude-fable-5-2", "effort": "high"}}
MXC = os.path.expanduser("~/.cache/tantalum/mxc/1.0.1/mxc-exec-mac")

def jar(module, suffix=""):
    """模块打好的 jar；没有就说明要先打包。"""
    found = [path for path in glob.glob(os.path.join(REPO, module, "target", f"{module}-*{suffix}.jar")) if suffix or "-exec" not in path]
    if not found: raise SystemExit(f"{module} is not packaged; run `mvn -o package -DskipTests` in {REPO} first")
    return sorted(found)[-1]

def connector_classpath():
    """连接程序的类路径：它自己的 jar 加上 Maven 解析出的依赖（和 Windows CI 同一条命令；解析一次，记在 results/ 里）。"""
    cache = os.path.join(HERE, "results", "connect.cp")
    pom = os.path.join(REPO, "tantalum-connect", "pom.xml")
    if not os.path.exists(cache) or os.path.getmtime(cache) < os.path.getmtime(pom):
        os.makedirs(os.path.dirname(cache), exist_ok=True)
        subprocess.run(["mvn", "-o", "-q", "-pl", "tantalum-connect", "-am", "package", "dependency:build-classpath", "-DskipTests", "-Djacoco.skip=true",
                        f"-Dmdep.outputFile={cache}", "-Dmdep.includeScope=runtime"], cwd=REPO, check=True)
    return jar("tantalum-connect") + ":" + open(cache).read().strip()

class Client:
    def __init__(self):
        self.jar = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(self.jar))
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

started = []   # 已经起的进程：启动中途失败时也要停掉

def stop_all(keep):
    for process in reversed(started):
        process.send_signal(signal.SIGTERM)
    for process in reversed(started):
        try: process.wait(timeout=20)
        except subprocess.TimeoutExpired: process.kill()
    subprocess.run(["pkill", "-f", f"{WORK}/browser-profile"], capture_output=True)
    if keep: print("kept", WORK)
    else: shutil.rmtree(WORK, ignore_errors=True)

def last_wins(command):
    """同一个 --键=值 出现两次时只留后面的：Spring 会把两次的值用逗号拼起来（"131072,24000"），数字就转换失败。"""
    keep, seen = [], set()
    for arg in reversed(command):
        key = arg.split("=", 1)[0] if arg.startswith("--") and "=" in arg else None
        if key is not None and key in seen: continue
        if key is not None: seen.add(key)
        keep.append(arg)
    return keep[::-1]

MODEL_NAME = None   # 这一轮用的模型（qwen 或 claude）
DATA = None   # 这一组服务器的数据目录（每组一份，--keep 时也不会互相影响）

def start(extra=(), name=None):
    global DATA
    # 先把会失败的都查完再起进程，免得留下一台没人管的服务器
    cp = connector_classpath()
    if not os.path.exists(MXC): raise SystemExit(f"the sandbox binary is missing at {MXC}; build the macOS connector package once (it downloads MXC) or pass --mxc")
    shell_jar, examples_jar = jar("tantalum-shell", "-exec"), jar("tantalum-examples")
    os.makedirs(WORK, exist_ok=True)
    DATA = f"{WORK}/data" + (f"-{name}" if name else "")
    fixture.build(ROOT)
    admin_pw, user_pw = secrets.token_urlsafe(18), secrets.token_urlsafe(18)
    env = env_file(); env.update(TANTALUM_ADMIN_USER="root", TANTALUM_ADMIN_PASSWORD=admin_pw)
    server = subprocess.Popen(last_wins(["java", "--enable-native-access=ALL-UNNAMED", f"-Dloader.path={examples_jar}", "-cp", shell_jar,
        "org.springframework.boot.loader.launch.PropertiesLauncher", f"--server.port={PORT}", f"--tantalum.data-dir={DATA}", f"--tantalum.workspace={DATA}-ws",
        "--tantalum.examples.enabled=true", "--tantalum.provider=local", f"--tantalum.openai-routes.local.base-url={LOCAL['url']}",
        f"--tantalum.openai-routes.local.model={LOCAL['model']}", "--tantalum.openai-routes.local.thinking=chat-template", "--tantalum.openai-routes.local.context-window=131072",
        "--tantalum.web.block-private-network=false", *extra]), env=env, stdout=open(f"{DATA}-server.log", "w"), stderr=subprocess.STDOUT)
    started.append(server)
    admin = Client()
    for _ in range(90):
        try:
            if admin.call("POST", "/api/auth/login", {"username": "root", "password": admin_pw})[0] == 200: break
        except OSError: pass
        time.sleep(1)
    else: raise SystemExit("server did not start: " + " | ".join(open(f"{DATA}-server.log", errors="replace").read().strip().splitlines()[-25:])[-2500:])
    assert admin.call("POST", "/api/users", {"name": "dev", "password": user_pw, "admin": False})[0] == 200
    user = Client(); assert user.call("POST", "/api/auth/login", {"username": "dev", "password": user_pw})[0] == 200
    status, token = user.call("POST", "/api/local/tokens", {"validity": "1d"}); assert status == 200, token
    cenv = dict(os.environ, TANTALUM_TOKEN=token["token"])
    connector = subprocess.Popen(["java", "--enable-native-access=ALL-UNNAMED", "-cp", cp, "tantalum.connect.Main", "--server", BASE, "--allow", ROOT,
        "--allow-write", "--allow-shell", "--mxc", MXC, "--allow-browser", "--browser-headless",
        "--browser-profile", f"{WORK}/browser-profile", "--lang", "en"], env=cenv, cwd=ROOT, stdout=open(f"{DATA}-connector.log", "w"), stderr=subprocess.STDOUT)
    started.append(connector)
    for _ in range(40):
        link = user.call("GET", "/api/local")[1].get("link")
        if link: break
        time.sleep(1)
    else: raise SystemExit("connector did not connect: " + " | ".join(open(f"{DATA}-connector.log", errors="replace").read().strip().splitlines()[-25:])[-2500:])
    print("computer:", {k: link.get(k) for k in ("write", "git")}, "shell", link["shell"].get("available"), link["shell"].get("reason", ""), "browser", (link.get("browser") or {}).get("available"), flush=True)
    percent = PERCENT.get(name, {}).get(MODEL_NAME)
    if percent: assert user.call("PUT", "/api/settings/compaction", {"percent": percent})[0] == 200
    assert user.call("PUT", "/api/local/skills", {"dir": os.path.join(ROOT, "skills")})[0] == 200
    assert user.call("PUT", "/api/local/wiki", {"dir": os.path.join(ROOT, "wiki")})[0] == 200
    return user

class Ctx: pass

def journal(session):
    path = f"{DATA}/sessions/dev/{session}.jsonl"
    calls, by_id, answer, model_calls, tokens = [], {}, "", 0, collections.Counter()
    extra = {"compactions": 0, "promptSha256": None}
    if not os.path.exists(path): return calls, answer, model_calls, tokens, extra
    for line in open(path, encoding="utf-8"):
        try: entry = json.loads(line)
        except ValueError: continue
        payload = entry.get("payload") or {}
        if entry.get("type") == "ContextAssembled" and extra["promptSha256"] is None and payload.get("system"):
            # 系统提示里写着这次临时目录的路径，换成 {{root}} 再算，否则每轮都不同
            extra["promptSha256"] = hashlib.sha256(normalize(payload["system"]).encode("utf-8")).hexdigest()
        if entry.get("type") == "ModelReplied" and (payload.get("options") or {}).get("toolsAllowed") is False:
            extra["compactions"] += 1
        if entry.get("type") == "ModelReplied":
            model_calls += 1
            for key in ("input", "output", "cacheRead", "cacheWrite"): tokens[key] += (payload.get("usage") or {}).get(key, 0)
            texts = [block.get("text", "") for block in payload.get("content", []) if block.get("kind") == "text"]
            if texts: answer = "\n".join(texts)
            for block in payload.get("content", []):
                if block.get("kind") == "toolCall":
                    call = {"name": block.get("name"), "args": block.get("arguments") if block.get("arguments") is not None else block.get("rawArguments"), "result": None, "error": None}
                    by_id[block.get("id")] = call; calls.append(call)
        elif entry.get("type") == "ToolFinished" and payload.get("toolCallId") in by_id:
            by_id[payload["toolCallId"]].update(result=payload.get("text") or "", error=bool(payload.get("error")))
    return calls, answer, model_calls, tokens, extra

def ask(user, session, text, model, timeout, answers, max_calls, posted=None):
    request = secrets.token_hex(6)
    # 同一会话里的后几轮：等的是这一次提问新开的那一轮，不是上一轮
    status, runs = user.call("GET", f"/api/sessions/{session}/runs")
    before = len((runs or {}).get("runs", [])) if status == 200 else 0
    status, body = user.call("POST", f"/api/sessions/{session}/submissions", {"requestId": request, "mode": "prompt", "text": text, "model": model})
    if status != 200: return f"submit {status}: {body}"
    start, answered = time.time(), set()
    while True:
        time.sleep(1.5)
        status, runs = user.call("GET", f"/api/sessions/{session}/runs")
        listed = (runs or {}).get("runs", []) if status == 200 else []
        last = listed[-1] if len(listed) > before else {}
        if last.get("endReason"):
            return None if last["endReason"] == "COMPLETED" else f"run ended {last['endReason']}: {last.get('detail', '')}"
        status, questions = user.call("GET", f"/api/sessions/{session}/questions")
        for question in (questions or {}).get("questions", []) if status == 200 else []:
            if question["id"] in answered: continue
            answered.add(question["id"])
            wanted = answers.get("*", "")
            choice = next((c["id"] for c in question.get("choices", []) if wanted and (wanted in str(c.get("label")) or wanted == c["id"])), None)
            # 场景没写答案时选第一个选项（模型自己多问了一句）；写了答案但对不上选项时当作自由回答
            if choice: payload = {"choice": choice}
            elif wanted and question.get("freeText"): payload = {"text": wanted}
            elif question.get("choices"): payload = {"choice": question["choices"][0]["id"]}
            else: payload = {"text": wanted or "ok"}
            user.call("POST", f"/api/sessions/{session}/questions/{question['id']}", payload)
            if posted is not None: posted.append(payload)
        calls_so_far = journal(session)[2]
        if time.time() - start > timeout or calls_so_far > max_calls:
            if last.get("run"): user.call("POST", f"/api/runs/{last['run']}/cancel", {})
            time.sleep(2)
            return f"stopped: {'timeout ' + str(timeout) + 's' if time.time() - start > timeout else 'more than ' + str(max_calls) + ' model calls'}"

def run_one(user, scenario, model, recording=False):
    fixture.reset(ROOT)
    base = tree(ROOT) if recording else None
    if scenario["setup"]: scenario["setup"](ROOT)
    after_setup = tree(ROOT) if recording else None
    posted = []
    fixture.REQUESTS.clear()
    session = "s-" + secrets.token_hex(4)
    started = time.time()
    prompt = scenario["prompt"] + (RECORD_NOTE if recording else "")
    problem = ask(user, session, prompt, model, scenario["timeout"], scenario["answers"], scenario["max_calls"], posted)
    sessions = [session]
    # 同一会话的后几轮（多轮对话场景）
    for turn in scenario["turns"]:
        if problem is not None: break
        problem = ask(user, session, turn, model, scenario["timeout"], scenario["answers"], scenario["max_calls"], posted)
    if problem is None and scenario["followup"]:
        time.sleep(6)   # 记忆落盘
        session2 = "s-" + secrets.token_hex(4); sessions.append(session2)
        problem = ask(user, session2, scenario["followup"], model, scenario["timeout"], scenario["answers"], scenario["max_calls"])
    ctx = Ctx(); ctx.root = ROOT; ctx.seconds = time.time() - started
    ctx.calls, ctx.answer, ctx.model_calls, tokens, ctx.compactions, prompt_sha = [], "", 0, collections.Counter(), 0, None
    for each in sessions:
        calls, answer, model_calls, used, extra = journal(each)
        ctx.calls += calls; ctx.answer = answer or ctx.answer; ctx.model_calls += model_calls; tokens.update(used)
        ctx.compactions += extra["compactions"]; prompt_sha = prompt_sha or extra["promptSha256"]
    ctx.tools = collections.Counter(call["name"] for call in ctx.calls)
    failed = ([problem] if problem else [])
    try: failed += scenario["check"](ctx)
    except Exception as failure: failed.append(f"check crashed: {failure!r}")
    seen = collections.Counter((call["name"], json.dumps(call["args"], sort_keys=True, ensure_ascii=False)) for call in ctx.calls)
    # 没跑成（提交被拒、运行异常结束、模型报错）和“模型做错了”分开：前者不算进通过率
    # 被钩子或插件拦下（STOPPED）、被取消是这次运行的结果，算没过；提交被拒、运行失败（FAILED：模型报错、服务器出错）才算没跑成
    blocked = problem if problem and (problem.startswith("submit ") or problem.startswith("run ended FAILED")) else None
    return {"id": scenario["id"], "group": scenario["group"], "risk": scenario["risk"], "passed": not failed, "failed": failed, "model_calls": ctx.model_calls,
            "blocked": blocked, "compactions": ctx.compactions, "promptSha256": prompt_sha,
            "tools": dict(ctx.tools), "errors": [{"tool": c["name"], "args": c["args"], "text": (c["result"] or "")[:400]} for c in ctx.calls if c["error"]],
            "repeats": max(seen.values()) if seen else 0, "seconds": round(ctx.seconds), "tokens": dict(tokens), "answer": ctx.answer[:1500],
            "calls": [{"name": c["name"], "args": c["args"], "error": c["error"], "result": (c["result"] or "")[:600]} for c in ctx.calls], "sessions": sessions,
            "full_calls": ctx.calls if recording else None, "_record": (base, after_setup, posted) if recording else None}

# 单独起服务器的配置（scenarios.py 里 S(..., server=名字)）。small-window：窗口很小、压缩点很低，长任务中途一定压缩
SERVERS = {
    None: [],
    "small-window": ["--tantalum.compaction.window-from-model=false", "--tantalum.compaction.reserve-tokens=2000",
                     "--tantalum.openai-routes.local.context-window=12000", "--tantalum.openai-routes.local.max-tokens=1024",
                     "--tantalum.claude.context-window=18000", "--tantalum.claude.max-tokens=2048"],
}
# 每组服务器起来后给用户设的压缩点（设置页“模型”一栏的百分比），按模型分：本机 12k 窗口用满（压缩点 10k；qwen 压缩一次后系统提示、工具、摘要和保留的消息就有九千二左右，压缩点低于这个数时每一步都要再压，10k、11k 的窗口一轮压六到十次、丢进度；qwen 光系统提示和
# 工具就 5.4k；9k 时也能压缩，但一轮要压七八次、模型会丢进度）；Claude 第一次调用就有九千多 token，18k 窗口取 50%（9k）
PERCENT = {"small-window": {"qwen": 100, "qwen-think": 100, "claude": 50, "opus": 50, "haiku": 50, "fable": 50}}
# 每百万 token 的美元价（输入、输出、读缓存、写缓存）；本机模型不花钱
# 每百万 token 的美元价：输入、输出、读缓存、写缓存（写 5 分钟缓存按输入的 1.25 倍）。2026-11-13 照 claude-api 技能的价格表；
# 之前 Sonnet 按旧价 3/15/0.3 算，报的费用多了约一半
PRICES = {"claude": (2.0, 10.0, 0.10, 2.5), "opus": (4.0, 20.0, 0.20, 5.0), "haiku": (0.10, 0.50, 0.01, 0.125),
          "fable": (8.0, 40.0, 0.20, 10.0), "qwen": (0.0, 0.0, 0.0, 0.0), "qwen-think": (0.0, 0.0, 0.0, 0.0)}

def cost(model, tokens):
    price = PRICES.get(model, (0, 0, 0, 0))
    return sum(tokens.get(key, 0) * per / 1e6 for key, per in zip(("input", "output", "cacheRead", "cacheWrite"), price))

def commit():
    head = subprocess.run(["git", "rev-parse", "--short", "HEAD"], cwd=REPO, capture_output=True, text=True).stdout.strip()
    dirty = subprocess.run(["git", "status", "--porcelain", "--untracked-files=no"], cwd=REPO, capture_output=True, text=True).stdout.strip()
    return head + ("-dirty" if dirty else "")

def summarize(results, repeat):
    """每个场景一行：通过 k/N、平均步数和 token、费用；k 在 1..N-1 是“不稳定”，N>1 时全过是“已饱和”。"""
    rows = collections.OrderedDict()
    for r in results:
        rows.setdefault(r["id"], []).append(r)
    table = {}
    for sid, runs in rows.items():
        done = [r for r in runs if not r.get("blocked")]
        passed = sum(r["passed"] for r in done)
        flag = "" if len(done) < 2 else "不稳定" if 0 < passed < len(done) else "已饱和" if passed == len(done) else ""
        avg = lambda key: round(sum(r[key] for r in done) / len(done), 1) if done else 0
        table[sid] = {"passed": passed, "runs": len(done), "planned": repeat, "flag": flag, "calls": avg("model_calls"),
                      "tokens": round(sum(sum(r["tokens"].values()) for r in done) / len(done)) if done else 0,
                      "cost": round(sum(r.get("cost", 0) for r in done), 4), "group": runs[0]["group"], "world": runs[0].get("world", True),
                      "failed": sorted({f for r in done for f in r["failed"]})[:3], "blocked": [r.get("blocked") for r in runs if r.get("blocked")]}
    return table

def compare(table, now, path):
    """和上一轮比：通过率、平均步数、平均 token。"""
    before = json.load(open(path, encoding="utf-8"))
    lines = ["", f"## 和 {os.path.basename(path)} 比", ""]
    if before.get("model") != now["model"]: lines.append(f"注意：模型不同（{before.get('model')} → {now['model']}）")
    if before.get("promptSha256") != now["promptSha256"]: lines.append("注意：系统提示变了（promptSha256 不同），差别可能来自提示而不是代码")
    old = before.get("summary", {})
    lines += ["| 场景 | 通过率 | 平均步数 | 平均 token |", "|---|---|---|---|"]
    for sid, row in table.items():
        prev = old.get(sid)
        if prev is None:
            lines.append(f"| {sid} | 新场景 {row['passed']}/{row['runs']} | {row['calls']} | {row['tokens']} |"); continue
        rate = lambda x: x["passed"] / x["runs"] if x["runs"] else 0
        arrow = "↑" if rate(row) > rate(prev) else "↓" if rate(row) < rate(prev) else "="
        lines.append(f"| {sid} | {prev['passed']}/{prev['runs']} → {row['passed']}/{row['runs']} {arrow} | {prev['calls']} → {row['calls']} | {prev['tokens']} → {row['tokens']} |")
    return lines

# ---- 录制（agent-eval 需求 2.4）：判分通过的场景写进 tantalum-examples/src/test/resources/replay/<id>/，由 ReplayScenarioTest 回放 ----
REPLAY_DIR = os.path.join(REPO, "tantalum-examples", "src", "test", "resources", "replay")
# 回放时 bash 不真的执行，模型用命令行改的文件回放不出来：录制时在提问后面加这一句（存进 scenario.json，回放用同一句）
RECORD_NOTE = "（改文件请用 edit_file 或 write_file，不要用命令行或脚本改文件。）"
# 回放的测试电脑没有浏览器、联网站点、技能和知识库目录，也不开第二个会话
NOT_REPLAYABLE_GROUPS = {"联网", "浏览器", "技能"}
# 录制只在 macOS 上做，所以只查 macOS 和 Linux 的本机路径；Windows 盘符和转义过的代码（"usage:\\n"）分不开，不查
SENSITIVE = re.compile(r"/Users/|/home/|/private/var/|/var/folders/|sk-ant-|api[_-]?key\s*[:=]", re.IGNORECASE)

def replayable(scenario):
    # 用到测试站的（注入网页等）也不录：回放时没有这个站
    return scenario["group"] not in NOT_REPLAYABLE_GROUPS and not scenario["followup"] and SITE_URL not in scenario["prompt"]

def tree(root):
    """项目里的文件（不含 .git、__pycache__、node_modules 的内容）：相对路径 → 字节。"""
    files = {}
    for folder, dirs, names in os.walk(root):
        dirs[:] = [d for d in dirs if d not in (".git", "__pycache__", "node_modules")]
        for name in names:
            path = os.path.join(folder, name)
            files[os.path.relpath(path, root).replace(os.sep, "/")] = open(path, "rb").read()
    return files

def diff(before, after):
    return {p: b for p, b in after.items() if before.get(p) != b}, sorted(p for p in before if p not in after)

TMP = re.compile(r"(?:/private)?/var/folders/[^/\s\"']+/[^/\s\"']+/T/")

def normalize(text):
    for prefix in sorted({ROOT, os.path.realpath(ROOT)}, key=len, reverse=True):
        text = text.replace(prefix, "{{root}}")
    # 系统临时目录（连接程序给命令的 TMPDIR、npm 的日志目录）：只是位置，换成 {{tmp}}，回放时 bash 的结果原样喂回去
    return TMP.sub("{{tmp}}/", text)

def write_files(target, files):
    for path, data in files.items():
        full = os.path.join(target, path); os.makedirs(os.path.dirname(full), exist_ok=True)
        open(full, "wb").write(data)

def record_seed():
    """种子项目：Git 历史打成一个 bundle，没提交的改动另放（node_modules 只留一个空目录的占位）。"""
    os.makedirs(REPLAY_DIR, exist_ok=True)
    fixture.reset(ROOT)
    subprocess.run(["git", "bundle", "create", os.path.join(REPLAY_DIR, "project.bundle"), "--all"], cwd=ROOT, check=True, capture_output=True)
    tracked = set(subprocess.run(["git", "ls-files", "-z"], cwd=ROOT, capture_output=True, text=True, check=True).stdout.split("\0"))
    head = {}
    for path in tracked:
        if path:
            blob = subprocess.run(["git", "show", f"HEAD:{path}"], cwd=ROOT, capture_output=True, check=True).stdout
            head[path] = blob
    dirty, _ = diff(head, tree(ROOT))
    target = os.path.join(REPLAY_DIR, "project-dirty")
    shutil.rmtree(target, ignore_errors=True)
    write_files(target, dirty)
    os.makedirs(os.path.join(target, "node_modules"), exist_ok=True)
    open(os.path.join(target, "node_modules", ".placeholder"), "w").write("node_modules is not part of the replay seed\n")

def record(scenario, result, base, after_setup, posted):
    """写一个场景的录音；有本机路径或像密钥的东西就不写。返回没写的原因，写了返回 None。"""
    final = tree(ROOT)
    setup_files, setup_deleted = diff(base, after_setup)
    expected_files, expected_deleted = diff(after_setup, final)
    replies, tools = [], []
    for session in result["sessions"]:
        for line in open(f"{DATA}/sessions/dev/{session}.jsonl", encoding="utf-8"):
            entry = json.loads(line)
            if entry.get("type") == "ModelReplied":
                replies.append(normalize(json.dumps({"type": entry["type"], "version": entry["version"], "payload": entry["payload"]}, ensure_ascii=False)))
    for call in result["full_calls"]:
        if call["name"] == "ask_user": continue   # 回答不是工具结果事件，回放时由 answers 交
        tools.append(normalize(json.dumps({"name": call["name"], "text": call["result"] or "", "error": bool(call["error"])}, ensure_ascii=False)))
    server_args = [a for a in SERVERS[scenario["server"]] if a.startswith("--tantalum.compaction.") or a.startswith("--tantalum.claude.")]
    meta = {"id": scenario["id"], "prompt": scenario["prompt"] + RECORD_NOTE, "turns": scenario["turns"], "write": True, "serverArgs": server_args, "answers": posted,
            "recordedWith": {"model": MODELS["claude"]()["model"], "commit": commit(), "date": time.strftime("%Y-%m-%d")}}
    texts = replies + tools + [json.dumps(meta, ensure_ascii=False)] + [b.decode("utf-8", "replace") for b in list(setup_files.values()) + list(expected_files.values())]
    leaked = [text[max(0, m.start() - 80):m.end() + 80] for text in texts for m in [SENSITIVE.search(text)] if m]
    if leaked: return f"not recorded: it contains {leaked[0]!r}"
    target = os.path.join(REPLAY_DIR, scenario["id"])
    shutil.rmtree(target, ignore_errors=True); os.makedirs(target)
    json.dump(meta, open(os.path.join(target, "scenario.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    open(os.path.join(target, "replies.jsonl"), "w", encoding="utf-8").write("\n".join(replies) + "\n")
    open(os.path.join(target, "tools.jsonl"), "w", encoding="utf-8").write("\n".join(tools) + ("\n" if tools else ""))
    write_files(os.path.join(target, "setup"), setup_files)
    if setup_deleted: open(os.path.join(target, "setup-deleted.txt"), "w").write("\n".join(setup_deleted) + "\n")
    write_files(os.path.join(target, "expected"), expected_files)
    if expected_deleted: open(os.path.join(target, "expected-deleted.txt"), "w").write("\n".join(expected_deleted) + "\n")
    return None

def verify_recordings(ids):
    """刚录的场景马上回放一次；回放不过的删掉（例如模型用 bash 改了文件，回放时 bash 不真的执行）。返回回放不过的。"""
    if not ids: return []
    out = subprocess.run(["mvn", "-o", "-q", "-pl", "tantalum-examples", "-am", "install", "-DskipTests", "-Djacoco.skip=true"], cwd=REPO, capture_output=True, text=True)
    if out.returncode != 0: raise SystemExit("could not build before verifying recordings:\n" + out.stdout[-2000:])
    subprocess.run(["mvn", "-o", "-q", "-pl", "tantalum-examples", "test", "-Dtest=ReplayScenarioTest", f"-Dreplay.only={','.join(ids)}",
                    "-Dsurefire.failIfNoSpecifiedTests=false", "-Djacoco.skip=true"], cwd=REPO, capture_output=True, text=True)
    report = os.path.join(REPO, "tantalum-examples", "target", "surefire-reports", "TEST-tantalum.shell.boot.ReplayScenarioTest.xml")
    text = open(report, encoding="utf-8").read() if os.path.exists(report) else ""
    # 报告里的用例叫 replaysAsRecorded(String)[k]：第 k 个，按场景名排序（和 ReplayScenarioTest.scenarios() 一样）
    bad = []
    for k, sid in enumerate(sorted(ids), start=1):
        case = re.search(r'<testcase name="replaysAsRecorded\(String\)\[' + str(k) + r'\]"[^>]*?(/>|>(.*?)</testcase>)', text, re.S)
        if case is None:
            # 找不到结果是验证本身的问题，不当成回放失败：留着，手动跑 ReplayScenarioTest
            bad.append((sid, "verification produced no result; kept")); continue
        body = case.group(2) or ""
        if "<failure" in body or "<error" in body:
            reason = re.search(r'message="([^"]*)"', body)
            bad.append((sid, (reason.group(1) if reason else "failed")[:400].replace("&#10;", " ").replace("&quot;", '"')))
            # 不直接删：挪到 results/rejected/ 里，看得到为什么回放不过
            rejected = os.path.join(HERE, "results", "rejected", sid)
            shutil.rmtree(rejected, ignore_errors=True); os.makedirs(os.path.dirname(rejected), exist_ok=True)
            shutil.move(os.path.join(REPLAY_DIR, sid), rejected)
    return bad


def main():
    global MXC
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--model", default="qwen", choices=sorted(MODELS)); parser.add_argument("--only", default="", help="comma-separated scenario ids")
    parser.add_argument("--repeat", type=int, default=1, help="runs per scenario (pass rate, unstable, saturated)")
    parser.add_argument("--compare", default="", help="an earlier results .json to compare with")
    parser.add_argument("--record", action="store_true", help="write passing replayable scenarios into the replay fixtures (Claude only) and verify them")
    parser.add_argument("--tag", default="", help="suffix of the result files"); parser.add_argument("--keep", action="store_true", help="keep the temporary directory")
    parser.add_argument("--local-url", default=LOCAL["url"]); parser.add_argument("--local-model", default=LOCAL["model"]); parser.add_argument("--mxc", default=MXC)
    args = parser.parse_args()
    LOCAL.update(url=args.local_url, model=args.local_model); MXC = args.mxc
    global MODEL_NAME; MODEL_NAME = args.model
    os.makedirs(os.path.join(HERE, "results"), exist_ok=True)
    wanted = [each for each in args.only.split(",") if each]
    unknown = [each for each in wanted if each not in {s["id"] for s in SCENARIOS}]
    if unknown: raise SystemExit(f"unknown scenarios: {unknown}")
    chosen = [s for s in SCENARIOS if not wanted or s["id"] in wanted]
    if args.record:
        if args.model != "claude": raise SystemExit("--record is for claude: replay uses the Claude route's token estimate")
        chosen = [s for s in chosen if replayable(s)]
        args.repeat = 1
    recorded, skipped = [], []
    stamp = time.strftime("%m%d-%H%M") + "-" + args.model + ("-" + args.tag if args.tag else "")
    out = os.path.join(HERE, "results", stamp)
    # 跑之前写好计划：哪些场景、各几次（照 pi packages/evals 的 expected-runs）；没跑成的不给总通过率
    report = {"model": args.model, "commit": commit(), "repeat": args.repeat, "plan": [s["id"] for s in chosen], "promptSha256": None, "results": []}
    results = report["results"]
    site = fixture.serve(SITE_PORT)
    try:
        if args.record:
            os.makedirs(WORK, exist_ok=True); fixture.build(ROOT); record_seed()
        for group in dict.fromkeys(s["server"] for s in chosen):
            members = [s for s in chosen if s["server"] == group]
            try:
                user = start(SERVERS[group], group)
            except (SystemExit, AssertionError, OSError) as failure:
                for scenario in members:
                    for run in range(1, args.repeat + 1):
                        results.append({"id": scenario["id"], "group": scenario["group"], "run": run, "passed": False, "blocked": f"server {group or 'default'}: {failure}",
                                        "failed": [], "model_calls": 0, "tools": {}, "errors": [], "repeats": 0, "seconds": 0, "tokens": {}, "world": scenario["world"]})
                stop_all(keep=True); started.clear(); continue
            for scenario in members:
                for run in range(1, args.repeat + 1):
                    result = run_one(user, scenario, MODELS[args.model](), recording=args.record); result["run"] = run; result["world"] = scenario["world"]
                    if args.record:
                        base, after_setup, posted = result.pop("_record")
                        if result["passed"]:
                            why = record(scenario, result, base, after_setup, posted)
                            (skipped.append((scenario["id"], why)) if why else recorded.append(scenario["id"]))
                        else:
                            skipped.append((scenario["id"], "did not pass"))
                    result.pop("full_calls", None); result.pop("_record", None)
                    result["cost"] = round(cost(args.model, result["tokens"]), 4)
                    report["promptSha256"] = report["promptSha256"] or result.get("promptSha256")
                    results.append(result)
                    print(f"{'PASS' if result['passed'] else 'BLOCK' if result.get('blocked') else 'FAIL'} {result['id']:13} #{run} calls={result['model_calls']:2} "
                          f"tools={sum(result['tools'].values()):2} errors={len(result['errors'])} compact={result.get('compactions', 0)} repeat={result['repeats']} "
                          f"{result['seconds']:3}s ${result['cost']:.3f} {'; '.join(result['failed'])[:150]}", flush=True)
                    json.dump(report, open(out + ".json", "w"), ensure_ascii=False, indent=1)
            stop_all(keep=True); started.clear()
    finally:
        site.shutdown()
        stop_all(args.keep)
    table = summarize(results, args.repeat)
    report["summary"] = table
    json.dump(report, open(out + ".json", "w"), ensure_ascii=False, indent=1)
    blocked = [r for r in results if r.get("blocked")]
    planned = len(chosen) * args.repeat
    passed = sum(r["passed"] for r in results if not r.get("blocked"))
    head = (f"{passed} / {planned} runs passed" if not blocked else
            f"总通过率不给：计划 {planned} 次，{len(blocked)} 次没跑成（{blocked[0]['blocked'][:120]}）")
    spent = sum(r.get("cost", 0) for r in results)
    lines = [f"# {stamp}", "", head, "", f"模型 {args.model} · 提交 {report['commit']} · 每个场景 {args.repeat} 次 · 系统提示 {str(report['promptSha256'])[:12]} · 费用约 ${spent:.2f}", "",
             "| 场景 | 组 | 判分 | 通过 | 标记 | 平均模型调用 | 平均 token | 费用 | 没过的标准 |", "|---|---|---|---|---|---|---|---|---|"]
    for sid, row in table.items():
        lines.append(f"| {sid} | {row['group']} | {'世界' if row['world'] else '回答'} | {row['passed']}/{row['runs']}"
                     f"{'' if row['runs'] == row['planned'] else ' (计划 ' + str(row['planned']) + ')'} | {row['flag']} | {row['calls']} | {row['tokens']} | ${row['cost']:.3f} | "
                     f"{'; '.join(row['blocked'][:1] or row['failed'])} |")
    if args.compare: lines += compare(table, report, args.compare)
    if args.record:
        failed_replay = verify_recordings(recorded)
        kept = [sid for sid in recorded if sid not in dict(failed_replay)]
        lines += ["", "## 录制", "", f"录下并回放通过：{', '.join(kept) or '无'}"]
        lines += [f"- 没录 {sid}：{why}" for sid, why in skipped] + [f"- 录了但回放不过，挪到 results/rejected/{sid}：{why}" for sid, why in failed_replay]
    open(out + ".md", "w", encoding="utf-8").write("\n".join(lines) + "\n")
    print("\n".join(lines[2:5]), "->", out + ".md")
    sys.exit(2 if blocked else 0)

if __name__ == "__main__":
    main()
