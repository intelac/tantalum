"""真实场景的工具测试：临时服务器 + 真的连接程序 + 真模型，在一份每次重新生成的小项目上跑一批真实任务。

用法（先在仓库根目录 mvn -o package -DskipTests 打好包）：

    python3 tantalum-examples/scenarios/run.py --model qwen            # 本机 OpenAI 兼容模型，默认 http://127.0.0.1:8090/v1
    python3 tantalum-examples/scenarios/run.py --model claude          # 要 ANTHROPIC_API_KEY（环境变量，或 ~/.config/tantalum/env）
    python3 tantalum-examples/scenarios/run.py --model qwen --only fix-bug,rename

不碰 ~/.local/share/tantalum：服务器的数据目录、项目目录、浏览器资料目录都在一个临时目录里，跑完删掉（--keep 保留）。
结果写到 tantalum-examples/scenarios/results/（不进 Git）：一份 .json（每个场景的每次工具调用）和一份 .md（表格）。
真模型的结果有随机性：一个场景没过，先再跑一次。判定标准只查事实（文件内容、测试结果、回答里藏着的编号），不看措辞。
"""
import argparse, glob, json, os, secrets, shutil, signal, subprocess, sys, tempfile, time, urllib.request, urllib.error, http.cookiejar, collections
import fixture
from scenarios import SCENARIOS

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(os.path.dirname(HERE))
WORK = tempfile.mkdtemp(prefix="tantalum-scenarios-")
ROOT = os.path.join(WORK, "inventory")
PORT, SITE_PORT = 8094, 8766
BASE = f"http://127.0.0.1:{PORT}"
LOCAL = {"url": "http://127.0.0.1:8090/v1", "model": "qwen3.8-flash-next-unsloth-ud-q4_k_xl"}
MODELS = {"qwen": lambda: {"route": "local", "model": LOCAL["model"], "effort": "off"},
          "claude": lambda: {"route": "claude", "model": "claude-sonnet-5-5", "effort": "medium"}}
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

def start():
    # 先把会失败的都查完再起进程，免得留下一台没人管的服务器
    cp = connector_classpath()
    if not os.path.exists(MXC): raise SystemExit(f"the sandbox binary is missing at {MXC}; build the macOS connector package once (it downloads MXC) or pass --mxc")
    shell_jar, examples_jar = jar("tantalum-shell", "-exec"), jar("tantalum-examples")
    fixture.build(ROOT)
    site = fixture.serve(SITE_PORT)
    admin_pw, user_pw = secrets.token_urlsafe(18), secrets.token_urlsafe(18)
    env = env_file(); env.update(TANTALUM_ADMIN_USER="root", TANTALUM_ADMIN_PASSWORD=admin_pw)
    server = subprocess.Popen(["java", "--enable-native-access=ALL-UNNAMED", f"-Dloader.path={examples_jar}", "-cp", shell_jar,
        "org.springframework.boot.loader.launch.PropertiesLauncher", f"--server.port={PORT}", f"--tantalum.data-dir={WORK}/data", f"--tantalum.workspace={WORK}/ws",
        "--tantalum.examples.enabled=true", "--tantalum.provider=local", f"--tantalum.openai-routes.local.base-url={LOCAL['url']}",
        f"--tantalum.openai-routes.local.model={LOCAL['model']}", "--tantalum.openai-routes.local.thinking=chat-template", "--tantalum.openai-routes.local.context-window=131072",
        "--tantalum.web.block-private-network=false"], env=env, stdout=open(f"{WORK}/server.log", "w"), stderr=subprocess.STDOUT)
    started.append(server)
    admin = Client()
    for _ in range(90):
        try:
            if admin.call("POST", "/api/auth/login", {"username": "root", "password": admin_pw})[0] == 200: break
        except OSError: pass
        time.sleep(1)
    else: raise SystemExit(f"server did not start; see {WORK}/server.log")
    assert admin.call("POST", "/api/users", {"name": "dev", "password": user_pw, "admin": False})[0] == 200
    user = Client(); assert user.call("POST", "/api/auth/login", {"username": "dev", "password": user_pw})[0] == 200
    status, token = user.call("POST", "/api/local/tokens", {"validity": "1d"}); assert status == 200, token
    cenv = dict(os.environ, TANTALUM_TOKEN=token["token"])
    connector = subprocess.Popen(["java", "--enable-native-access=ALL-UNNAMED", "-cp", cp, "tantalum.connect.Main", "--server", BASE, "--allow", ROOT,
        "--allow-write", "--allow-shell", "--mxc", MXC, "--allow-browser", "--browser-headless",
        "--browser-profile", f"{WORK}/browser-profile", "--lang", "en"], env=cenv, cwd=ROOT, stdout=open(f"{WORK}/connector.log", "w"), stderr=subprocess.STDOUT)
    started.append(connector)
    for _ in range(40):
        link = user.call("GET", "/api/local")[1].get("link")
        if link: break
        time.sleep(1)
    else: raise SystemExit(f"connector did not connect; see {WORK}/connector.log")
    print("computer:", {k: link.get(k) for k in ("write", "git")}, "shell", link["shell"].get("available"), link["shell"].get("reason", ""), "browser", (link.get("browser") or {}).get("available"), flush=True)
    assert user.call("PUT", "/api/local/skills", {"dir": os.path.join(ROOT, "skills")})[0] == 200
    assert user.call("PUT", "/api/local/wiki", {"dir": os.path.join(ROOT, "wiki")})[0] == 200
    return server, connector, site, user

class Ctx: pass

def journal(session):
    path = f"{WORK}/data/sessions/dev/{session}.jsonl"
    calls, by_id, answer, model_calls, tokens = [], {}, "", 0, collections.Counter()
    if not os.path.exists(path): return calls, answer, model_calls, tokens
    for line in open(path, encoding="utf-8"):
        try: entry = json.loads(line)
        except ValueError: continue
        payload = entry.get("payload") or {}
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
    return calls, answer, model_calls, tokens

