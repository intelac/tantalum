// Tantalum panel bridge helper, protocol v1 (docs/panels.md). Copy this file into your panel.
// The panel runs in a sandboxed iframe: it cannot read the console's cookies or call APIs itself;
// everything goes through postMessage to the console, which answers only a fixed set of read requests.
(function () {
  "use strict";
  var handlers = {};
  var pending = {};
  var next = 1;

  function send(message) {
    message.tantalum = 1;
    window.parent.postMessage(message, "*");
  }

  window.addEventListener("message", function (event) {
    if (event.source !== window.parent) return;
    var message = event.data;
    if (!message || message.tantalum !== 1) return;
    if (message.type === "result") {
      var waiting = pending[message.id];
      if (!waiting) return;
      delete pending[message.id];
      if (message.ok) waiting.resolve(message.body);
      else waiting.reject(Object.assign(new Error(message.error || "request failed"), { status: message.status }));
      return;
    }
    (handlers[message.type] || []).forEach(function (handler) {
      try {
        handler(message);
      } catch (failure) {
        send({ type: "error", message: String(failure && failure.message || failure) });
      }
    });
  });

  window.addEventListener("error", function (event) {
    send({ type: "error", message: String(event.message || "error") });
  });

  window.tantalum = {
    /** Call once your handlers are registered; the console answers with "init". */
    ready: function () { send({ type: "ready" }); },
    /** what: "tree" | "trace" | "runs" | "summary"; returns a Promise of the response body. */
    get: function (what, params) {
      var id = next++;
      return new Promise(function (resolve, reject) {
        pending[id] = { resolve: resolve, reject: reject };
        send({ type: "get", id: id, what: what, params: params || {} });
      });
    },
    /** what: "trace" (default) for live trace records, "monitor" for live pipeline stages; they arrive as messages of that type. */
    subscribe: function (what) { send({ type: "subscribe", what: what || "trace" }); },
    /** "init" | "context" | "trace" | "monitor" */
    on: function (type, handler) { (handlers[type] = handlers[type] || []).push(handler); },
    /** target: { run: "<run id>" } or { record: "<trace record id>" } */
    open: function (target) { send({ type: "open", target: target }); },
    error: function (message) { send({ type: "error", message: String(message) }); }
  };
})();
