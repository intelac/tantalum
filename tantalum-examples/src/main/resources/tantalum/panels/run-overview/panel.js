// Run overview: the latest turn of the current session as a compact tree, refreshed while the run is live.
(function () {
  "use strict";
  var TEXT = {
    en: { none: "No runs in this session yet.", turn: "Turn", step: "Step", running: "Running", failed: "Failed", done: "Done", loading: "Loading…" },
    zh: { none: "这个会话还没有运行。", turn: "第", step: "第", running: "进行中", failed: "失败", done: "完成", loading: "加载中…" },
    ja: { none: "この会話にはまだ実行がありません。", turn: "ターン", step: "ステップ", running: "実行中", failed: "失敗", done: "完了", loading: "読み込み中…" }
  };
  var context = null;
  var run = null;
  var turn = null;
  var timer = null;
  var status = document.getElementById("status");
  var steps = document.getElementById("steps");

  function text() { return TEXT[(context && context.language) || "en"] || TEXT.en; }

  function label(kind, n) {
    var t = text();
    if (context && context.language === "zh") return t[kind] + " " + n + (kind === "turn" ? " 轮" : " 步");
    return t[kind] + " " + n;
  }

  function seconds(ms) {
    if (ms === undefined || ms === null) return "";
    return ms < 1000 ? ms + " ms" : (ms / 1000).toFixed(ms < 10000 ? 2 : 1) + " s";
  }

  function item(node, depth) {
    var li = document.createElement("li");
    li.className = "node " + node.kind + " " + node.state;
    li.style.paddingLeft = depth * 14 + "px";
    var name = document.createElement("span");
    name.className = "name";
    name.textContent = node.kind === "step" ? label("step", (node.name || "").replace(/\D+/g, "")) : node.name || node.kind;
    var time = document.createElement("span");
    time.className = "time";
    time.textContent = seconds(node.ms);
    li.appendChild(name);
    if (node.state === "error") {
      var mark = document.createElement("span");
      mark.className = "mark";
      mark.textContent = "!";
      mark.title = node.detail || text().failed;
      li.appendChild(mark);
    }
    li.appendChild(time);
    li.tabIndex = 0;
    li.addEventListener("click", function () { window.tantalum.open({ run: run }); });
    li.addEventListener("keydown", function (event) { if (event.key === "Enter") window.tantalum.open({ run: run }); });
    return li;
  }

  function render(root) {
    steps.textContent = "";
    var t = text();
    var state = root.state === "running" || root.state === "waiting" ? t.running : root.state === "error" ? t.failed : t.done;
    status.textContent = (turn === undefined || turn === null ? "" : label("turn", turn) + " · ") + state + (root.ms ? " · " + seconds(root.ms) : "");
    status.className = root.state === "error" ? "error" : "muted";
    (root.children || []).forEach(function (child) {
      if (child.kind !== "step" && child.kind !== "compaction") return;
      steps.appendChild(item(child, 0));
      (child.children || []).forEach(function (inner) {
        if (inner.kind === "request" || inner.kind === "tool" || inner.kind === "hook" || inner.kind === "work") steps.appendChild(item(inner, 1));
      });
    });
  }

  function load() {
    if (!context) return;
    status.textContent = text().loading;
    window.tantalum.get("runs", { limit: 1 }).then(function (page) {
      var latest = page.runs && page.runs[0];
      if (!latest) {
        run = null;
        steps.textContent = "";
        status.textContent = text().none;
        return null;
      }
      run = latest.run;
      turn = latest.turn;
      return window.tantalum.get("tree", { run: run });
    }).then(function (tree) {
      if (tree) render(tree.root);
    }).catch(function (failure) {
      window.tantalum.error(failure.message);
    });
  }

  function later() {
    clearTimeout(timer);
    timer = setTimeout(load, 500);
  }

  function apply(message) {
    var changed = !context || context.session !== message.session;
    context = message;
    document.documentElement.dataset.theme = message.theme;
    document.documentElement.lang = message.language;
    if (changed) load();
  }

  window.tantalum.on("init", function (message) {
    apply(message);
    window.tantalum.subscribe();
  });
  window.tantalum.on("context", apply);
  // A new run, or the current one moving on: reload the latest turn (debounced).
  window.tantalum.on("trace", later);
  window.tantalum.ready();
})();
