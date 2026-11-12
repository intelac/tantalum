"""场景清单。check(ctx) 返回没过的标准（空表就是过了）。只查事实：文件、命令结果、回答里藏着的编号。"""
import os, re, subprocess
from fixture import FACTS

SITE = "http://127.0.0.1:8766"

def read(ctx, path):
    try:
        return open(os.path.join(ctx.root, path), encoding="utf-8").read()
    except (OSError, UnicodeDecodeError) as failure:
        return f"<{failure}>"

def tests(ctx):
    out = subprocess.run(["python3", "-m", "unittest", "discover", "-s", "tests", "-q"], cwd=ctx.root, capture_output=True, text=True)
    return out.returncode == 0, out.stderr.strip().splitlines()[-1] if out.stderr.strip() else ""

def says(ctx, *needles):
    return [f"回答里没有 {n!r}" for n in needles if n.lower() not in ctx.answer.lower()]

def used(ctx, *names):
    return [] if any(ctx.tools.get(n, 0) > 0 for n in names) else [f"没有用到 {' / '.join(names)}"]

def S(id, group, risk, prompt, check, max_calls=12, answers=None, setup=None, timeout=240, followup=None, server=None, world=True):
    """server：要单独起一台服务器的配置名（见 run.py 的 SERVERS）；world=False 的场景只能查回答（在表里标出来）。"""
    return dict(id=id, group=group, risk=risk, prompt=prompt, check=check, max_calls=max_calls, answers=answers or {}, setup=setup,
                timeout=timeout, followup=followup, server=server, world=world)

# ---- 找和读 ----
def c_find_def(ctx):
    return says(ctx, "shipping.py", "orders.py", "reports.py", "cli.py")

def c_big_file(ctx):
    return says(ctx, FACTS["warehouse"])

def c_tree(ctx):
    bad = says(ctx, "inventory", "tests", "docs")
    listed = sum(1 for call in ctx.calls if call["name"] == "list_dir" and "node_modules" in str(call["args"]))
    return bad + (["把 node_modules 逐层列了 3 次以上"] if listed >= 3 else [])

def c_odd_files(ctx):
    return says(ctx, FACTS["meeting"], FACTS["gbk"])

def c_binary(ctx):
    bad = []
    if not re.search(r"png|image|图片|图像", ctx.answer, re.I): bad.append("没说 logo 是图片")
    repeats = sum(1 for call in ctx.calls if call["name"] == "read_file" and "logo.png" in str(call["args"]))
    if repeats > 2: bad.append(f"读 logo.png 重试了 {repeats} 次")
    return bad

def c_pdf(ctx):
    return says(ctx, FACTS["pdf"])

# ---- 改 ----
def c_fix_bug(ctx):
    ok, last = tests(ctx)
    bad = [] if ok else [f"测试还没过：{last}"]
    if "apply_discount(200, 25), 150" not in read(ctx, "tests/test_pricing.py"): bad.append("改了测试而不是代码")
    return bad

def c_rename(ctx):
    bad = []
    for path in ["inventory/shipping.py", "inventory/orders.py", "inventory/reports.py", "inventory/cli.py", "tests/test_shipping.py", "docs/api.md"]:
        text = read(ctx, path)
        if "compute_shipping_fee" in text: bad.append(f"{path} 里还有旧名字")
        if "shipping_cost" not in text: bad.append(f"{path} 里没有新名字")
    out = subprocess.run(["python3", "-m", "unittest", "tests.test_shipping", "-q"], cwd=ctx.root, capture_output=True, text=True)
    if out.returncode != 0: bad.append("test_shipping 不过了")
    return bad

def c_new_file(ctx):
    bad = []
    text = read(ctx, "docs/changelog.md")
    if "<" in text[:1] or "0.2.0" not in text: bad.append("docs/changelog.md 没建好或没有 0.2.0")
    index = read(ctx, "docs/index.md")
    if "changelog.md" not in index: bad.append("docs/index.md 没登记")
    if "api.md" not in index or "spec.pdf" not in index: bad.append("docs/index.md 原来的条目丢了")
    return bad

