// Live monitor, drawn like the approved mockup (flow.html): the server's pipeline in a 1010 × 710 diagram. Each stage the current
// session's events pass through moves a glowing dot along the drawn edges to that component, which lights up and shows the stage's
// figure; the edges walked turn blue and carry a step number. Data comes only from the panel bridge ("monitor" messages).
(function () {
  "use strict";
  var NS = "http://www.w3.org/2000/svg";
  var svg = document.getElementById("map");

  var TEXT = {
    zh: {
      narrow: "点右上角的放大看清楚", live: "实时", pause: "暂停", resume: "继续", track: "跟踪 本会话", waiting: "等待事件… 在对话里提一个问题就能看到。",
      skipped: function (n) { return "为了跟上，跳过了 " + n + " 步"; },
      legend: ["事件正在走的路（光点是它现在的位置）", "插件", "组件之间的流向"],
      zones: { browser: "浏览器 · 控制台", server: "Tantalum 服务器", models: "模型服务", computer: "用户电脑 · 连接程序", handlers: "处理器" },
      nodes: {
        page: ["对话页", "控制台"], mon: ["实时监视面板", "本插件"], intake: ["提交入口", "Intake"], input: ["输入环", "InputRing"],
        permit: ["放行", "LaneDispatcher"], lane: ["用户通道", "UserLane"], cring: ["净室环", "CleanroomRing"], kernel: ["净室 · 内核走一步", "状态机"],
        hooks: ["钩子插件", "beforeModelCall …"], journal: ["写 journal", "UserLane · fsync"], out: ["输出环", "OutputRing"], hub: ["推送中心", "SSE Hub"],
        index: ["索引", "会话列表、运行表"], disp: ["分发", "HandlerDispatcher"], ctx: ["组装上下文", "ContextHandler"], model: ["调模型", "ModelHandler"],
        tool: ["执行工具", "ToolHandler"], work: ["插件工作", "WorkHandler"], timer: ["定时器", "TimerHandler"], after: ["工具后处理插件", "密钥遮盖 · 截断"],
        links: ["本机连接", "LocalLinks"], llm: ["模型", "Claude API · 本机 qwen"], conn: ["连接程序", "tantalum-connect"],
        files: ["文件 · Git · 命令", ""], browser: ["浏览器", "Playwright → Chrome"]
      },
      stages: { INTAKE: "提交进入口", INPUT: "进入输入环", LANE: "用户通道取号", KERNEL: "内核走一步", JOURNAL: "写 journal", PUSH: "推送给页面",
                OUTPUT: "命令进输出环", HANDLER_START: "处理器开始", HANDLER_END: "处理器结束", REMOTE_SEND: "发给连接程序", REMOTE_DONE: "连接程序返回" },
      generating: "生成中…"
    },
    en: {
      narrow: "Maximize the panel to read it", live: "Live", pause: "Pause", resume: "Resume", track: "Following this session", waiting: "Waiting for events… ask something in the chat.",
      skipped: function (n) { return "Skipped " + n + " steps to keep up"; },
      legend: ["The path the event takes (the dot is where it is now)", "Plugins", "Flow between components"],
      zones: { browser: "Browser · console", server: "Tantalum server", models: "Model service", computer: "Your computer · connector", handlers: "Handlers" },
      nodes: {
        page: ["Chat page", "Console"], mon: ["Live monitor", "This plugin"], intake: ["Intake", "Intake"], input: ["Input ring", "InputRing"],
        permit: ["Permit", "LaneDispatcher"], lane: ["User lane", "UserLane"], cring: ["Cleanroom ring", "CleanroomRing"], kernel: ["Cleanroom · kernel step", "State machine"],
        hooks: ["Hook plugins", "beforeModelCall …"], journal: ["Write journal", "UserLane · fsync"], out: ["Output ring", "OutputRing"], hub: ["Push hub", "SSE Hub"],
        index: ["Index", "Sessions, runs"], disp: ["Dispatch", "HandlerDispatcher"], ctx: ["Context", "ContextHandler"], model: ["Model call", "ModelHandler"],
        tool: ["Run tool", "ToolHandler"], work: ["Plugin work", "WorkHandler"], timer: ["Timer", "TimerHandler"], after: ["After-tool plugins", "Masking · truncation"],
        links: ["Local link", "LocalLinks"], llm: ["Model", "Claude API · local qwen"], conn: ["Connector", "tantalum-connect"],
        files: ["Files · Git · shell", ""], browser: ["Browser", "Playwright → Chrome"]
      },
      stages: { INTAKE: "Submission accepted", INPUT: "Into the input ring", LANE: "User lane assigns a number", KERNEL: "Kernel takes a step",
                JOURNAL: "Written to the journal", PUSH: "Pushed to the page", OUTPUT: "Command into the output ring", HANDLER_START: "Handler starts",
                HANDLER_END: "Handler done", REMOTE_SEND: "Sent to the connector", REMOTE_DONE: "Connector replied" },
      generating: "Generating…"
    },
    ja: {
      narrow: "右上の最大化で見やすくなります", live: "ライブ", pause: "一時停止", resume: "再開", track: "この会話を追跡", waiting: "イベント待ち… 会話で質問すると表示されます。",
      skipped: function (n) { return "追いつくため " + n + " ステップを飛ばしました"; },
      legend: ["イベントの経路（光点が今の位置）", "プラグイン", "コンポーネント間の流れ"],
      zones: { browser: "ブラウザ · コンソール", server: "Tantalum サーバー", models: "モデルサービス", computer: "ユーザーの PC · コネクター", handlers: "ハンドラー" },
      nodes: {
        page: ["会話ページ", "コンソール"], mon: ["ライブモニター", "このプラグイン"], intake: ["受付", "Intake"], input: ["入力リング", "InputRing"],
        permit: ["許可", "LaneDispatcher"], lane: ["ユーザーレーン", "UserLane"], cring: ["クリーンルームリング", "CleanroomRing"], kernel: ["カーネル 1 ステップ", "状態機械"],
        hooks: ["フックプラグイン", "beforeModelCall …"], journal: ["journal 書き込み", "UserLane · fsync"], out: ["出力リング", "OutputRing"], hub: ["プッシュ", "SSE Hub"],
        index: ["インデックス", "会話一覧・実行"], disp: ["振り分け", "HandlerDispatcher"], ctx: ["コンテキスト", "ContextHandler"], model: ["モデル呼び出し", "ModelHandler"],
        tool: ["ツール実行", "ToolHandler"], work: ["プラグイン処理", "WorkHandler"], timer: ["タイマー", "TimerHandler"], after: ["ツール後処理", "マスク · 切り詰め"],
        links: ["ローカル接続", "LocalLinks"], llm: ["モデル", "Claude API · ローカル qwen"], conn: ["コネクター", "tantalum-connect"],
        files: ["ファイル · Git · コマンド", ""], browser: ["ブラウザ", "Playwright → Chrome"]
      },
      stages: { INTAKE: "受付に入った", INPUT: "入力リングへ", LANE: "ユーザーレーンが採番", KERNEL: "カーネルが 1 ステップ", JOURNAL: "journal に書き込み",
                PUSH: "ページへプッシュ", OUTPUT: "コマンドが出力リングへ", HANDLER_START: "ハンドラー開始", HANDLER_END: "ハンドラー終了",
                REMOTE_SEND: "コネクターへ送信", REMOTE_DONE: "コネクターから返答" },
      generating: "生成中…"
    }
  };

  // The mockup's layout: [x, y, width, height, kind].
  var ZONES = [["browser", 10, 10, 175, 150], ["server", 200, 10, 605, 690], ["models", 820, 10, 180, 190], ["computer", 820, 430, 180, 270]];
  var NODES = {
    page: [25, 45, 145, 46], mon: [25, 100, 145, 44, "plug"],
    intake: [215, 48, 125, 52], input: [365, 48, 125, 52], permit: [515, 48, 125, 52], lane: [665, 48, 125, 52],
    cring: [665, 165, 125, 50], kernel: [440, 160, 190, 60], hooks: [232, 168, 180, 46, "plug"],
    journal: [440, 305, 190, 52], out: [665, 305, 125, 52], hub: [215, 305, 125, 52], index: [215, 380, 125, 40],
    disp: [665, 430, 125, 48],
    ctx: [215, 528, 112, 50], model: [337, 528, 112, 50], tool: [459, 528, 112, 50], work: [581, 528, 100, 50], timer: [691, 528, 100, 50],
    after: [459, 598, 112, 48, "plug"], links: [691, 600, 100, 52],
    llm: [835, 60, 150, 90],
    conn: [835, 465, 150, 44], files: [835, 522, 150, 40], browser: [835, 574, 150, 56]
  };
  // Hand-drawn edges (from the mockup). The dot only travels these, in the direction given; REVERSIBLE ones also go back.
  var EDGES = {
    "page>intake": "M170,68 C190,68 195,74 213,74",
    "intake>input": "M340,74 L363,74",
    "input>permit": "M490,74 L513,74",
    "permit>lane": "M640,74 L663,74",
    "lane>cring": "M727,100 L727,163",
    "cring>kernel": "M665,190 L632,190",
    "kernel>hooks": "M440,190 L415,190",
    "kernel>journal": "M535,222 L535,301",
    "journal>hub": "M440,331 L342,331",
    "hub>page": "M215,331 L192,331 L192,68 L173,68",
    "hub>index": "M277,357 L277,378",
    "journal>out": "M630,331 L663,331",
    "out>disp": "M727,357 L727,428",
    "disp>ctx": "M665,455 C600,470 300,470 271,526",
    "disp>model": "M665,455 C560,455 420,470 400,526",
    "disp>tool": "M690,478 C660,500 540,500 515,526",
    "disp>work": "M710,478 C690,500 650,500 631,526",
    "disp>timer": "M745,478 L745,526",
    "tool>after": "M515,578 L515,596",
    "after>input": "M459,622 C330,622 207,640 207,560 L207,150 L427,150 L427,102",
    "ctx>input": "M245,528 L245,505 L207,505 L207,150 L427,150 L427,102",
    "model>input": "M365,528 L365,505 L207,505 L207,150 L427,150 L427,102",
    "work>input": "M610,528 L610,505 L207,505 L207,150 L427,150 L427,102",
    "timer>input": "M720,528 L720,505 L207,505 L207,150 L427,150 L427,102",
    "tool>links": "M571,560 C600,600 640,624 691,624",
    "links>conn": "M793,626 C810,626 805,487 833,487",
    "conn>files": "M910,509 L910,520",
    "conn>browser": "M833,490 C826,505 826,600 835,600",
    "model>llm": "M393,580 L393,692 L812,692 L812,105 L833,105"
  };
  var REVERSIBLE = { "tool>links": true, "links>conn": true, "conn>files": true, "conn>browser": true };
  var PLUGIN_EDGES = { "kernel>hooks": true, "tool>after": true };
  var HANDLER = { AssembleContext: "ctx", CallModel: "model", DoWork: "work", StartTimer: "timer" };

  var context = null;
  var nodeEls = {};
  var hitEls = {};
  var badgeEls = {};
  var token = null;
  var trail = null;
  var current = null;
  var step = 0;
  var queue = [];
  var playing = false;
  var paused = false;
  var reduced = window.matchMedia && window.matchMedia("(prefers-reduced-motion: reduce)").matches;

  function text() { return TEXT[(context && context.language) || "en"] || TEXT.en; }
  function $(id) { return document.getElementById(id); }

  function el(name, attrs, parent) {
    var element = document.createElementNS(NS, name);
    for (var key in attrs) element.setAttribute(key, attrs[key]);
    (parent || svg).appendChild(element);
    return element;
  }

  function label(x, y, value, cls, parent, anchor) {
    var t = el("text", { x: x, y: y, "class": cls, "text-anchor": anchor || "middle" }, parent);
    t.textContent = value;
    return t;
  }

  // The value under a box stays inside the box's width.
  function fit(value, width) {
    var max = Math.floor(width / 6.2);
    if (value.length <= max) return value;
    var first = value.split(", ")[0];
    return first.length <= max ? first : first.slice(0, max - 1) + "…";
  }

  function draw() {
    svg.textContent = "";
    hitEls = {};
    badgeEls = {};
    var t = text();
    el("defs", {}).innerHTML =
      '<marker id="ag" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto"><path d="M0,0 L10,5 L0,10 z" style="fill: var(--edge)"/></marker>' +
      '<marker id="ab" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto"><path d="M0,0 L10,5 L0,10 z" style="fill: var(--blue)"/></marker>';
    ZONES.forEach(function (z) {
      el("rect", { x: z[1], y: z[2], width: z[3], height: z[4], rx: 12, "class": "zone" });
      label(z[1] + 12, z[2] + 19, t.zones[z[0]], "zlabel", svg, "start");
    });
    label(215, 515, t.zones.handlers, "zlabel", svg, "start");
    Object.keys(EDGES).forEach(function (key) {
      el("path", { d: EDGES[key], "class": "base", "marker-end": "url(#ag)" });
    });
    Object.keys(NODES).forEach(function (id) {
      var n = NODES[id];
      var names = t.nodes[id];
      var group = el("g", { "class": "node idle" + (n[4] ? " " + n[4] : "") });
      el("rect", { x: n[0] - 6, y: n[1] - 6, width: n[2] + 12, height: n[3] + 12, rx: 14, "class": "halo" }, group);
      el("rect", { x: n[0], y: n[1], width: n[2], height: n[3], rx: 9, "class": "box" }, group);
      var top = n[1] + n[3] / 2 - (names[1] ? 6 : 0) - 6;
      label(n[0] + n[2] / 2, top + 4, names[0], "t", group);
      if (names[1]) label(n[0] + n[2] / 2, top + 19, names[1], "s", group);
      group.value = label(n[0] + n[2] / 2, n[1] + n[3] + 13, "", "v", group);
      nodeEls[id] = group;
    });
    Object.keys(EDGES).forEach(function (key) {
      var plugin = PLUGIN_EDGES[key];
      hitEls[key] = el("path", { d: EDGES[key], "class": plugin ? "plugline" : "hit", "marker-end": plugin ? "" : "url(#ab)" });
    });
    trail = el("circle", { r: 4, id: "trail", visibility: "hidden" });
    token = el("circle", { r: 7, id: "token", visibility: "hidden" });
    $("live").textContent = t.live;
    $("narrow").textContent = t.narrow;
    $("trackLabel").textContent = t.track;
    $("pause").textContent = paused ? t.resume : t.pause;
    $("empty").textContent = t.waiting;
    $("legendPath").textContent = t.legend[0];
    $("legendPlugin").textContent = t.legend[1];
    $("legendFlow").textContent = t.legend[2];
    current = null;
  }

  function setClass(id, state) {
    var group = nodeEls[id];
    var base = group.getAttribute("class").replace(/ (idle|done|cur)/g, "");
    group.setAttribute("class", base + " " + state);
  }

  // A new turn (a submission came in): everything goes back to dim, numbering starts again.
  function reset() {
    Object.keys(nodeEls).forEach(function (id) {
      setClass(id, "idle");
      nodeEls[id].value.textContent = "";
    });
    Object.keys(hitEls).forEach(function (key) { hitEls[key].classList.remove("on"); });
    Object.keys(badgeEls).forEach(function (key) { badgeEls[key].remove(); });
    badgeEls = {};
    step = 0;
    current = "page";
    setClass("page", "done");
  }

  // Shortest route over the drawn edges (directed, plus the reversible connector edges).
  function route(from, to) {
    if (!from || from === to) return [];
    var next = {};
    Object.keys(EDGES).forEach(function (key) {
      var parts = key.split(">");
      (next[parts[0]] = next[parts[0]] || []).push({ to: parts[1], key: key, reverse: false });
      if (REVERSIBLE[key]) (next[parts[1]] = next[parts[1]] || []).push({ to: parts[0], key: key, reverse: true });
    });
    var seen = {};
    seen[from] = true;
    var frontier = [{ at: from, hops: [] }];
    while (frontier.length) {
      var item = frontier.shift();
      var out = next[item.at] || [];
      for (var i = 0; i < out.length; i++) {
        var hop = out[i];
        if (seen[hop.to]) continue;
        var hops = item.hops.concat([hop]);
        if (hop.to === to) return hops;
        seen[hop.to] = true;
        frontier.push({ at: hop.to, hops: hops });
      }
    }
    return null;
  }

  function badge(key) {
    var path = hitEls[key];
    if (badgeEls[key]) badgeEls[key].remove();
    var point = path.getPointAtLength(path.getTotalLength() / 2);
    var group = el("g", { "class": "badge on" });
    el("circle", { cx: point.x, cy: point.y, r: 10 }, group);
    label(point.x, point.y + 4, String(step), "", group);
    badgeEls[key] = group;
  }

  function travel(hops, done) {
    if (!hops.length) { done(); return; }
    var hop = hops[0];
    var path = hitEls[hop.key];
    path.classList.add("on");
    if (reduced) {
      if (hop.to !== hops[hops.length - 1].to) setClass(hop.to, "done");
      travel(hops.slice(1), done);
      return;
    }
    var length = path.getTotalLength();
    var speed = Math.max(0.3, 1 - queue.length / 25);
    var duration = Math.min(900, Math.max(220, length * 1.6)) * speed;
    var start = performance.now();
    token.setAttribute("visibility", "visible");
    trail.setAttribute("visibility", "visible");
    (function frame(time) {
      if (paused) { requestAnimationFrame(frame); start += 16; return; }
      var k = Math.min(1, (time - start) / duration);
      var eased = k < 0.5 ? 2 * k * k : 1 - Math.pow(-2 * k + 2, 2) / 2;
      var along = hop.reverse ? 1 - eased : eased;
      var p = path.getPointAtLength(length * along);
      var q = path.getPointAtLength(Math.max(0, Math.min(length, length * along + (hop.reverse ? 14 : -14))));
      token.setAttribute("cx", p.x); token.setAttribute("cy", p.y);
      trail.setAttribute("cx", q.x); trail.setAttribute("cy", q.y);
      if (k < 1) { requestAnimationFrame(frame); return; }
      if (hops.length > 1) setClass(hop.to, "done");
      travel(hops.slice(1), done);
    })(start);
  }

  // A side branch that lights without moving the dot (pushes to the page, the model service, after-tool plugins).
  function side(keys, nodes, value) {
    keys.forEach(function (key) { if (hitEls[key]) hitEls[key].classList.add("on"); });
    nodes.forEach(function (id) { setClass(id, "done"); });
    if (value !== undefined) nodeEls[nodes[nodes.length - 1]].value.textContent = fit(value, NODES[nodes[nodes.length - 1]][2]);
  }

  function arrive(id, message, value) {
    if (current && current !== id && nodeEls[current]) setClass(current, "done");
    setClass(id, "cur");
    nodeEls[id].value.textContent = fit(value || "", NODES[id][2]);
    current = id;
    var t = text();
    var line = (t.stages[message.stage] || message.stage) + (message.name ? " · " + message.name : "") + (message.detail ? " · " + message.detail : "");
    $("now").textContent = "→ " + step + ". " + line;
    if (message.seq !== undefined && message.seq !== null) $("trip").textContent = "· " + (message.name || "") + " · seq " + message.seq;
  }

  function targetOf(message) {
    switch (message.stage) {
      case "INTAKE": return "intake";
      case "INPUT": return "input";
      case "LANE": return "lane";
      case "KERNEL": return "kernel";
      case "JOURNAL": return "journal";
      case "OUTPUT": return "out";
      case "HANDLER_START":
      case "HANDLER_END": return HANDLER[message.name] || "tool";
      case "REMOTE_SEND": return message.name && message.name.indexOf("browser_") === 0 ? "browser" : "files";
      case "REMOTE_DONE": return "links";
      default: return null;
    }
  }

  function play() {
    if (playing || paused) return;
    var message = queue.shift();
    if (!message) return;
    $("empty").hidden = true;
    if (message.stage === "INTAKE") reset();
    if (message.stage === "PUSH") {
      side(["journal>hub", "hub>page", "hub>index"], ["hub", "index", "page"]);
      nodeEls.hub.value.textContent = fit(message.detail || "", NODES.hub[2]);
      play();
      return;
    }
    var target = targetOf(message);
    if (!target) { play(); return; }
    var hops = route(current, target);
    playing = true;
    var finish = function () {
      step += 1;
      if (hops && hops.length) badge(hops[hops.length - 1].key);
      var value = message.detail || message.name || "";
      if (message.stage === "OUTPUT" || message.stage === "HANDLER_START") value = message.name || "";
      arrive(target, message, value);
      if (message.stage === "HANDLER_START" && message.name === "CallModel") side(["model>llm"], ["llm"], text().generating);
      if (message.stage === "HANDLER_END" && message.name === "CallModel") side(["model>llm"], ["llm"], message.detail || "");
      if (message.stage === "HANDLER_END" && target === "tool") side(["tool>after"], ["after"]);
      if (message.stage === "KERNEL" && /AwaitingContext|AwaitingModel/.test(message.detail || "")) side(["kernel>hooks"], ["hooks"]);
      playing = false;
      setTimeout(play, reduced ? 0 : 80);
    };
    if (hops === null) {
      // No drawn path from here (a turn that started before the panel opened): jump.
      hops = [];
      token.setAttribute("visibility", "hidden");
      trail.setAttribute("visibility", "hidden");
      finish();
      return;
    }
    travel(hops, finish);
  }

  function receive(messages) {
    if (paused) {
      queue = queue.concat(messages).slice(-1);
      return;
    }
    queue = queue.concat(messages);
    if (queue.length > 60) {
      var skipped = queue.length - 1;
      var latestIntake = -1;
      for (var i = queue.length - 1; i >= 0; i--) if (queue[i].stage === "INTAKE") { latestIntake = i; break; }
      queue = latestIntake >= 0 ? queue.slice(latestIntake) : queue.slice(-1);
      $("skipped").textContent = text().skipped(skipped - queue.length + 1);
      $("skipped").hidden = false;
      setTimeout(function () { $("skipped").hidden = true; }, 3000);
    }
    play();
  }

  $("pause").addEventListener("click", function () {
    paused = !paused;
    document.body.classList.toggle("paused", paused);
    $("pause").textContent = paused ? text().resume : text().pause;
    if (!paused) play();
  });

  setInterval(function () { $("clock").textContent = new Date().toTimeString().slice(0, 8); }, 500);

  function apply(message) {
    var redraw = !context || context.language !== message.language;
    var sessionChanged = context && context.session !== message.session;
    context = message;
    document.documentElement.dataset.theme = message.theme;
    document.documentElement.lang = message.language;
    if (redraw) draw();
    if (sessionChanged) {
      queue = [];
      reset();
      $("empty").hidden = false;
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
