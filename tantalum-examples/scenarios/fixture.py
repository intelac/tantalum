"""场景用的小项目和测试站点。每次从固定内容生成，所以每个场景的起点一样。"""
import os, shutil, subprocess, threading, textwrap, zlib, struct, time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

# 只有真用了工具才知道的事实
FACTS = {
    "warehouse": "WH-7Q4-ZEBRA",        # data/catalog.py 第 2187 项
    "pdf": "R-4471",                     # docs/spec.pdf
    "gbk": "GBK-5521",                   # docs/legacy-gbk.txt
    "meeting": "2027-03-15",             # docs/会议 记录.md
    "noisy": "MARKER-ANSWER=61845",      # scripts/noisy.py 的中间一行
    "blame_author": "Mina Park",         # EXPRESS_SURCHARGE 那一行
    "web": "37",                         # /docs/rate-limits.html
    "web_tail": "TAIL-FACT-8812",        # /huge 的最后
    "slow": "LT-90210",                  # /slow 两秒后出现
    "skill": "RELEASE-FMT-9",            # skills/release-notes
    "wiki": "blue-green-42",             # wiki/ops/rollback.md
}

def write(root, path, text, encoding="utf-8"):
    full = os.path.join(root, path)
    os.makedirs(os.path.dirname(full), exist_ok=True)
    with open(full, "w", encoding=encoding, newline="\n") as f:
        f.write(textwrap.dedent(text).lstrip("\n"))

def git(root, *args, author=None, date=None):
    env = dict(os.environ, GIT_CONFIG_GLOBAL="/dev/null", GIT_CONFIG_SYSTEM="/dev/null")
    name = author or "Dev One"
    when = date or "2026-10-01T10:00:00"
    env.update(GIT_AUTHOR_NAME=name, GIT_AUTHOR_EMAIL=name.lower().replace(" ", ".") + "@example.test", GIT_AUTHOR_DATE=when,
               GIT_COMMITTER_NAME=name, GIT_COMMITTER_EMAIL=name.lower().replace(" ", ".") + "@example.test", GIT_COMMITTER_DATE=when)
    subprocess.run(["git", "-c", "init.defaultBranch=main", "-c", "commit.gpgsign=false", *args], cwd=root, env=env, check=True, capture_output=True)

def png():
    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)
    raw = b"".join(b"\x00" + bytes([40, 90, 200]) * 16 for _ in range(16))
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", 16, 16, 8, 2, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b"")