def c_edit_unread(ctx):
    text = read(ctx, "inventory/shipping.py")
    return [] if "BASE_FEE = 6" in text and "EXPRESS_SURCHARGE = 12" in text else ["BASE_FEE 没改成 6，或者别的行被动了"]

def setup_twice(root):
    path = os.path.join(root, "docs/api.md")
    open(path, "a", encoding="utf-8").write("\n## Status\n\nstatus: draft\n\n## Review\n\nstatus: draft\n")

def c_edit_twice(ctx):
    text = read(ctx, "docs/api.md")
    review = text.split("## Review")[-1] if "## Review" in text else ""
    status = text.split("## Status")[-1].split("## Review")[0] if "## Status" in text else ""
    bad = []
    if "status: approved" not in review: bad.append("Review 一节没改成 approved")
    if "status: draft" not in status: bad.append("Status 一节被误改了")
    return bad

def setup_stale(root):
    open(os.path.join(root, "scripts/bump.py"), "w", encoding="utf-8").write(
        'import pathlib\np = pathlib.Path("inventory/shipping.py")\np.write_text(p.read_text().replace("BASE_FEE = 5", "BASE_FEE = 7"))\nprint("bumped")\n')

def c_edit_stale(ctx):
    text = read(ctx, "inventory/shipping.py")
    bad = []
    if "BASE_FEE = 7" not in text: bad.append("脚本改的 BASE_FEE = 7 丢了（被旧内容盖掉）")
    if "EXPRESS_SURCHARGE = 15" not in text: bad.append("EXPRESS_SURCHARGE 没改成 15")
    return bad

# ---- 命令行 ----
def c_run_tests(ctx):
    return used(ctx, "bash") + says(ctx, "test_discount_takes_percent_off", "test_no_discount_keeps_the_price")

def c_noisy(ctx):
    return used(ctx, "bash") + says(ctx, "61845")

def c_slow(ctx):
    bad = used(ctx, "bash")
    if not re.search(r"超时|timed? ?out|timeout|没有(跑|执行)完|未完成|did not finish|too long|太久", ctx.answer, re.I): bad.append("没告诉用户命令超时或没跑完")
    return bad

def c_stdin(ctx):
    bad = used(ctx, "bash")
    if ctx.seconds > 200: bad.append(f"卡了 {int(ctx.seconds)} 秒")
    return bad

def c_network(ctx):
    # 沙箱默认允许联网：成功就该报出 200；真失败了要说明是网络的原因
    bad = used(ctx, "bash")
    if "200" not in ctx.answer and not re.search(r"网络|联网|network|DNS|无法(访问|连接)|连不上|resolve|connect", ctx.answer, re.I): bad.append("既没报出 200，也没说明网络的原因")
    return bad

# ---- Git ----
def c_git_history(ctx):
    return used(ctx, "git_log", "git_show", "git_diff", "bash") + says(ctx, "3.5", "12") + ([] if re.search(r"base fee|BASE_FEE|基础|注释|comment", ctx.answer, re.I) else ["没提到第三次改动（给 BASE_FEE 加注释）"])

def c_git_blame(ctx):
    return says(ctx, FACTS["blame_author"])

def c_git_dirty(ctx):
    return says(ctx, "reports.py") + ([] if re.search(r"todo\.txt|notes", ctx.answer) else ["没提到没跟踪的 notes/todo.txt"])

# ---- 联网 ----
def c_web(ctx):
    return used(ctx, "web_fetch", "browser_navigate") + says(ctx, FACTS["web"])

def c_web_404(ctx):
    bad = used(ctx, "web_fetch", "browser_navigate")
    if not re.search(r"404|不存在|找不到|not found", ctx.answer, re.I): bad.append("没说这一页不存在")
    fetches = sum(1 for call in ctx.calls if call["name"] in ("web_fetch", "browser_navigate"))
    if fetches > 3: bad.append(f"重试了 {fetches} 次")
    return bad

def c_web_redirect(ctx):
    return used(ctx, "web_fetch", "browser_navigate") + says(ctx, FACTS["web"])

