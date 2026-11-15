#!/usr/bin/env python3
"""LongMemEval 子集（tier 3，要真模型，不进 CI）：公开的长期记忆考题，和别家比。

三步：
  pick   从 longmemeval_s_cleaned.json（HuggingFace xiaowu0162/longmemeval-cleaned，MIT）里按五种能力各挑 N 道（默认 6，共 30），
         固定种子，写成 subset.json。五种能力：找事实（single-session-user/assistant/preference）、跨会话（multi-session）、
         记住更新（knowledge-update）、按时间推理（temporal-reasoning）、不知道就说不知道（question_id 以 _abs 结尾）。
  build  用真的记笔记代码导入每道题的旧对话（LongMemEvalImport，Claude Haiku 5.5 记笔记），每道题记出一本记忆账。
  ask    起一台真服务器，每道题一个新用户、放进他的账，用题目的 question_date 提问；memory（用记忆）和 none（关掉记忆）两组。
         判分用官方 evaluate_qa.py 的判题提示（按题型分五种），裁判换成 Claude Sonnet 5.5（官方用 GPT-4o）；回答里有 yes 算对。

数据很大（277 MB），放在命令行给的目录里，不进仓库。结果写到 scenarios/results/<时间>-longmemeval-<模型>.md 和 .json。
"""
import argparse, collections, json, os, random, secrets, shutil, signal, subprocess, sys, tempfile, time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import memory_eval as me  # noqa: E402  服务器、登录、提问、读回答都和第二层一样

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(os.path.dirname(HERE))
ABILITIES = ["extraction", "multi-session", "knowledge-update", "temporal", "abstention"]
JUDGE_PRICE = {"claude-sonnet-5-5": (2.0, 10.0), "claude-opus-5-5": (4.0, 20.0), "claude-haiku-5-5": (0.10, 0.50)}

# 官方 src/evaluation/evaluate_qa.py 的 get_anscheck_prompt，逐字照抄（MIT）
STANDARD = ("I will give you a question, a correct answer, and a response from a model. Please answer yes if the response contains the "
            "correct answer. Otherwise, answer no. If the response is equivalent to the correct answer or contains all the intermediate "
            "steps to get the correct answer, you should also answer yes. If the response only contains a subset of the information "
            "required by the answer, answer no. \n\nQuestion: {}\n\nCorrect Answer: {}\n\nModel Response: {}\n\nIs the model response "
            "correct? Answer yes or no only.")
TEMPORAL = ("I will give you a question, a correct answer, and a response from a model. Please answer yes if the response contains the "
            "correct answer. Otherwise, answer no. If the response is equivalent to the correct answer or contains all the intermediate "
            "steps to get the correct answer, you should also answer yes. If the response only contains a subset of the information "
            "required by the answer, answer no. In addition, do not penalize off-by-one errors for the number of days. If the question "
            "asks for the number of days/weeks/months, etc., and the model makes off-by-one errors (e.g., predicting 19 days when the "
            "answer is 18), the model's response is still correct. \n\nQuestion: {}\n\nCorrect Answer: {}\n\nModel Response: {}\n\nIs "
            "the model response correct? Answer yes or no only.")
UPDATE = ("I will give you a question, a correct answer, and a response from a model. Please answer yes if the response contains the "
          "correct answer. Otherwise, answer no. If the response contains some previous information along with an updated answer, the "
          "response should be considered as correct as long as the updated answer is the required answer.\n\nQuestion: {}\n\nCorrect "
          "Answer: {}\n\nModel Response: {}\n\nIs the model response correct? Answer yes or no only.")
PREFERENCE = ("I will give you a question, a rubric for desired personalized response, and a response from a model. Please answer yes "
              "if the response satisfies the desired response. Otherwise, answer no. The model does not need to reflect all the points "
              "in the rubric. The response is correct as long as it recalls and utilizes the user's personal information correctly."
              "\n\nQuestion: {}\n\nRubric: {}\n\nModel Response: {}\n\nIs the model response correct? Answer yes or no only.")
