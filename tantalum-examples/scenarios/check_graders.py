"""不调模型，验证长任务的判分（agent-eval 需求 4、5）：项目原样时没过，照参考答案改了就过。判分改了先跑这个：

    python3 tantalum-examples/scenarios/check_graders.py
"""
import os, re, sys, tempfile, collections
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import fixture, scenarios as sc

def ctx_for(root, calls=(), compactions=0):
    c = sc.__dict__.get("Ctx", type("Ctx", (), {}))(); c.root = root; c.answer = ""; c.calls = list(calls); c.compactions = compactions
    c.tools = collections.Counter(x["name"] for x in c.calls); return c

def edit(root, path, old, new):
    p = os.path.join(root, path); s = open(p, encoding="utf-8").read(); assert old in s, (path, old); open(p, "w", encoding="utf-8").write(s.replace(old, new, 1))

def write(root, path, text):
    os.makedirs(os.path.dirname(os.path.join(root, path)) or root, exist_ok=True); open(os.path.join(root, path), "w", encoding="utf-8").write(text)

def solve_feature(root):
    edit(root, "inventory/shipping.py", "def compute_shipping_fee", "def split_fee(weight_kg, zone, batches):\n    if batches < 1:\n        raise ValueError('batches')\n    return sum(compute_shipping_fee(weight_kg / batches, zone) for _ in range(batches))\n\n\ndef compute_shipping_fee")
    edit(root, "inventory/orders.py", "from inventory.shipping import compute_shipping_fee", "from inventory.shipping import compute_shipping_fee, split_fee")
    edit(root, "inventory/orders.py", "zone, discount_percent=0):", "zone, discount_percent=0, batches=1):")
    edit(root, "inventory/orders.py", 'shipping = compute_shipping_fee(item["weight_kg"] * quantity, zone)', 'shipping = split_fee(item["weight_kg"] * quantity, zone, batches)')
    edit(root, "inventory/reports.py", "from inventory.shipping import compute_shipping_fee", "from inventory.shipping import split_fee")
    edit(root, "inventory/reports.py", "def shipping_table(weights, zone):", "def shipping_table(weights, zone, batches=1):")
    edit(root, "inventory/reports.py", "compute_shipping_fee(weight, zone)", "split_fee(weight, zone, batches)")
    write(root, "tests/test_split.py", "import unittest\nfrom inventory.shipping import split_fee\nclass T(unittest.TestCase):\n    def test_one(self):\n        self.assertEqual(split_fee(2, 'local', 1), 7)\n")

def solve_debug(root):
    edit(root, "inventory/cli.py", "sku, quantity, zone = argv[1], argv[2], argv[3]", "sku, quantity, zone = argv[1], int(argv[2]), argv[3]")
    write(root, "tests/test_cli.py", "import unittest\nfrom inventory.cli import main\nclass T(unittest.TestCase):\n    def test_order(self):\n        self.assertEqual(main(['order', 'SKU-0007', '2', 'local']), 0)\n")

def solve_split(root):
    stock = open(os.path.join(root, "inventory/stock.py"), encoding="utf-8").read()
    head, reserve = stock.split("def reserve_stock", 1)
    write(root, "inventory/stock.py", head.rstrip() + "\n")
    write(root, "inventory/reservations.py", '"""Reservations."""\nfrom inventory.stock import _levels\n\n\ndef reserve_stock' + reserve)
    edit(root, "inventory/orders.py", "from inventory.stock import reserve_stock", "from inventory.reservations import reserve_stock")
    edit(root, "tests/test_stock.py", "from inventory.stock import reserve_stock, restock", "from inventory.reservations import reserve_stock\nfrom inventory.stock import restock")

def solve_checklist(root):
    edit(root, "inventory/shipping.py", '"overseas": 3.5}', '"overseas": 3.5, "remote": 4.0}\nHEAVY_LIMIT_KG = 30')
    edit(root, "inventory/shipping.py", '    if zone not in ZONE_RATES:', '    if weight_kg > HEAVY_LIMIT_KG:\n        raise ValueError("too heavy")\n    if zone not in ZONE_RATES:')
    edit(root, "README.md", "# inventory\n", "# inventory\n\nZones: local, national, overseas, remote.\n")
    open(os.path.join(root, "docs/api.md"), "a", encoding="utf-8").write("\n## Zones\n\n- local\n- national\n- overseas\n- remote\n")
    edit(root, "inventory/cli.py", 'print("usage: fee <weight_kg> <zone>")', 'print("usage: fee <weight_kg> <zone> (zones: local, national, overseas, remote)")')
    edit(root, "tests/test_shipping.py", "    def test_unknown_zone", "    def test_remote(self):\n        self.assertEqual(compute_shipping_fee(2, 'remote'), 13)\n\n    def test_too_heavy(self):\n        with self.assertRaises(ValueError):\n            compute_shipping_fee(31, 'local')\n\n    def test_unknown_zone")
    write(root, "CHANGELOG.md", "## 0.3.0\n\n- New remote zone.\n")

TODOS = [{"name": "todo_write", "error": False, "args": {"todos": [{"content": f"item {i}", "status": "completed"} for i in range(1, 9)]}}]
root = os.path.join(tempfile.mkdtemp(), "inventory"); fixture.build(root)
cases = [("feature-tests", solve_feature, (), 0), ("debug-log", solve_debug, (), 0), ("split-module", solve_split, (), 0),
         ("todo-steps", solve_checklist, TODOS, 0), ("todo-compact", solve_checklist, TODOS, 1)]
by_id = {s["id"]: s for s in sc.SCENARIOS}
failures = 0
for sid, solve, calls, compactions in cases:
    scenario = by_id[sid]
    fixture.reset(root); scenario["setup"](root)
    before = scenario["check"](ctx_for(root, (), 0))
    fixture.reset(root); scenario["setup"](root); solve(root)
    after = scenario["check"](ctx_for(root, calls, compactions))
    good = bool(before) and not after
    failures += 0 if good else 1
    print(f"{'ok ' if good else 'BAD'} {sid:14} untouched fails ({len(before)} reasons, e.g. {before[:1]}); solved: {'passes' if not after else after}")
sys.exit(1 if failures else 0)
