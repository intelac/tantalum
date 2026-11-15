// 插件面板的 iframe（plugin-panel 需求 2.6、3）：sandbox 只给 allow-scripts（不同源，读不到 cookie，改不了上层地址），
// 宿主这一侧的消息桥接在这里。10 秒没收到 ready 就显示加载失败和重试；面板报的错显示在面板上方。

import { useEffect, useRef, useState } from "react";
import { getRunTree, getRunsSummary, getTracePage, listAllRuns } from "../api/client";
import { useViewedUser } from "../api/viewing";
import { navigate, runPath, traceRecordPath } from "../app/router";
import type { PanelInfo } from "../api/types";
import { useLanguage, useText } from "../text";
import type { TraceStore } from "../trace/TraceStore";
import { Button } from "../ui/Button";
import { browserZone } from "../observe/filters";
import { PanelBridge } from "./bridge";
import type { PanelContext } from "./protocol";

export const READY_TIMEOUT_MS = 10_000;

/** 现在的亮/暗：跟着根元素的 data-theme 变。 */
function useResolvedTheme(): "light" | "dark" {
  const read = () => (document.documentElement.dataset.theme === "dark" ? "dark" : "light");
  const [theme, setTheme] = useState<"light" | "dark">(read);
  useEffect(() => {
    const observer = new MutationObserver(() => setTheme(read()));
    observer.observe(document.documentElement, { attributes: true, attributeFilter: ["data-theme"] });
    return () => observer.disconnect();
  }, []);
  return theme;
}

interface Props {
  panel: PanelInfo;
  title: string;
  session: string;
  run?: string;
  trace: TraceStore;
}

export function PanelFrame({ panel, title, session, run, trace }: Props) {
  const t = useText().panel;
  const frame = useRef<HTMLIFrameElement>(null);
  const language = useLanguage();
  const theme = useResolvedTheme();
  const readOnly = useViewedUser() !== null;
  const [attempt, setAttempt] = useState(0);
  const [ready, setReady] = useState(false);
  const [timedOut, setTimedOut] = useState(false);
  const [reported, setReported] = useState<string | null>(null);
  const context = useRef<PanelContext>({ session, run, theme, language, readOnly });
  context.current = { session, ...(run === undefined ? {} : { run }), theme, language, readOnly };

  // 桥在 effect 里建、在清理时作废：换面板、重试、开发模式下 effect 跑两遍时都是一个新的桥
  const bridge = useRef<PanelBridge | null>(null);
  useEffect(() => {
    const created = new PanelBridge({
      frame: () => frame.current?.contentWindow ?? null,
      context: () => context.current,
      api: {
        tree: (s, r) => getRunTree(s, r),
        trace: (s, cursor) => getTracePage(s, cursor),
        runs: (query) => listAllRuns(query),
        summary: (query) => getRunsSummary(query),
      },
      subscribeLive: (s, listener) => trace.subscribeLive(s, listener),
      navigate: (target, s) => navigate("run" in target ? runPath(s, target.run) : traceRecordPath(s, target.record)),
      onReady: () => setReady(true),
      onError: (message) => setReported(message),
      zone: browserZone,
    });
    bridge.current = created;
    const listener = (event: MessageEvent) => created.receive(event);
    window.addEventListener("message", listener);
    return () => {
      window.removeEventListener("message", listener);
      created.dispose();
      if (bridge.current === created) bridge.current = null;
    };
  }, [panel.url, attempt, trace]);

  useEffect(() => {
    bridge.current?.contextChanged();
  }, [session, run, theme, language, readOnly]);

  useEffect(() => {
    setReady(false);
    setTimedOut(false);
    setReported(null);
    const timer = window.setTimeout(() => setTimedOut(true), READY_TIMEOUT_MS);
    return () => window.clearTimeout(timer);
  }, [panel.url, attempt]);

  return (
    <div className="flex h-full min-h-0 flex-col">
      {reported !== null && (
        <p role="alert" className="border-b border-line bg-danger-soft px-3 py-1 text-xs text-danger">
          {t.reported(reported)}
        </p>
      )}
      {timedOut && !ready && (
        <div role="alert" className="flex items-center gap-3 border-b border-line px-3 py-2 text-xs text-danger">
          <span className="min-w-0 flex-1">{t.failed}</span>
          <Button size="sm" onClick={() => setAttempt((value) => value + 1)}>
            {t.retry}
          </Button>
        </div>
      )}
      <iframe
        key={`${panel.url}#${attempt}`}
        ref={frame}
        title={title}
        src={panel.url}
        sandbox="allow-scripts"
        referrerPolicy="no-referrer"
        className="min-h-0 w-full flex-1 border-0 bg-canvas"
      />
    </div>
  );
}