ABSTENTION = ("I will give you an unanswerable question, an explanation, and a response from a model. Please answer yes if the model "
              "correctly identifies the question as unanswerable. The model could say that the information is incomplete, or some other "
              "information is given but the asked information is not.\n\nQuestion: {}\n\nExplanation: {}\n\nModel Response: {}\n\nDoes "
              "the model correctly identify the question as unanswerable? Answer yes or no only.")


def ability(question):
    if question["question_id"].endswith("_abs"): return "abstention"
    kind = question["question_type"]
    if kind.startswith("single-session"): return "extraction"
    return {"multi-session": "multi-session", "knowledge-update": "knowledge-update", "temporal-reasoning": "temporal"}[kind]


def judge_prompt(question, response):
    answer = str(question["answer"])
    if ability(question) == "abstention": template = ABSTENTION
    else: template = {"temporal-reasoning": TEMPORAL, "knowledge-update": UPDATE, "single-session-preference": PREFERENCE}.get(
        question["question_type"], STANDARD)
    return template.format(question["question"], answer, response)


# ---- pick ----

# 找回按服务器真的“今天”限定日期的说法（TimeWindow）；旧对话是 2023 年的，这种题会被错限定，挑题时跳过
RELATIVE = __import__("re").compile(r"\d+\s*days?\s+ago|yesterday|last week", __import__("re").IGNORECASE)


def pick(args):
    data = [q for q in json.load(open(args.data, encoding="utf-8")) if not RELATIVE.search(q["question"])]
    chooser = random.Random(args.seed)
    chosen = []
    for name in ABILITIES:
        pool = [q for q in data if ability(q) == name]
        if name == "extraction":
            # 三种找事实的题各挑三分之一：用户说的、助手说的、偏好
            kinds = ["single-session-user", "single-session-assistant", "single-session-preference"]
            for index, kind in enumerate(kinds):
                share = args.per // 3 + (1 if index < args.per % 3 else 0)
                chosen += chooser.sample([q for q in pool if q["question_type"] == kind], share)
        else:
            chosen += chooser.sample(pool, args.per)
    os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
    json.dump(chosen, open(args.out, "w", encoding="utf-8"), ensure_ascii=False)
    counts = collections.Counter(ability(q) for q in chosen)
    turns = sum(1 for q in chosen for s in q["haystack_sessions"] for t in s if t["role"] == "user")
    print(f"picked {len(chosen)} questions {dict(counts)}; {turns} user turns to note -> {args.out}")


# ---- build ----

def build(args):
    """在仓库里跑 LongMemEvalImport（不能和别的 mvn 同时跑在同一个目录里）。"""
    env = me.env_file()
    if not env.get("ANTHROPIC_API_KEY"): raise SystemExit("build needs ANTHROPIC_API_KEY (environment or ~/.config/tantalum/env)")
    repo = args.repo or REPO
    shell = os.path.join(repo, "tantalum-shell")
    # 测试类路径：先编译测试代码、列出依赖，再直接用 java 跑（surefire 十分钟就杀进程）
    listing = os.path.join(tempfile.mkdtemp(prefix="tantalum-lme-cp-"), "cp.txt")
    if subprocess.call(["mvn", "-o", "-q", "test-compile", "dependency:build-classpath", "-pl", "tantalum-shell", "-Djacoco.skip=true",
                        "-Dmdep.includeScope=test", f"-Dmdep.outputFile={listing}"], cwd=repo, env=env) != 0:
        raise SystemExit("cannot compile tantalum-shell tests")
    # 编译出的类拷到一边：导入要跑一个多小时，这期间仓库里重新编译不影响它
    frozen = os.path.dirname(listing)
    for part in ("test-classes", "classes"):
        shutil.copytree(os.path.join(shell, "target", part), os.path.join(frozen, part))
    classpath = os.pathsep.join([os.path.join(frozen, "test-classes"), os.path.join(frozen, "classes"), open(listing).read().strip()])
    command = ["java", "--enable-native-access=ALL-UNNAMED", "-cp", classpath, "tantalum.memory.capture.LongMemEvalImport",
               os.path.abspath(args.subset), os.path.abspath(args.ledgers), str(args.parallel)]
    started = time.time()
    code = subprocess.call(command, cwd=repo, env=env)
    print(f"build exit {code} after {round((time.time() - started) / 60, 1)} min", flush=True)
    for report in sorted(f for f in os.listdir(args.ledgers) if f.startswith("usage-")):
        usage = json.load(open(os.path.join(args.ledgers, report)))
        spent = (usage["input"] * 0.10 + usage["output"] * 0.50) / 1e6
        print(f"{report}: {usage['calls']} calls ({usage['failures']} failed), {usage['input']} in / {usage['output']} out, ~${spent:.2f}")
    return code