def c_web_huge(ctx):
    return used(ctx, "web_fetch", "browser_navigate") + says(ctx, FACTS["web_tail"])

# ---- 浏览器 ----
def c_form(ctx):
    return used(ctx, "browser_navigate") + says(ctx, "CONF-ZHAO-5")

def c_wait(ctx):
    return used(ctx, "browser_navigate") + says(ctx, FACTS["slow"])

# ---- 技能、提问、记忆 ----
def c_skill(ctx):
    return used(ctx, "skill") + ([] if ctx.answer.lstrip().startswith(FACTS["skill"]) or FACTS["skill"] in ctx.answer else [f"没按技能的格式写（缺 {FACTS['skill']}）"])

def c_wiki(ctx):
    return says(ctx, FACTS["wiki"])

def c_ask(ctx):
    bad = used(ctx, "ask_user")
    if os.path.exists(os.path.join(ctx.root, "notes/todo.txt")): bad.append("用户选了删除，文件还在")
    if not os.path.exists(os.path.join(ctx.root, "README.md")): bad.append("删多了")
    return bad

def c_memory(ctx):
    return says(ctx, "tabs") if "tab" in ctx.answer.lower() else ["新会话里没用上记住的偏好（缩进用 tab）"]

# ---- 并行、长任务 ----
def c_parallel(ctx):
    bad = says(ctx, "apply_discount", "compute_shipping_fee", "reserve_stock", "place_order", "shipping_table")
    reads = ctx.tools.get("read_file", 0)
    if reads < 5: bad.append(f"只读了 {reads} 个文件")
    return bad

def c_feature(ctx):
    ok, last = tests(ctx)
    bad = [] if ok else [f"测试不过：{last}"]
    if "def bulk_discount" not in read(ctx, "inventory/pricing.py"): bad.append("pricing.py 里没有 bulk_discount")
    found = any("bulk_discount" in read(ctx, os.path.join("tests", name)) for name in os.listdir(os.path.join(ctx.root, "tests")) if name.endswith(".py"))
    if not found: bad.append("没有给 bulk_discount 写测试")
    out = subprocess.run(["python3", "-c", "from inventory.pricing import bulk_discount as b; assert b(100, 9) == 100 and b(100, 10) == 95 and b(100, 50) == 90, (b(100,9), b(100,10), b(100,50))"],
                         cwd=ctx.root, capture_output=True, text=True)
    if out.returncode != 0: bad.append("bulk_discount 的结果不对：" + out.stderr.strip().splitlines()[-1][:120] if out.stderr.strip() else "bulk_discount 的结果不对")
    return bad

# ---- 长任务（agent-eval 需求 4、5）：判分全看世界——隐藏测试在运行结束后才放进项目，模型看不到 ----
import hashlib, textwrap

FIXED_PRICING = '''"""Prices and discounts."""


def unit_total(quantity, unit_price):
    """Price of a line before any discount."""
    return quantity * unit_price


def apply_discount(price, percent):
    """Return the price after taking percent off."""
    if percent < 0 or percent > 100:
        raise ValueError("percent must be between 0 and 100")
    return price * (100 - percent) / 100
'''
_ORIGINAL_TESTS = {}

def _fix_pricing(root):
    """长任务从“现有测试全过”开始，不和 fix-bug 场景混在一起。"""
    with open(os.path.join(root, "inventory/pricing.py"), "w", encoding="utf-8") as f: f.write(FIXED_PRICING)

def _remember_tests(root):
    _ORIGINAL_TESTS[root] = {name: hashlib.sha256(open(os.path.join(root, "tests", name), "rb").read()).hexdigest()
                             for name in os.listdir(os.path.join(root, "tests")) if name.endswith(".py")}

def _tests_untouched(ctx):
    bad = []
    for name, digest in _ORIGINAL_TESTS.get(ctx.root, {}).items():
        path = os.path.join(ctx.root, "tests", name)
        if not os.path.exists(path): bad.append(f"删了原有的测试 {name}")
        elif hashlib.sha256(open(path, "rb").read()).hexdigest() != digest: bad.append(f"改了原有的测试 {name}")
    return bad

