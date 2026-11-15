// 面板区（plugin-panel 需求 2）：页面 | 拖动条 | 面板。面板有标题栏（名字、放大/还原、关闭），Esc 关闭；
// 宽度可以拖（键盘用方向键），记在浏览器里；900px 以下面板从底部升起，没有拖动条。放大时页面让开。

import { Activity, ChartBar, Gauge, ListTree, Maximize2, Minimize2, PanelRight, X } from "lucide-react";
import { useEffect, useRef, useState, type KeyboardEvent, type PointerEvent, type ReactNode } from "react";
import type { PanelInfo } from "../api/types";
import { useLanguage, useText } from "../text";
import { pick } from "../text/localText";
import type { TraceStore } from "../trace/TraceStore";
import { Button } from "../ui/Button";
import { cx } from "../ui/cx";
import { PanelFrame } from "./PanelFrame";
import { clampWidth, MIN_WIDTH, readWidth, saveWidth } from "./usePanels";

const ICONS = { activity: Activity, "chart-bar": ChartBar, gauge: Gauge, "list-tree": ListTree, "panel-right": PanelRight } as const;

export function PanelIcon({ name, size = 16 }: { name: string; size?: number }) {
  const Icon = ICONS[name as keyof typeof ICONS] ?? PanelRight;
  return <Icon size={size} aria-hidden />;
}

function useNarrow(): boolean {
  const query = "(max-width: 899px)";
  const [narrow, setNarrow] = useState(() => typeof window.matchMedia === "function" && window.matchMedia(query).matches);
  useEffect(() => {
    if (typeof window.matchMedia !== "function") return;
    const media = window.matchMedia(query);
    const onChange = () => setNarrow(media.matches);
    media.addEventListener("change", onChange);
    return () => media.removeEventListener("change", onChange);
  }, []);
  return narrow;
}

interface Props {
  children: ReactNode;
  panel: PanelInfo | undefined;
  session: string;
  run?: string;
  trace: TraceStore;
  maximized: boolean;
  onMaximize: (maximized: boolean) => void;
  onClose: () => void;
}

export function PanelLayout({ children, panel, session, run, trace, maximized, onMaximize, onClose }: Props) {
  const t = useText().panel;
  const language = useLanguage();
  const narrow = useNarrow();
  const container = useRef<HTMLDivElement>(null);
  const [width, setWidth] = useState(readWidth);
  const dragging = useRef(false);

  const resize = (next: number) => {
    const total = container.current?.clientWidth ?? window.innerWidth;
    const clamped = clampWidth(next, total);
    setWidth(clamped);
    saveWidth(clamped);
  };
  const onPointerDown = (event: PointerEvent<HTMLDivElement>) => {
    dragging.current = true;
    event.currentTarget.setPointerCapture(event.pointerId);
  };
  const onPointerMove = (event: PointerEvent<HTMLDivElement>) => {
    if (!dragging.current || container.current === null) return;
    const right = container.current.getBoundingClientRect().right;
    resize(right - event.clientX);
  };
  const onSeparatorKey = (event: KeyboardEvent<HTMLDivElement>) => {
    if (event.key === "ArrowLeft") resize(width + 24);
    else if (event.key === "ArrowRight") resize(width - 24);
    else return;
    event.preventDefault();
  };
  const onPanelKey = (event: KeyboardEvent<HTMLElement>) => {
    if (event.key === "Escape") {
      event.stopPropagation();
      onClose();
    }
  };

  if (panel === undefined) return <div className="h-full min-h-0">{children}</div>;
  const title = pick(panel.title, language);

  const section = (
    <section
      aria-label={t.region(title)}
      onKeyDown={onPanelKey}
      className={cx(
        "flex min-h-0 flex-col bg-canvas",
        maximized ? "absolute inset-0 z-20" : narrow ? "absolute inset-x-0 bottom-0 z-20 h-[70%] rounded-t-lg border-t border-line shadow-lg" : "shrink-0 border-l border-line",
      )}
      style={!maximized && !narrow ? { width } : undefined}
    >
      <header className="flex h-10 shrink-0 items-center gap-2 border-b border-line px-3">
        <PanelIcon name={panel.icon} />
        <h2 className="min-w-0 flex-1 truncate text-sm font-medium">{title}</h2>
        {!narrow && (
          <Button variant="icon" size="sm" aria-label={maximized ? t.restore : t.maximize} title={maximized ? t.restore : t.maximize} onClick={() => onMaximize(!maximized)}>
            {maximized ? <Minimize2 size={14} /> : <Maximize2 size={14} />}
          </Button>
        )}
        <Button variant="icon" size="sm" aria-label={t.close} title={t.close} onClick={onClose}>
          <X size={14} />
        </Button>
      </header>
      <PanelFrame panel={panel} title={title} session={session} run={run} trace={trace} />
    </section>
  );

  return (
    <div ref={container} className="relative flex h-full min-h-0">
      <div className="min-h-0 min-w-0 flex-1" hidden={maximized}>
        {children}
      </div>
      {!maximized && !narrow && (
        <div
          role="separator"
          aria-orientation="vertical"
          aria-label={t.resize}
          aria-valuemin={MIN_WIDTH}
          aria-valuenow={width}
          tabIndex={0}
          onPointerDown={onPointerDown}
          onPointerMove={onPointerMove}
          onPointerUp={() => (dragging.current = false)}
          onKeyDown={onSeparatorKey}
          className="w-1 shrink-0 cursor-col-resize bg-transparent hover:bg-line-strong focus-visible:bg-focus"
        />
      )}
      {section}
    </div>
  );
}