# ---- ask ----

def place(admin, data, ledger, name):
    """建一个新用户，在他第一次被访问之前放进导入的记忆账。"""
    state = os.path.join(data, "plugins", "memory", name)
    shutil.copytree(ledger, state, dirs_exist_ok=True, ignore=shutil.ignore_patterns(".done"))
    password = secrets.token_urlsafe(18)
    status, body = admin.call("POST", "/api/users", {"name": name, "password": password, "admin": False})
    assert status == 200, (name, status, body)
    client = me.Client()
    assert client.call("POST", "/api/auth/login", {"username": name, "password": password})[0] == 200
    client.call("PUT", "/api/settings/timezone", {"timezone": "UTC"})
    return client


def grade(grader, model, question, response, usage):
    reply = grader.messages.create(model=model, max_tokens=500, messages=[{"role": "user", "content": judge_prompt(question, response)}])
    usage["input"] += reply.usage.input_tokens; usage["output"] += reply.usage.output_tokens
    text = "".join(block.text for block in reply.content if block.type == "text")
    return "yes" in text.lower(), text.strip()[:80]


def ask_all(args):
    me.JARS.update(shell=args.shell_jar, examples=args.examples_jar)
    me.PORT = args.port; me.BASE = f"http://127.0.0.1:{args.port}"
    questions = json.load(open(args.subset, encoding="utf-8"))
    if args.only: questions = [q for q in questions if q["question_id"] in set(args.only.split(","))]
    missing = [q["question_id"] for q in questions if not os.path.exists(os.path.join(args.ledgers, q["question_id"], ".done"))]
    if missing: raise SystemExit(f"not imported yet: {', '.join(missing)}; run build first")
    import anthropic
    grader = anthropic.Anthropic(api_key=me.env_file().get("ANTHROPIC_API_KEY"))
    arms = [arm for arm in args.arms.split(",") if arm]
    stamp = time.strftime("%m%d-%H%M") + "-longmemeval-" + args.model + ("-" + args.tag if args.tag else "")
    out = os.path.join(HERE, "results", stamp)
    report = {"model": args.model, "judge": args.judge, "arms": arms, "questions": len(questions), "results": []}
    usage = collections.Counter()
    work = tempfile.mkdtemp(prefix="tantalum-lme-")
    try:
        for arm in arms:
            data = os.path.join(work, "data-" + arm)
            server, admin = me.start(data, secrets.token_urlsafe(18))
            try:
                for index, question in enumerate(questions):
                    name = f"lme-{arm}-{index + 1}"
                    client = place(admin, data, os.path.join(args.ledgers, question["question_id"]), name)
                    if arm == "none": me.set_memory(client, False)
                    session = "q-" + secrets.token_hex(4)
                    text = f"Current date: {question['question_date']}\n\n{question['question']}"
                    started = time.time()
                    problem = me.ask(client, session, text, me.MODELS[args.model], args.timeout)
                    answer, tokens, calls = me.answer_of(data, name, session)
                    passed, verdict = (False, "") if problem else grade(grader, args.judge, question, answer, usage)
                    result = {"id": question["question_id"], "ability": ability(question), "type": question["question_type"], "arm": arm,
                              "passed": passed, "verdict": verdict, "problem": problem, "question": question["question"],
                              "expected": str(question["answer"])[:300], "answer": answer[:800], "seconds": round(time.time() - started, 1),
                              "calls": calls, "tokens": dict(tokens), "cost": round(me.cost(args.model, tokens), 4)}
                    report["results"].append(result)
                    print(f"{'PASS' if passed else 'FAIL'} {arm:6} {result['ability']:16} {result['id']:22} {result['seconds']:6}s "
                          f"{(problem or answer.replace(chr(10), ' '))[:90]}", flush=True)
                    json.dump(report, open(out + ".json", "w"), ensure_ascii=False, indent=1)
            finally:
                server.send_signal(signal.SIGTERM)
                try: server.wait(timeout=30)
                except subprocess.TimeoutExpired: server.kill()
    finally:
        if args.keep: print("kept", work)
        else: shutil.rmtree(work, ignore_errors=True)
    judge_cost = (usage["input"] * JUDGE_PRICE.get(args.judge, (0, 0))[0] + usage["output"] * JUDGE_PRICE.get(args.judge, (0, 0))[1]) / 1e6
    report["judgeTokens"] = dict(usage); report["cost"] = round(sum(r["cost"] for r in report["results"]) + judge_cost, 4)
    json.dump(report, open(out + ".json", "w"), ensure_ascii=False, indent=1)
    write_table(report, out + ".md", stamp, judge_cost)
    print(open(out + ".md", encoding="utf-8").read())