def _new_tests(ctx):
    return [n for n in os.listdir(os.path.join(ctx.root, "tests")) if n.startswith("test_") and n.endswith(".py")
            and n not in _ORIGINAL_TESTS.get(ctx.root, {})]

def _hidden(ctx, name, body):
    """把隐藏测试放进 tests/ 跑全部测试，跑完拿走。返回 (过没过, 最后一行)。"""
    path = os.path.join(ctx.root, "tests", name)
    with open(path, "w", encoding="utf-8") as f: f.write(textwrap.dedent(body).lstrip())
    try: return tests(ctx)
    finally: os.remove(path)

def setup_long(root):
    _fix_pricing(root); _remember_tests(root)

def c_feature_tests(ctx):
    bad = _tests_untouched(ctx)
    if not _new_tests(ctx): bad.append("没有新写测试文件")
    ok, last = _hidden(ctx, "test_hidden_split.py", """
        import unittest
        from inventory.shipping import compute_shipping_fee, split_fee
        from inventory.orders import place_order
        from inventory.reports import shipping_table
        from inventory.stock import restock

        class HiddenSplit(unittest.TestCase):
            def test_split_fee_sums_each_batch(self):
                self.assertAlmostEqual(split_fee(6, "national", 3), 3 * compute_shipping_fee(2, "national"))
                self.assertAlmostEqual(split_fee(6, "national", 1), compute_shipping_fee(6, "national"))
            def test_bad_batches(self):
                with self.assertRaises(ValueError): split_fee(6, "local", 0)
            def test_order_with_batches(self):
                restock("SKU-0002", 50)
                one = place_order("SKU-0002", 4, 10, "local")
                restock("SKU-0002", 50)
                two = place_order("SKU-0002", 4, 10, "local", batches=2)
                self.assertGreater(two["shipping"], one["shipping"])
                self.assertAlmostEqual(two["total"], two["goods"] + two["shipping"])
            def test_report_with_batches(self):
                self.assertEqual(shipping_table([4.0], "local"), shipping_table([4.0], "local", batches=1))
                self.assertIn("14.00", shipping_table([4.0], "local", batches=2))
    """)
    if not ok: bad.append(f"隐藏测试或原有测试没过：{last}")
    return bad

BUGGY_CLI = '''"""Command line entry.

    python3 -m inventory.cli fee <weight> <zone>
    python3 -m inventory.cli order <sku> <quantity> <zone>
"""
import sys

from inventory.orders import place_order
from inventory.shipping import compute_shipping_fee
from inventory.stock import restock


def main(argv):
    if len(argv) == 3 and argv[0] == "fee":
        print(compute_shipping_fee(float(argv[1]), argv[2]))
        return 0
    if len(argv) == 4 and argv[0] == "order":
        sku, quantity, zone = argv[1], argv[2], argv[3]
        restock(sku, 100)
        print(place_order(sku, quantity, 9.5, zone)["total"])
        return 0
    print("usage: fee <weight_kg> <zone> | order <sku> <quantity> <zone>")
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
'''
APP_LOG = """2026-11-12 09:14:02 INFO  starting inventory cli
2026-11-12 09:14:02 INFO  args: order SKU-0003 3 national
2026-11-12 09:14:02 ERROR unhandled exception
Traceback (most recent call last):
  File "inventory/cli.py", line 20, in main
    print(place_order(sku, quantity, 9.5, zone)["total"])
  File "inventory/orders.py", line 10, in place_order
    reserve_stock(sku, quantity)
  File "inventory/stock.py", line 17, in reserve_stock
    if quantity > available:
TypeError: '>' not supported between instances of 'str' and 'int'
2026-11-12 09:14:03 INFO  exit 1
"""

def setup_debug(root):
    _fix_pricing(root)
    with open(os.path.join(root, "inventory/cli.py"), "w", encoding="utf-8") as f: f.write(BUGGY_CLI)
    os.makedirs(os.path.join(root, "logs"), exist_ok=True)
    with open(os.path.join(root, "logs/app.log"), "w", encoding="utf-8") as f: f.write(APP_LOG)
    _remember_tests(root)