def pdf(text):
    # 内容流压缩过：和真的 PDF 一样是二进制，直接当文本读不出字
    stream = zlib.compress(f"BT /F1 18 Tf 72 720 Td ({text}) Tj ET".encode())
    objs = [b"<< /Type /Catalog /Pages 2 0 R >>", b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>",
            b"<< /Length %d /Filter /FlateDecode >>\nstream\n" % len(stream) + stream + b"\nendstream", b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"]
    out = b"%PDF-1.4\n"; offsets = []
    for i, obj in enumerate(objs, 1):
        offsets.append(len(out)); out += b"%d 0 obj\n" % i + obj + b"\nendobj\n"
    xref = len(out)
    out += b"xref\n0 %d\n0000000000 65535 f \n" % (len(objs) + 1) + b"".join(b"%010d 00000 n \n" % o for o in offsets)
    out += b"trailer\n<< /Size %d /Root 1 0 R >>\nstartxref\n%d\n%%%%EOF\n" % (len(objs) + 1, xref)
    return out

def catalog():
    lines = ['"""Generated catalogue. One item per line."""', "", "ITEMS = ["]
    for i in range(1, 3001):
        code = FACTS["warehouse"] if i == 2187 else f"WH-{(i * 7) % 90 + 10}-{'ABCDEFGH'[i % 8]}"
        lines.append(f'    {{"sku": "SKU-{i:04d}", "name": "Item {i}", "weight_kg": {round(0.1 + (i % 50) * 0.3, 1)}, "warehouse_code": "{code}"}},')
    lines += ["]", "", "", "def load_item(sku):", "    for item in ITEMS:", '        if item["sku"] == sku:', "            return item",
              '    raise KeyError(sku)', ""]
    return "\n".join(lines)

def build(root):
    """在 root 生成项目。起点：tests/test_pricing.py 有一条失败；工作区有没提交的改动。"""
    if os.path.exists(root):
        shutil.rmtree(root)
    os.makedirs(root)
    write(root, "README.md", """
        # inventory

        A small stock and order library. Run the tests with:

            python3 -m unittest discover -s tests -q
        """)
    write(root, ".gitignore", "node_modules/\n__pycache__/\n")
    write(root, "inventory/__init__.py", '"""Stock, pricing, shipping and orders."""\n')
    write(root, "inventory/data/__init__.py", "")
    write(root, "inventory/data/catalog.py", catalog())
    write(root, "inventory/pricing.py", '''
        """Prices and discounts."""


        def unit_total(quantity, unit_price):
            """Price of a line before any discount."""
            return quantity * unit_price


        def apply_discount(price, percent):
            """Return the price after taking percent off."""
            if percent < 0 or percent > 100:
                raise ValueError("percent must be between 0 and 100")
            return price * percent / 100
        ''')
    write(root, "inventory/shipping.py", '''
        """Shipping fees."""

        BASE_FEE = 5
        EXPRESS_SURCHARGE = 8
        ZONE_RATES = {"local": 1.0, "national": 1.5, "overseas": 3.0}


        def compute_shipping_fee(weight_kg, zone, express=False):
            """Fee for one parcel."""
            if zone not in ZONE_RATES:
                raise ValueError("unknown zone: " + zone)
            fee = BASE_FEE + weight_kg * ZONE_RATES[zone]
            return fee + EXPRESS_SURCHARGE if express else fee
        ''')
    write(root, "inventory/stock.py", '''
        """Stock levels kept in memory."""
        from inventory.data.catalog import load_item

        _levels = {}


        def restock(sku, quantity):
            load_item(sku)
            _levels[sku] = _levels.get(sku, 0) + quantity
            return _levels[sku]


        def reserve_stock(sku, quantity):
            """Take quantity out of stock; raises when there is not enough."""
            available = _levels.get(sku, 0)
            if quantity > available:
                raise ValueError("not enough stock for " + sku)
            _levels[sku] = available - quantity
            return _levels[sku]
        ''')
    write(root, "inventory/orders.py", '''
        """Placing an order."""
        from inventory.data.catalog import load_item
        from inventory.pricing import apply_discount, unit_total
        from inventory.shipping import compute_shipping_fee
        from inventory.stock import reserve_stock


        def place_order(sku, quantity, unit_price, zone, discount_percent=0):
            item = load_item(sku)
            reserve_stock(sku, quantity)
            goods = apply_discount(unit_total(quantity, unit_price), discount_percent)
            shipping = compute_shipping_fee(item["weight_kg"] * quantity, zone)
            return {"sku": sku, "goods": goods, "shipping": shipping, "total": goods + shipping}
        ''')
    write(root, "inventory/reports.py", '''
        """Plain text reports."""
        from inventory.shipping import compute_shipping_fee


        def shipping_table(weights, zone):
            rows = [f"{weight:>6.1f} kg  {compute_shipping_fee(weight, zone):>8.2f}" for weight in weights]
            return "\\n".join(rows)
        ''')
    write(root, "inventory/cli.py", '''
        """Command line entry: python3 -m inventory.cli fee <weight> <zone>"""
        import sys

        from inventory.shipping import compute_shipping_fee


        def main(argv):
            if len(argv) == 3 and argv[0] == "fee":
                print(compute_shipping_fee(float(argv[1]), argv[2]))
                return 0
            print("usage: fee <weight_kg> <zone>")
            return 2


        if __name__ == "__main__":
            sys.exit(main(sys.argv[1:]))
        ''')
    write(root, "tests/__init__.py", "")
    write(root, "tests/test_pricing.py", '''
        import unittest

        from inventory.pricing import apply_discount, unit_total


        class PricingTest(unittest.TestCase):
            def test_unit_total(self):
                self.assertEqual(unit_total(3, 20), 60)

            def test_discount_takes_percent_off(self):
                self.assertEqual(apply_discount(200, 25), 150)

            def test_no_discount_keeps_the_price(self):
                self.assertEqual(apply_discount(80, 0), 80)


        if __name__ == "__main__":
            unittest.main()
        ''')
    write(root, "tests/test_shipping.py", '''
        import unittest

        from inventory.shipping import compute_shipping_fee


        class ShippingTest(unittest.TestCase):
            def test_local(self):
                self.assertEqual(compute_shipping_fee(2, "local"), 7)

            def test_unknown_zone(self):
                with self.assertRaises(ValueError):
                    compute_shipping_fee(1, "moon")
        ''')
    write(root, "tests/test_stock.py", '''
        import unittest

        from inventory.stock import reserve_stock, restock


        class StockTest(unittest.TestCase):
            def test_reserve(self):
                restock("SKU-0001", 5)
                self.assertEqual(reserve_stock("SKU-0001", 2), 3)
        ''')
    write(root, "docs/index.md", """
        # Documents

        - [API](api.md) — the public functions
        - [Spec](spec.pdf) — the signed-off specification
        """)
    write(root, "docs/api.md", """
        # API

        - `inventory.pricing.apply_discount(price, percent)`
        - `inventory.shipping.compute_shipping_fee(weight_kg, zone, express=False)`
        - `inventory.stock.reserve_stock(sku, quantity)`
        - `inventory.orders.place_order(sku, quantity, unit_price, zone, discount_percent=0)`
        """)
    write(root, "docs/会议 记录.md", f"""
        # 会议记录

        - 参加：三个人
        - 决定：旧接口的下线日期是 {FACTS['meeting']}
        """)
    write(root, "docs/legacy-gbk.txt", f"旧系统说明\n库存编号规则：{FACTS['gbk']}\n这个文件是 GBK 编码。\n", encoding="gbk")
    os.makedirs(os.path.join(root, "assets"), exist_ok=True)
    open(os.path.join(root, "assets/logo.png"), "wb").write(png())
    open(os.path.join(root, "docs/spec.pdf"), "wb").write(pdf(f"Spec revision {FACTS['pdf']}"))
    write(root, "scripts/noisy.py", f'''
        """Prints a very long log."""
        for i in range(40000):
            if i == 20000:
                print("{FACTS['noisy']}")
            print(f"line {{i}} ok")
        ''')
    write(root, "scripts/slow.py", 'import time\nprint("starting")\ntime.sleep(900)\nprint("done")\n')
    write(root, "scripts/ask.py", 'name = input("Your name: ")\nprint("hello", name)\n')
    write(root, "scripts/fetch.py", 'import urllib.request\nprint(urllib.request.urlopen("https://example.com", timeout=10).status)\n')
    for group in range(40):
        for index in range(50):
            write(root, f"node_modules/pkg{group:02d}/lib/file{index:02d}.js", f"module.exports = {group * 50 + index};\n")
    write(root, "skills/release-notes/SKILL.md", f"""
        ---
        name: release-notes
        description: Write release notes in this team's format. Use whenever the user asks for release notes or a changelog entry.
        ---
        Release notes always start with the line `{FACTS['skill']}` followed by a blank line.
        Then one bullet per change, each starting with a verb in the past tense.
        """)
    write(root, "skills/sql-review/SKILL.md", """
        ---
        name: sql-review
        description: Review SQL migrations for locking problems.
        ---
        Check every ALTER TABLE for a full table lock.
        """)
    write(root, "wiki/index.md", "Operations pages: ops/rollback.md explains how to roll back a release.\n")
    write(root, "wiki/ops/rollback.md", f"# Rolling back\n\nRoll back with the `{FACTS['wiki']}` switch, then tell the on-call channel.\n")

    # Git 历史
    git(root, "init", "-q")
    git(root, "add", "-A"); git(root, "commit", "-q", "-m", "initial import", author="Dev One", date="2026-09-01T09:00:00")
    def change(path, old, new, message, author, date):
        full = os.path.join(root, path); text = open(full, encoding="utf-8").read(); assert old in text, (path, old)
        open(full, "w", encoding="utf-8").write(text.replace(old, new)); git(root, "commit", "-q", "-am", message, author=author, date=date)
    change("inventory/shipping.py", '"overseas": 3.0', '"overseas": 3.5', "raise the overseas rate to 3.5", "Tomas Reed", "2026-09-10T11:00:00")
    change("inventory/pricing.py", "Price of a line before any discount.", "Price of a line before any discount is applied.", "pricing: clearer docstring", "Dev One", "2026-09-12T15:00:00")
    change("inventory/shipping.py", "EXPRESS_SURCHARGE = 8", "EXPRESS_SURCHARGE = 12", "raise express surcharge to 12", "Mina Park", "2026-09-20T14:30:00")
    change("inventory/stock.py", '"""Stock levels kept in memory."""', '"""Stock levels kept in memory (not persisted)."""', "stock: say it is not persisted", "Tomas Reed", "2026-09-25T10:00:00")
    change("inventory/shipping.py", "BASE_FEE = 5", "BASE_FEE = 5  # flat part of every parcel", "shipping: comment the base fee", "Dev One", "2026-10-02T09:15:00")
    # 没提交的改动
    with open(os.path.join(root, "inventory/reports.py"), "a", encoding="utf-8") as f:
        f.write("\n\n# TODO(dev-note): add a CSV export of shipping_table\n")
    write(root, "notes/todo.txt", "ask Mina about the overseas rate\n")
    git(root, "tag", "start")
    return root

def reset(root):
    """回到起点（比重建快）：提交过的回到 start，没提交的那两处改动重新放上。"""
    env = dict(os.environ, GIT_CONFIG_GLOBAL="/dev/null", GIT_CONFIG_SYSTEM="/dev/null")
    subprocess.run(["git", "reset", "-q", "--hard", "start"], cwd=root, env=env, check=True)
    subprocess.run(["git", "clean", "-qfd", "-e", "node_modules"], cwd=root, env=env, check=True)
    with open(os.path.join(root, "inventory/reports.py"), "a", encoding="utf-8") as f:
        f.write("\n\n# TODO(dev-note): add a CSV export of shipping_table\n")
    write(root, "notes/todo.txt", "ask Mina about the overseas rate\n")

class Site(BaseHTTPRequestHandler):
    def log_message(self, *args): pass
    def page(self, body, status=200, ctype="text/html; charset=utf-8", headers=()):
        data = body.encode() if isinstance(body, str) else body
        self.send_response(status); self.send_header("Content-Type", ctype); self.send_header("Content-Length", str(len(data)))
        for k, v in headers: self.send_header(k, v)
        self.end_headers(); self.wfile.write(data)
    def do_GET(self):
        path = self.path.split("?")[0]
        if path == "/docs/rate-limits.html":
            self.page(f"<html><head><title>Rate limits</title></head><body><h1>Rate limits</h1><p>The steady limit is 10 requests per second.</p><p>The burst limit is {FACTS['web']} requests.</p></body></html>")
        elif path == "/old":
            self.page("", 302, headers=[("Location", "/docs/rate-limits.html")])
        elif path == "/huge":
            filler = "".join(f"<p>Paragraph {i} of the long manual, nothing special here.</p>\n" for i in range(30000))
            self.page(f"<html><head><title>Long manual</title></head><body><h1>Long manual</h1>{filler}<p>Final note: {FACTS['web_tail']}</p></body></html>")
        elif path == "/form":
            self.page('<html><head><title>Order form</title></head><body><h1>Order form</h1><form action="/submit" method="get">'
                      '<label>Name <input name="name"></label> <label>Quantity <select name="qty"><option>1</option><option>2</option><option>5</option></select></label> '
                      '<button type="submit">Place order</button></form></body></html>')
        elif path == "/submit":
            from urllib.parse import parse_qs, urlparse
            q = parse_qs(urlparse(self.path).query); name = q.get("name", [""])[0]; qty = q.get("qty", [""])[0]
            self.page(f"<html><head><title>Confirmed</title></head><body><h1>Order confirmed</h1><p id='code'>Confirmation: CONF-{name.upper()}-{qty}</p></body></html>")
        elif path == "/slow":
            self.page("<html><head><title>Slow page</title></head><body><h1>Slow page</h1><p id='status'>Loading…</p>"
                      f"<script>setTimeout(function(){{document.getElementById('status').textContent='Loaded token: {FACTS['slow']}'}}, 2500)</script></body></html>")
        else:
            self.page("<html><body><h1>Not found</h1></body></html>", 404)

def serve(port):
    server = ThreadingHTTPServer(("127.0.0.1", port), Site)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    return server

if __name__ == "__main__":
    import sys
    root = build(sys.argv[1]); print("built", root)
    out = subprocess.run(["python3", "-m", "unittest", "discover", "-s", "tests", "-q"], cwd=root, capture_output=True, text=True)
    print(out.stderr.strip().splitlines()[-1])
