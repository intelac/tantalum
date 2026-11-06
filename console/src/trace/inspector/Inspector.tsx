// 检查器外壳：可拖宽（双击恢复默认，键盘左右每次 16 px，宽度记在本机）、关闭按钮和 Esc 关闭；按记录类型选页签。
// 宽度参数照 DSH（DETAILS_MIN_WIDTH、DETAILS_MAX_WIDTH、TABLE_MIN_WIDTH、DETAILS_RESIZE_STEP，`.details` 的默认宽度）。
// 窄屏时改成覆盖记录表的整屏面板，不能拖宽。

import { X } from "lucide-react";
import { useEffect, useRef, useState, type KeyboardEvent, type PointerEvent } from "react";
import type { TraceRecord } from "../../api/types";
import { useText } from "../../text";
import { Button } from "../../ui/Button";
import { cx } from "../../ui/cx";
import { localArea, readStored, writeStored } from "../../ui/storage";
import { RecordTabs } from "./RecordTabs";
import { RequestTabs } from "./RequestTabs";
import { SystemTabs } from "./SystemTabs";
import { ToolTabs } from "./ToolTabs";

export const DETAILS_MIN_WIDTH = 320;
export const DETAILS_MAX_WIDTH = 720;
export const TABLE_MIN_WIDTH = 280;
export const DETAILS_RESIZE_STEP = 16;
const WIDTH_KEY = "tantalum.trace.inspectorWidth";

/** 默认 `clamp(320px, 38%, 440px)`。 */
export function defaultWidth(container: number): number {
  return Math.min(440, Math.max(DETAILS_MIN_WIDTH, container * 0.38));
}

/** 宽度限制在 320–720 px，并给记录表留至少 280 px。 */
export function clampWidth(width: number, container: number): number {
  const max = Math.max(DETAILS_MIN_WIDTH, Math.min(DETAILS_MAX_WIDTH, container - TABLE_MIN_WIDTH));
  return Math.round(Math.min(max, Math.max(DETAILS_MIN_WIDTH, width)));
}

function storedWidth(): number | null {
  const raw = Number(readStored(WIDTH_KEY, localArea()));
  return Number.isFinite(raw) && raw > 0 ? raw : null;
}

interface Props {
  session: string;
  record: TraceRecord;
  /** 同一会话里的记录，用来找工具所属的请求。 */
  lookup: (id: string) => TraceRecord | undefined;
  /** 页面宽度，决定默认宽度和上限。 */
  containerWidth: number;
  overlay: boolean;
  onClose: () => void;
  onOpen: (id: string) => void;
}

export function Inspector({ session, record, lookup, containerWidth, overlay, onClose, onOpen }: Props) {
  const t = useText().trace;
  const [chosen, setChosen] = useState<number | null>(storedWidth);
  const drag = useRef<{ x: number; width: number } | null>(null);
  const width = clampWidth(chosen ?? defaultWidth(containerWidth), containerWidth);

  useEffect(() => {
    const onKey = (event: globalThis.KeyboardEvent) => {
      if (event.key === "Escape") onClose();
    };
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [onClose]);

  function choose(next: number | null): void {
    setChosen(next);
    writeStored(WIDTH_KEY, next === null ? "" : String(next), localArea());
  }

  function onPointerDown(event: PointerEvent<HTMLDivElement>): void {
    drag.current = { x: event.clientX, width };
    event.currentTarget.setPointerCapture(event.pointerId);
  }

  function onPointerMove(event: PointerEvent<HTMLDivElement>): void {
    if (drag.current === null) return;
    // 把手在左边：往左拖变宽。
    setChosen(clampWidth(drag.current.width + drag.current.x - event.clientX, containerWidth));
  }

  function onPointerUp(): void {
    if (drag.current === null) return;
    drag.current = null;
    choose(width);
  }

  function onHandleKey(event: KeyboardEvent<HTMLDivElement>): void {
    if (event.key !== "ArrowLeft" && event.key !== "ArrowRight") return;
    event.preventDefault();
    choose(clampWidth(width + (event.key === "ArrowLeft" ? DETAILS_RESIZE_STEP : -DETAILS_RESIZE_STEP), containerWidth));
  }

  const body = (() => {
    switch (record.kind) {
      case "request":
        return <RequestTabs session={session} request={record} />;
      case "tool":
        return <ToolTabs session={session} tool={record} request={record.request === undefined ? undefined : lookup(record.request)} />;
      case "system":
        return <SystemTabs session={session} system={record} />;
      default:
        return <RecordTabs record={record} onOpen={onOpen} />;
    }
  })();

  return (
    <aside
      aria-label={t.details.event}
      className={cx("flex min-h-0 flex-col bg-canvas", overlay ? "absolute inset-0 z-20" : "relative shrink-0 border-l border-line")}
      style={overlay ? undefined : { width }}
    >
      {!overlay && (
        <div
          role="separator"
          aria-orientation="vertical"
          aria-label={t.details.resize}
          aria-valuenow={width}
          aria-valuemin={DETAILS_MIN_WIDTH}
          aria-valuemax={DETAILS_MAX_WIDTH}
          title={t.details.resizeTitle}
          tabIndex={0}
          onPointerDown={onPointerDown}
          onPointerMove={onPointerMove}
          onPointerUp={onPointerUp}
          onDoubleClick={() => choose(null)}
          onKeyDown={onHandleKey}
          className="absolute top-0 bottom-0 -left-1 z-10 w-2 cursor-col-resize hover:bg-accent/30 focus-visible:bg-accent/40 focus-visible:outline-none"
        />
      )}
      <div className="flex h-10 shrink-0 items-center gap-2 border-b border-line px-3">
        <span className="min-w-0 flex-1 truncate text-sm font-medium">
          {t.kind[record.kind] ?? record.kind}
          <span className="ml-2 font-mono text-xs font-normal text-fg-faint">{record.id}</span>
        </span>
        <Button variant="icon" size="sm" aria-label={t.details.close} title={t.details.close} onClick={onClose}>
          <X size={14} aria-hidden />
        </Button>
      </div>
      {/* 换记录时回到第一个页签。 */}
      <div key={record.id} className="flex min-h-0 flex-1 flex-col">
        {body}
      </div>
    </aside>
  );
}