def c_debug_log(ctx):
    bad = _tests_untouched(ctx)
    out = subprocess.run(["python3", "-m", "inventory.cli", "order", "SKU-0003", "3", "national"], cwd=ctx.root, capture_output=True, text=True)
    if out.returncode != 0: bad.append("命令还是报错：" + (out.stderr.strip().splitlines() or [""])[-1][:120])
    ok, last = _hidden(ctx, "test_hidden_cli.py", """
        import io, unittest
        from contextlib import redirect_stdout
        from inventory.cli import main

        class HiddenCli(unittest.TestCase):
            def test_order_from_the_command_line(self):
                out = io.StringIO()
                with redirect_stdout(out):
                    self.assertEqual(main(["order", "SKU-0004", "2", "local"]), 0)
                self.assertGreater(float(out.getvalue().strip()), 0)
    """)
    if not ok: bad.append(f"隐藏测试或原有测试没过：{last}")
    if not _new_tests(ctx): bad.append("没有加回归测试")
    return bad

def c_split_module(ctx):
    bad = []
    stock, reservations = read(ctx, "inventory/stock.py"), read(ctx, "inventory/reservations.py")
    if "def reserve_stock" not in reservations: bad.append("reservations.py 里没有 reserve_stock")
    if "reserve_stock" in stock: bad.append("stock.py 里还有 reserve_stock（不要留转发）")
    if "def restock" not in stock: bad.append("stock.py 里的 restock 不见了")
    for folder in ("inventory", "tests"):
        for name in os.listdir(os.path.join(ctx.root, folder)):
            if name.endswith(".py") and re.search(r"from inventory\.stock import[^\n]*reserve_stock", read(ctx, os.path.join(folder, name))):
                bad.append(f"{folder}/{name} 还从 stock 导入 reserve_stock")
    ok, last = _hidden(ctx, "test_hidden_split_module.py", """
        import unittest
        from inventory.reservations import reserve_stock
        from inventory.stock import restock
        from inventory.orders import place_order

        class HiddenSplitModule(unittest.TestCase):
            def test_levels_are_shared(self):
                restock("SKU-0005", 4)
                self.assertEqual(reserve_stock("SKU-0005", 3), 1)
                with self.assertRaises(ValueError): reserve_stock("SKU-0005", 2)
            def test_orders_still_work(self):
                restock("SKU-0006", 10)
                self.assertIn("total", place_order("SKU-0006", 1, 5, "local"))
    """)
    if not ok: bad.append(f"隐藏测试或原有测试没过：{last}")
    return bad

CHECKLIST = """# Release checklist 0.3.0

Do every item, in order.

1. In `inventory/shipping.py`, add the zone `"remote"` with rate `4.0` to `ZONE_RATES`.
2. In `inventory/shipping.py`, add a constant `HEAVY_LIMIT_KG = 30`.
3. `compute_shipping_fee` must raise `ValueError("too heavy")` when `weight_kg` is more than `HEAVY_LIMIT_KG`.
4. In `README.md`, add the line `Zones: local, national, overseas, remote.`
5. In `docs/api.md`, add a section `## Zones` that lists the four zones.
6. In `inventory/cli.py`, change the usage message to end with `(zones: local, national, overseas, remote)`.
7. In `tests/test_shipping.py`, add `test_remote` (2 kg remote costs 13) and `test_too_heavy` (31 kg raises ValueError).
8. Create `CHANGELOG.md` with a heading `## 0.3.0` and one line about the remote zone.

Finish by running all tests; they must pass.
"""

def setup_checklist(root):
    _fix_pricing(root)
    with open(os.path.join(root, "docs/checklist.md"), "w", encoding="utf-8") as f: f.write(CHECKLIST)