def write_table(report, path, stamp, judge_cost):
    arms = report["arms"]
    score = collections.defaultdict(collections.Counter)
    for r in report["results"]:
        score[(r["ability"], r["arm"])]["n"] += 1; score[(r["ability"], r["arm"])]["ok"] += bool(r["passed"])
        score[("all", r["arm"])]["n"] += 1; score[("all", r["arm"])]["ok"] += bool(r["passed"])
    names = {"extraction": "找事实", "multi-session": "跨会话", "knowledge-update": "记住更新", "temporal": "按时间推理",
             "abstention": "不知道就说不知道", "all": "合计"}
    lines = [f"# {stamp}", "", f"LongMemEval_S（cleaned）子集 {report['questions']} 题 · 回答模型 {report['model']} · 记笔记 Claude Haiku 5.5 · "
             f"裁判 {report['judge']}（官方判题提示） · 回答和裁判费用约 ${report['cost']:.2f}（裁判 ${judge_cost:.2f}）", "",
             "| 能力 | " + " | ".join(arms) + " |", "|---|" + "---|" * len(arms)]
    for key in ABILITIES + ["all"]:
        cells = []
        for arm in arms:
            cell = score[(key, arm)]
            cells.append(f"{cell['ok']}/{cell['n']}" if cell["n"] else "-")
        lines.append(f"| {names[key]} | " + " | ".join(cells) + " |")
    lines += ["", "## 每道题", "", "| 题 | 能力 | " + " | ".join(arms) + " |", "|---|---|" + "---|" * len(arms)]
    by_question = collections.OrderedDict()
    for r in report["results"]:
        by_question.setdefault((r["id"], r["ability"]), {})[r["arm"]] = "✓" if r["passed"] else "✗"
    for (qid, kind), cells in by_question.items():
        lines.append(f"| {qid} | {names[kind]} | " + " | ".join(cells.get(arm, "-") for arm in arms) + " |")
    open(path, "w", encoding="utf-8").write("\n".join(lines) + "\n")


def main():
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest="step", required=True)
    p = sub.add_parser("pick"); p.add_argument("--data", required=True); p.add_argument("--out", required=True)
    p.add_argument("--per", type=int, default=6); p.add_argument("--seed", type=int, default=1116)
    b = sub.add_parser("build"); b.add_argument("--subset", required=True); b.add_argument("--ledgers", required=True)
    b.add_argument("--parallel", type=int, default=6); b.add_argument("--repo", default="")
    a = sub.add_parser("ask"); a.add_argument("--subset", required=True); a.add_argument("--ledgers", required=True)
    a.add_argument("--model", default="qwen", choices=sorted(me.MODELS)); a.add_argument("--arms", default="memory,none")
    a.add_argument("--judge", default="claude-sonnet-5-5"); a.add_argument("--timeout", type=int, default=300)
    a.add_argument("--only", default=""); a.add_argument("--tag", default=""); a.add_argument("--keep", action="store_true")
    a.add_argument("--port", type=int, default=8096); a.add_argument("--shell-jar", default=""); a.add_argument("--examples-jar", default="")
    args = parser.parse_args()
    if args.step == "pick": pick(args)
    elif args.step == "build": sys.exit(build(args))
    else: ask_all(args)


if __name__ == "__main__":
    main()