def ask(user, session, text, model, timeout, answers, max_calls):
    request = secrets.token_hex(6)
    status, body = user.call("POST", f"/api/sessions/{session}/submissions", {"requestId": request, "mode": "prompt", "text": text, "model": model})
    if status != 200: return f"submit {status}: {body}"
    start, answered = time.time(), set()
    while True:
        time.sleep(1.5)
        status, runs = user.call("GET", f"/api/sessions/{session}/runs")
        last = (runs or {}).get("runs", [{}])[-1] if status == 200 and (runs or {}).get("runs") else {}
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
        calls_so_far = journal(session)[2]
        if time.time() - start > timeout or calls_so_far > max_calls:
            if last.get("run"): user.call("POST", f"/api/runs/{last['run']}/cancel", {})
            time.sleep(2)
            return f"stopped: {'timeout ' + str(timeout) + 's' if time.time() - start > timeout else 'more than ' + str(max_calls) + ' model calls'}"

def run_one(user, scenario, model):
    fixture.reset(ROOT)
    if scenario["setup"]: scenario["setup"](ROOT)
    session = "s-" + secrets.token_hex(4)
    started = time.time()
    problem = ask(user, session, scenario["prompt"], model, scenario["timeout"], scenario["answers"], scenario["max_calls"])
    sessions = [session]
    if problem is None and scenario["followup"]:
        time.sleep(6)   # 记忆落盘
        session2 = "s-" + secrets.token_hex(4); sessions.append(session2)
        problem = ask(user, session2, scenario["followup"], model, scenario["timeout"], scenario["answers"], scenario["max_calls"])
    ctx = Ctx(); ctx.root = ROOT; ctx.seconds = time.time() - started
    ctx.calls, ctx.answer, ctx.model_calls, tokens = [], "", 0, collections.Counter()
    for each in sessions:
        calls, answer, model_calls, used = journal(each)
        ctx.calls += calls; ctx.answer = answer or ctx.answer; ctx.model_calls += model_calls; tokens.update(used)
    ctx.tools = collections.Counter(call["name"] for call in ctx.calls)
    failed = ([problem] if problem else [])
    try: failed += scenario["check"](ctx)
    except Exception as failure: failed.append(f"check crashed: {failure!r}")
    seen = collections.Counter((call["name"], json.dumps(call["args"], sort_keys=True, ensure_ascii=False)) for call in ctx.calls)
    return {"id": scenario["id"], "group": scenario["group"], "risk": scenario["risk"], "passed": not failed, "failed": failed, "model_calls": ctx.model_calls,
            "tools": dict(ctx.tools), "errors": [{"tool": c["name"], "args": c["args"], "text": (c["result"] or "")[:400]} for c in ctx.calls if c["error"]],
            "repeats": max(seen.values()) if seen else 0, "seconds": round(ctx.seconds), "tokens": dict(tokens), "answer": ctx.answer[:1500],
            "calls": [{"name": c["name"], "args": c["args"], "error": c["error"], "result": (c["result"] or "")[:600]} for c in ctx.calls], "sessions": sessions}

def main():
    global MXC
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--model", default="qwen", choices=sorted(MODELS)); parser.add_argument("--only", default="", help="comma-separated scenario ids")
    parser.add_argument("--tag", default="", help="suffix of the result files"); parser.add_argument("--keep", action="store_true", help="keep the temporary directory")
    parser.add_argument("--local-url", default=LOCAL["url"]); parser.add_argument("--local-model", default=LOCAL["model"]); parser.add_argument("--mxc", default=MXC)
    args = parser.parse_args()
    LOCAL.update(url=args.local_url, model=args.local_model); MXC = args.mxc
    os.makedirs(os.path.join(HERE, "results"), exist_ok=True)
    chosen = [s for s in SCENARIOS if not args.only or s["id"] in args.only.split(",")]
    results = []
    stamp = time.strftime("%m%d-%H%M") + "-" + args.model + ("-" + args.tag if args.tag else "")
    out = os.path.join(HERE, "results", stamp)
    try:
        server, connector, site, user = start()
        for scenario in chosen:
            result = run_one(user, scenario, MODELS[args.model]()); results.append(result)
            print(f"{'PASS' if result['passed'] else 'FAIL'} {result['id']:13} calls={result['model_calls']:2} tools={sum(result['tools'].values()):2} errors={len(result['errors'])} "
                  f"repeat={result['repeats']} {result['seconds']:3}s {'; '.join(result['failed'])[:150]}", flush=True)
            json.dump(results, open(out + ".json", "w"), ensure_ascii=False, indent=1)
    finally:
        stop_all(args.keep)
    lines = [f"# {stamp}", "", f"{sum(r['passed'] for r in results)} / {len(results)} passed", "", "| 场景 | 组 | 结果 | 模型调用 | 工具调用 | 出错 | 重复 | 秒 | 没过的标准 |", "|---|---|---|---|---|---|---|---|---|"]
    for r in results:
        lines.append(f"| {r['id']} | {r['group']} | {'过' if r['passed'] else '**没过**'} | {r['model_calls']} | {sum(r['tools'].values())} | {len(r['errors'])} | {r['repeats']} | {r['seconds']} | {'; '.join(r['failed'])} |")
    total = collections.Counter()
    for r in results: total.update(r["tokens"])
    lines += ["", f"tokens: {dict(total)}"]
    open(out + ".md", "w", encoding="utf-8").write("\n".join(lines) + "\n")
    print("\n".join(lines[2:3]), "->", out + ".md")

if __name__ == "__main__":
    main()