PROBE_CHECKLIST = """from inventory import shipping as s
assert s.ZONE_RATES.get('remote') == 4.0, 'item 1'
assert getattr(s, 'HEAVY_LIMIT_KG', None) == 30, 'item 2'
assert s.compute_shipping_fee(2, 'remote') == 13, 'remote fee'
assert s.compute_shipping_fee(30, 'local') == 35, 'item 3: 30 kg is still fine'
try:
    s.compute_shipping_fee(31, 'local')
    raise SystemExit('item 3: 31 kg did not raise')
except ValueError:
    pass
"""

def _checklist(ctx):
    bad = []
    probe = subprocess.run(["python3", "-c", PROBE_CHECKLIST], cwd=ctx.root, capture_output=True, text=True)
    if probe.returncode != 0: bad.append("shipping.py：" + ((probe.stderr.strip().splitlines() or [probe.stdout.strip()])[-1])[:120])
    if "Zones: local, national, overseas, remote." not in read(ctx, "README.md"): bad.append("第 4 项 README")
    api = read(ctx, "docs/api.md")
    if "## Zones" not in api or not all(z in api.split("## Zones", 1)[-1] for z in ("local", "national", "overseas", "remote")): bad.append("第 5 项 docs/api.md")
    if "(zones: local, national, overseas, remote)" not in read(ctx, "inventory/cli.py"): bad.append("第 6 项 cli.py")
    tests_text = read(ctx, "tests/test_shipping.py")
    if "def test_remote" not in tests_text or "def test_too_heavy" not in tests_text: bad.append("第 7 项 测试")
    changelog = read(ctx, "CHANGELOG.md")
    if "## 0.3.0" not in changelog or "remote" not in changelog.lower(): bad.append("第 8 项 CHANGELOG.md")
    ok, last = tests(ctx)
    if not ok: bad.append(f"测试没过：{last}")
    return bad

def _todos(ctx):
    """模型用过 todo_write，最后一张清单全部完成。"""
    writes = [c for c in ctx.calls if c["name"] == "todo_write" and not c["error"]]
    if not writes: return ["没有用 todo_write 跟踪进度"]
    args = writes[-1]["args"] if isinstance(writes[-1]["args"], dict) else {}
    last = args.get("todos") or []
    if len(last) < 4: return [f"最后一张清单只有 {len(last)} 项"]
    open_items = [t.get("content") for t in last if t.get("status") != "completed"]
    return [f"最后一张清单还有没完成的：{open_items[:3]}"] if open_items else []

def c_todo_steps(ctx):
    return _checklist(ctx) + _todos(ctx)

def c_todo_compact(ctx):
    bad = _checklist(ctx) + _todos(ctx)
    if ctx.compactions < 1: bad.append("运行中没有压缩（压缩点没起作用）")
    return bad

LONG_PROMPT_CHECKLIST = "这是一个多步任务：按 docs/checklist.md 逐项完成里面的 8 项改动，用待办清单跟踪进度，最后跑全部测试确认通过。改文件用 edit_file 或 write_file，一项一项改，不要用命令行或脚本改文件。"

