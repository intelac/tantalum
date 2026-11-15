// Live monitor: the server's pipeline as a diagram; each pipeline stage the current session's events pass through moves a dot
// along the edges, in the order the stages arrive. Data comes only from the panel bridge ("monitor" messages).
(function () {
  "use strict";
  var NS = "http://www.w3.org/2000/svg";
  var svg = document.getElementById("map");
  var now = document.getElementById("now");
  var empty = document.getElementById("empty");
  var skipped = document.getElementById("skipped");
  var pauseButton = document.getElementById("pause");
  var live = document.getElementById("live");

  var TEXT = {
    en: { live: "Live", pause: "Pause", resume: "Resume", waiting: "Waiting for events… ask something in the chat.",
          skipped: function (n) { return "Skipped " + n + " steps to keep up"; },
          zones: { browser: "Browser", server: "Tantalum server", handlers: "Handlers", models: "Model service", computer: "Your computer" },
          nodes: { page: "Chat page", intake: "Intake", input: "Input ring", lane: "User lane", kernel: "Kernel step", hooks: "Hook plugins",
                   journal: "Journal", hub: "Push", out: "Output ring", disp: "Dispatch", ctx: "Context", model: "Model call", tool: "Tool",
                   work: "Plugin work", timer: "Timer", links: "Local link", llm: "Model", conn: "Connector" },
          stages: { INTAKE: "Submission accepted", INPUT: "Into the input ring", LANE: "User lane assigns a number", KERNEL: "Kernel takes a step",
                    JOURNAL: "Written to the journal", PUSH: "Pushed to the page", OUTPUT: "Command into the output ring",
                    HANDLER_START: "Handler starts", HANDLER_END: "Handler done", REMOTE_SEND: "Sent to the connector", REMOTE_DONE: "Connector replied" } },
    zh: { live: "实时", pause: "暂停", resume: "继续", waiting: "等待事件… 在对话里提一个问题就能看到。",
          skipped: function (n) { return "为了跟上，跳过了 " + n + " 步"; },
          zones: { browser: "浏览器", server: "Tantalum 服务器", handlers: "处理器", models: "模型服务", computer: "用户电脑" },
          nodes: { page: "对话页", intake: "提交入口", input: "输入环", lane: "用户通道", kernel: "内核走一步", hooks: "钩子插件",
                   journal: "写 journal", hub: "推送", out: "输出环", disp: "分发", ctx: "组装上下文", model: "调模型", tool: "执行工具",
                   work: "插件工作", timer: "定时器", links: "本机连接", llm: "模型", conn: "连接程序" },
          stages: { INTAKE: "提交进入口", INPUT: "进入输入环", LANE: "用户通道取号", KERNEL: "内核走一步", JOURNAL: "写入 journal",
                    PUSH: "推送给页面", OUTPUT: "命令进输出环", HANDLER_START: "处理器开始", HANDLER_END: "处理器结束",
                    REMOTE_SEND: "发给连接程序", REMOTE_DONE: "连接程序返回" } },
    ja: { live: "ライブ", pause: "一時停止", resume: "再開", waiting: "イベント待ち… 会話で質問すると表示されます。",
          skipped: function (n) { return "追いつくため " + n + " ステップを飛ばしました"; },
          zones: { browser: "ブラウザ", server: "Tantalum サーバー", handlers: "ハンドラー", models: "モデルサービス", computer: "ユーザーの PC" },
          nodes: { page: "会話ページ", intake: "受付", input: "入力リング", lane: "ユーザーレーン", kernel: "カーネル 1 ステップ", hooks: "フックプラグイン",
                   journal: "journal 書き込み", hub: "プッシュ", out: "出力リング", disp: "振り分け", ctx: "コンテキスト組み立て", model: "モデル呼び出し",
                   tool: "ツール実行", work: "プラグイン処理", timer: "タイマー", links: "ローカル接続", llm: "モデル", conn: "コネクター" },
          stages: { INTAKE: "受付に入った", INPUT: "入力リングへ", LANE: "ユーザーレーンが採番", KERNEL: "カーネルが 1 ステップ", JOURNAL: "journal に書き込み",
                    PUSH: "ページへプッシュ", OUTPUT: "コマンドが出力リングへ", HANDLER_START: "ハンドラー開始", HANDLER_END: "ハンドラー終了",
                    REMOTE_SEND: "コネクターへ送信", REMOTE_DONE: "コネクターから返答" } }
  };

  // Layout in a 640 × 830 viewBox, top to bottom like the pipeline.
  var ZONES = [
    ["browser", 10, 10, 140, 70],
    ["server", 160, 10, 470, 600],
    ["handlers", 170, 430, 450, 170],
    ["models", 10, 620, 300, 200],
    ["computer", 330, 620, 300, 200]
  ];
  var NODES = {
    page:    [20, 32, 120, 40],
    intake:  [175, 40, 130, 44, "Intake"],
    input:   [325, 40, 130, 44, "InputRing"],
    lane:    [480, 40, 135, 44, "UserLane"],
    hooks:   [175, 130, 130, 44, "beforeModelCall…", "plug"],
    kernel:  [325, 130, 130, 44, "Kernel"],
    journal: [325, 220, 130, 44, "fsync"],
    hub:     [175, 220, 130, 44, "SSE"],
    out:     [480, 220, 135, 44, "OutputRing"],
    disp:    [480, 310, 135, 44, "HandlerDispatcher"],
    ctx:     [180, 460, 82, 50],
    model:   [268, 460, 82, 50],
    tool:    [356, 460, 82, 50],
    work:    [444, 460, 82, 50],
    timer:   [532, 460, 82, 50],
    links:   [356, 545, 130, 44, "LocalLinks"],
    llm:     [80, 680, 160, 50],
    conn:    [400, 680, 160, 50, "tantalum-connect"]
  };
  // Edges drawn between box borders; the dot can travel them either way.
  var EDGES = [
    ["page", "intake"], ["intake", "input"], ["input", "lane"], ["lane", "kernel"], ["kernel", "hooks"], ["kernel", "journal"],
    ["journal", "hub"], ["hub", "page"], ["journal", "out"], ["out", "disp"],
    ["disp", "ctx"], ["disp", "model"], ["disp", "tool"], ["disp", "work"], ["disp", "timer"],
    ["ctx", "input"], ["model", "input"], ["tool", "input"], ["work", "input"], ["timer", "input"],
    ["tool", "links"], ["links", "conn"], ["model", "llm"]
  ];
  var HANDLER_NODE = { AssembleContext: "ctx", CallModel: "model", DoWork: "work", StartTimer: "timer" };

  var context = null;
  var nodeEls = {};
  var edgeEls = {};
  var queue = [];
  var playing = false;
  var paused = false;
  var current = null;
  var reduced = window.matchMedia && window.matchMedia("(prefers-reduced-motion: reduce)").matches;
  var token;

  function text() { return TEXT[(context && context.language) || "en"] || TEXT.en; }

  function el(name, attrs, parent) {
    var element = document.createElementNS(NS, name);
    for (var key in attrs) element.setAttribute(key, attrs[key]);
    (parent || svg).appendChild(element);
    return element;
  }

  function center(id) {
    var n = NODES[id];
    return { x: n[0] + n[2] / 2, y: n[1] + n[3] / 2 };
  }

  // Where the line from a's centre to b's centre leaves box a.
  function border(id, toward) {
    var n = NODES[id];
    var c = center(id);
    var dx = toward.x - c.x;
    var dy = toward.y - c.y;
    var sx = dx === 0 ? Infinity : (n[2] / 2) / Math.abs(dx);
    var sy = dy === 0 ? Infinity : (n[3] / 2) / Math.abs(dy);
    var s = Math.min(sx, sy);
    return { x: c.x + dx * s, y: c.y + dy * s };
  }

  function edgePath(a, b) {
    var from = border(a, center(b));
    var to = border(b, center(a));
    return "M" + from.x.toFixed(1) + "," + from.y.toFixed(1) + " L" + to.x.toFixed(1) + "," + to.y.toFixed(1);
  }

  function draw() {
    svg.textContent = "";
    var t = text();
    ZONES.forEach(function (zone) {
      el("rect", { x: zone[1], y: zone[2], width: zone[3], height: zone[4], rx: 10, "class": "zone" });
      var label = el("text", { x: zone[1] + 10, y: zone[2] + 16, "class": "zlabel" });
      label.textContent = t.zones[zone[0]];
    });
    EDGES.forEach(function (edge) {
      edgeEls[edge[0] + ">" + edge[1]] = el("path", { d: edgePath(edge[0], edge[1]), "class": "edge" });
    });
    Object.keys(NODES).forEach(function (id) {
      var n = NODES[id];
      var group = el("g", { "class": "node" + (n[5] ? " " + n[5] : "") });
      el("rect", { x: n[0], y: n[1], width: n[2], height: n[3], rx: 8 }, group);
      var title = el("text", { x: n[0] + n[2] / 2, y: n[1] + (n[4] ? 17 : n[3] / 2 + 4), "text-anchor": "middle", "class": "t" }, group);
      title.textContent = t.nodes[id];
      if (n[4]) {
        var sub = el("text", { x: n[0] + n[2] / 2, y: n[1] + 30, "text-anchor": "middle", "class": "s" }, group);
        sub.textContent = n[4];
      }
      var value = el("text", { x: n[0] + n[2] / 2, y: n[1] + n[3] + 13, "text-anchor": "middle", "class": "v" }, group);
      group.value = value;
      nodeEls[id] = group;
    });
    token = el("circle", { r: 7, id: "token", visibility: "hidden" });
    live.textContent = t.live;
    pauseButton.textContent = paused ? t.resume : t.pause;
    empty.textContent = t.waiting;
  }

  function nodeOf(message) {
    switch (message.stage) {
      case "INTAKE": return "intake";
      case "INPUT": return "input";
      case "LANE": return "lane";
      case "KERNEL": return "kernel";
      case "JOURNAL": return "journal";
      case "PUSH": return "hub";
      case "OUTPUT": return "out";
      case "HANDLER_START":
      case "HANDLER_END": return HANDLER_NODE[message.name] || "tool";
      case "REMOTE_SEND": return "conn";
      case "REMOTE_DONE": return "links";
      default: return null;
    }
  }

  // The value under a box stays within the box's width: narrow boxes keep the first part ("1678 ms" of "1678 ms, ModelReplied").
  function fit(value, width) {
    var max = Math.floor(width / 5.6);
    if (value.length <= max) return value;
    var first = value.split(", ")[0];
    return first.length <= max ? first : first.slice(0, max - 1) + "…";
  }

  function describe(message) {
    var line = text().stages[message.stage] || message.stage;
    if (message.name) line += " · " + message.name;
    if (message.detail) line += " · " + message.detail;
    return line;
  }

  function light(id, message) {
    if (current && nodeEls[current]) nodeEls[current].setAttribute("class", nodeEls[current].getAttribute("class").replace(" cur", "") + " done");
    var group = nodeEls[id];
    group.setAttribute("class", group.getAttribute("class").replace(" done", "").replace(" cur", "") + " cur");
    group.value.textContent = fit(message.detail || message.name || "", NODES[id][2]);
    if (message.stage === "HANDLER_START" && message.name === "CallModel") {
      nodeEls.llm.value.textContent = "…";
      flash("model>llm");
    }
    current = id;
    now.textContent = describe(message);
  }

  function flash(key) {
    var edge = edgeEls[key];
    if (!edge) return;
    edge.setAttribute("class", "edge hit");
    setTimeout(function () { edge.setAttribute("class", "edge"); }, 900);
  }

  function move(from, to, done) {
    var key = edgeEls[from + ">" + to] ? from + ">" + to : edgeEls[to + ">" + from] ? to + ">" + from : null;
    if (!from || !key || reduced) {
      if (key) flash(key);
      done();
      return;
    }
    var path = edgeEls[key];
    var reverse = key !== from + ">" + to;
    var length = path.getTotalLength();
    var duration = Math.max(120, 350 - queue.length * 10);
    var start = performance.now();
    path.setAttribute("class", "edge hit");
    token.setAttribute("visibility", "visible");
    (function frame(time) {
      var k = Math.min(1, (time - start) / duration);
      var eased = k < 0.5 ? 2 * k * k : 1 - Math.pow(-2 * k + 2, 2) / 2;
      var point = path.getPointAtLength(length * (reverse ? 1 - eased : eased));
      token.setAttribute("cx", point.x);
      token.setAttribute("cy", point.y);
      if (k < 1) requestAnimationFrame(frame);
      else {
        setTimeout(function () { path.setAttribute("class", "edge"); }, 600);
        done();
      }
    })(start);
  }

  function play() {
    if (playing || paused) return;
    var message = queue.shift();
    if (!message) return;
    var target = nodeOf(message);
    if (!target) { play(); return; }
    playing = true;
    empty.hidden = true;
    move(current, target, function () {
      light(target, message);
      playing = false;
      setTimeout(play, reduced ? 0 : 60);
    });
  }

  function receive(messages) {
    if (paused) {
      queue = messages.slice(-1);
      return;
    }
    queue = queue.concat(messages);
    if (queue.length > 50) {
      var dropped = queue.length - 1;
      queue = queue.slice(-1);
      skipped.textContent = text().skipped(dropped);
      skipped.hidden = false;
      setTimeout(function () { skipped.hidden = true; }, 3000);
    }
    play();
  }

  pauseButton.addEventListener("click", function () {
    paused = !paused;
    document.body.classList.toggle("paused", paused);
    pauseButton.textContent = paused ? text().resume : text().pause;
    if (!paused) play();
  });

  function apply(message) {
    var languageChanged = !context || context.language !== message.language;
    var sessionChanged = context && context.session !== message.session;
    context = message;
    document.documentElement.dataset.theme = message.theme;
    document.documentElement.lang = message.language;
    if (languageChanged) {
      draw();
      current = null;
    }
    if (sessionChanged) {
      queue = [];
      empty.hidden = false;
    }
  }

  window.tantalum.on("init", function (message) {
    apply(message);
    window.tantalum.subscribe("monitor");
  });
  window.tantalum.on("context", apply);
  window.tantalum.on("monitor", function (message) { receive(message.messages || []); });
  window.tantalum.ready();
})();