SCENARIOS = [
    S("find-def", "找和读", "grep / find_files 的参数会不会用", "compute_shipping_fee 这个函数定义在哪个文件？项目里还有哪些文件调用了它？列出文件名。", c_find_def),
    S("big-file", "找和读", "三千行文件的窗口读", "inventory/data/catalog.py 里 SKU-2187 的 warehouse_code 是什么？", c_big_file),
    S("tree", "找和读", "list_dir 会不会被 node_modules 淹没", "简单说明这个项目的目录结构，每个顶层目录是干什么的。", c_tree),
    S("odd-files", "找和读", "带空格和中文的文件名、GBK 编码", "docs 目录里有一份会议记录和一份 legacy 说明。会议决定的下线日期是哪天？legacy 说明里的库存编号规则是什么？", c_odd_files),
    S("binary", "找和读", "读图片时的结果模型看不看得懂", "assets/logo.png 是什么文件？大概说一下。", c_binary, max_calls=6),
    S("pdf", "找和读", "读 PDF", "docs/spec.pdf 里写的 Spec revision 编号是多少？", c_pdf, max_calls=8),
    S("fix-bug", "改", "读、改、跑的整条链", "这个项目的测试有失败的。找出原因并修好代码（不要改测试），最后跑一遍测试确认全部通过。", c_fix_bug, max_calls=16, timeout=360),
    S("rename", "改", "多文件改名会不会漏", "把函数 compute_shipping_fee 改名为 shipping_cost，所有用到它的地方（代码、测试、文档）都要改。", c_rename, max_calls=24, timeout=420),
    S("new-file", "改", "write_file 和 edit_file 的分工", "新建 docs/changelog.md，写一条 0.2.0 版本的记录（内容随意），并在 docs/index.md 的列表里加上它的链接。", c_new_file),
    S("edit-unread", "改", "没先读就改的报错能不能照着改正", "不用看别的，直接把 inventory/shipping.py 里的 BASE_FEE 从 5 改成 6。", c_edit_unread, max_calls=8),
    S("edit-twice", "改", "要改的文字出现两次", "docs/api.md 最后有 Status 和 Review 两节，都写着 status: draft。只把 Review 那一节的改成 status: approved，Status 那一节不要动。", c_edit_twice, setup=setup_twice),
    S("edit-stale", "改", "读了之后文件被别人改过", "先读 inventory/shipping.py。然后运行 python3 scripts/bump.py（它会改这个文件）。再把 EXPRESS_SURCHARGE 改成 15。脚本做的改动要保留。", c_edit_stale, setup=setup_stale, max_calls=14),
    S("run-tests", "命令行", "失败输出会不会被截掉关键行", "跑一下测试，告诉我是哪几个测试方法失败了（写出方法名）。不要修。", c_run_tests),
    S("noisy", "命令行", "几万行输出被截断后模型怎么办", "运行 python3 scripts/noisy.py，它的输出里有一行以 MARKER-ANSWER= 开头，等号后面的数字是多少？", c_noisy),
    S("slow", "命令行", "超时的报错", "运行 python3 scripts/slow.py，超时设成 5 秒，告诉我结果。", c_slow, max_calls=6, timeout=200),
    S("stdin", "命令行", "等输入的命令会不会卡死", "运行 python3 scripts/ask.py，告诉我它输出了什么。", c_stdin, max_calls=8, timeout=260),
    S("network", "命令行", "命令联网（沙箱默认允许）", "运行 python3 scripts/fetch.py，告诉我它打印了什么；如果失败了，说明原因。", c_network, max_calls=10),
    S("git-history", "Git", "git_log / git_show / git_diff", "inventory/shipping.py 最近三次提交分别改了什么？", c_git_history, max_calls=14),
    S("git-blame", "Git", "git_blame", "inventory/shipping.py 里 EXPRESS_SURCHARGE 那一行最后是谁改的？", c_git_blame, max_calls=8),
    S("git-dirty", "Git", "没提交的改动", "现在工作区里有哪些还没提交的改动？包括没被 Git 跟踪的文件。", c_git_dirty, max_calls=10),
    S("web", "联网", "抓网页回答问题", f"打开 {SITE}/docs/rate-limits.html，burst limit 是多少？", c_web, max_calls=6),
    S("web-404", "联网", "不存在的页", f"{SITE}/docs/pricing.html 这一页讲了什么？", c_web_404, max_calls=8),
    S("web-redirect", "联网", "跳转", f"打开 {SITE}/old，页面里说 burst limit 是多少？", c_web_redirect, max_calls=6),
    S("web-huge", "联网", "很大的页被截断", f"{SITE}/huge 这一页最后的 Final note 写的是什么？", c_web_huge, max_calls=12),
    S("form", "浏览器", "元素编号、填表提交", f"用浏览器打开 {SITE}/form，Name 填 zhao，Quantity 选 5，提交，告诉我确认页上的 Confirmation 编号。", c_form, max_calls=14, timeout=300),
    S("wait", "浏览器", "等动态内容", f"用浏览器打开 {SITE}/slow，页面加载完成后显示的 token 是什么？", c_wait, max_calls=10, timeout=300),
    S("skill", "技能", "技能清单会不会用", "给这个项目写一份 0.2.0 的 release notes：新增了批量折扣，修了折扣计算的 bug。直接回复在对话里，不要写文件。", c_skill, max_calls=8),
    S("wiki", "技能", "知识库会不会用", "我们回滚一次发布要用哪个开关？查一下知识库。", c_wiki, max_calls=8),
    S("ask", "提问和记忆", "ask_user 的选项格式", "notes/todo.txt 这个文件我不确定还要不要。你先问我是删除还是保留，再照我说的做。", c_ask, answers={"*": "删除"}, max_calls=10),
    S("memory", "提问和记忆", "记忆工具：新会话里用上", "记住：我的代码缩进一律用 tab，不用空格。以后都按这个来。", c_memory, max_calls=8,
      followup="（新会话）我写 Python 时缩进该用什么？按我之前告诉你的偏好回答，用英文单词 tabs 或 spaces 回答。"),
    S("parallel", "并行", "一次读多个文件", "同时读 inventory 下的 pricing.py、shipping.py、stock.py、orders.py、reports.py 这五个文件，各用一句话说它里面的主要函数（写出函数名）。", c_parallel, max_calls=8),
    S("feature", "长任务", "多步之间不丢上下文", "先修好现有失败的测试（改代码不改测试）。然后在 inventory/pricing.py 里加一个函数 bulk_discount(price, quantity)：数量不到 10 件不打折，10 到 49 件打 95 折，50 件及以上打 9 折。给它写测试放在 tests 目录里，最后跑全部测试确认通过。", c_feature, max_calls=30, timeout=600),
    S("feature-tests", "长任务", "跨三个文件加功能并写测试；隐藏测试判分", "给订单加“分批发货”：inventory/shipping.py 加函数 split_fee(weight_kg, zone, batches)，把重量平均分成 batches 批、每批单独算运费再相加（batches 小于 1 时抛 ValueError）；inventory/orders.py 的 place_order 加可选参数 batches（默认 1），运费用 split_fee 算；inventory/reports.py 的 shipping_table 也加可选参数 batches（默认 1）。给这些改动写测试（新建测试文件，不要改原有的测试），最后跑全部测试确认通过。", c_feature_tests, setup=setup_long, max_calls=40, timeout=900),
    S("debug-log", "长任务", "先读日志、再定位、再修，加回归测试", "logs/app.log 里记了一次命令行下单的报错。找出原因并修好代码，再加一个能抓住这个问题的回归测试（新建测试文件），最后跑全部测试确认通过。", c_debug_log, setup=setup_debug, max_calls=30, timeout=600),
    S("split-module", "长任务", "拆模块，所有引用都改对", "把 inventory/stock.py 里的 reserve_stock 拆到新文件 inventory/reservations.py；stock.py 只保留库存数量本身（_levels 和 restock），不要在 stock.py 里留转发或重新导入。所有用到 reserve_stock 的地方（代码和测试）都改成从新模块导入，最后跑全部测试确认通过。", c_split_module, setup=setup_long, max_calls=30, timeout=600),
    S("todo-steps", "长任务", "八步清单：会不会用待办、会不会漏步", LONG_PROMPT_CHECKLIST, c_todo_steps, setup=setup_checklist, max_calls=45, timeout=900),
    S("todo-compact", "长任务", "同上，运行中途被压缩后能不能接着做完", LONG_PROMPT_CHECKLIST, c_todo_compact, setup=setup_checklist, max_calls=50, timeout=1200, server="small-window"),
]

# 问答类场景只能查回答：答案是藏在项目、网页、知识库里的编号，猜不出来，但终究是看模型说了什么（表里标“回答”）
ANSWER_ONLY = {"find-def", "big-file", "tree", "odd-files", "binary", "pdf", "run-tests", "noisy", "slow", "stdin", "network", "git-history",
               "git-blame", "git-dirty", "web", "web-404", "web-redirect", "web-huge", "form", "wait", "skill", "wiki", "memory", "parallel"}
for _scenario in SCENARIOS:
    _scenario["world"] = _scenario["id"] not in ANSWER_ONLY
