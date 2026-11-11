import { useEffect, useId, useLayoutEffect, useRef, useState, type KeyboardEvent } from "react";
import type { ContextUsage } from "../api/types";
import { useText } from "../text";
import { cx } from "../ui/cx";
import { grouped, meterParts, type MeterPart } from "./status";

interface Props {
  /** 状态行上的字：“上下文 46%”。 */
  label: string;
  percent: number;
  /** 快到自动压缩的点了：变色，悬停说明。 */
  warn: boolean;
  hint?: string;
  usage: ContextUsage;
  /** 百分比按它算的窗口。 */
  window: number;
}

/** 弹窗离屏幕边至少留这么多。 */
const EDGE = 8;

const COLORS: Record<MeterPart["key"], string> = { system: "bg-accent", tools: "bg-warning", messages: "bg-success" };

/**
 * 状态行的上下文百分比，点开是明细：用了百分之几、约 已用 / 窗口、一条分三段的条和三行图例（照 DSH ContextMeter）。
 * 三项是估算摊出来的，所以都写“约”。Esc 或点外面关掉。
 */
export function ContextMeter({ label, percent, warn, hint, usage, window }: Props) {
  const t = useText();
  const [open, setOpen] = useState(false);
  const root = useRef<HTMLSpanElement>(null);
  const trigger = useRef<HTMLButtonElement>(null);
  const panel = useRef<HTMLDivElement>(null);
  const [shift, setShift] = useState(0);
  const panelId = useId();
  const parts = meterParts(usage);

  // 弹窗以按钮为中心；窄屏上按钮靠边，会伸出屏幕，量一下再挪回来。
  useLayoutEffect(() => {
    if (!open || panel.current === null) {
      setShift(0);
      return;
    }
    // 关着的时候 shift 是 0，所以这里量到的就是没挪过的位置。
    const { left, right } = panel.current.getBoundingClientRect();
    const width = document.documentElement.clientWidth;
    if (left < EDGE) setShift(EDGE - left);
    else if (right > width - EDGE) setShift(width - EDGE - right);
  }, [open]);

  useEffect(() => {
    if (!open) return;
    function onPointerDown(event: PointerEvent): void {
      if (root.current !== null && !root.current.contains(event.target as Node)) setOpen(false);
    }
    document.addEventListener("pointerdown", onPointerDown);
    return () => document.removeEventListener("pointerdown", onPointerDown);
  }, [open]);

  function onKeyDown(event: KeyboardEvent<HTMLSpanElement>): void {
    if (event.key === "Escape" && open) {
      event.preventDefault();
      setOpen(false);
      trigger.current?.focus();
    }
  }

  return (
    <span ref={root} className="relative" onKeyDown={onKeyDown}>
      <button
        ref={trigger}
        type="button"
        aria-haspopup="dialog"
        aria-expanded={open}
        aria-controls={open ? panelId : undefined}
        title={hint}
        onClick={() => setOpen((current) => !current)}
        className={cx("rounded-xs px-1 hover:bg-hover hover:text-fg", warn ? "font-medium text-danger" : "")}
      >
        {label}
      </button>
      {open && (
        <div
          ref={panel}
          id={panelId}
          role="dialog"
          aria-label={t.status.meter.title(percent)}
          style={{ transform: `translateX(calc(-50% + ${shift}px))` }}
          className="absolute bottom-full left-1/2 z-40 mb-2 w-[min(16rem,calc(100vw-2rem))] rounded-md border border-line bg-raised p-3 text-left text-xs text-fg shadow-lg"
        >
          <p className="font-medium">{t.status.meter.title(percent)}</p>
          <p className="mt-0.5 text-fg-muted">{t.status.meter.amount(grouped(usage.used ?? 0), grouped(window))}</p>
          {parts.length > 0 && (
            <>
              <div className="mt-2 flex h-1.5 overflow-hidden rounded-full bg-line" aria-hidden="true">
                {parts.map((part) => (
                  <span key={part.key} className={COLORS[part.key]} style={{ width: `${(part.share * percent) / 100}%` }} />
                ))}
              </div>
              <ul className="mt-2 space-y-1">
                {parts.map((part) => (
                  <li key={part.key} className="flex items-center gap-2">
                    <span className={cx("h-2 w-2 shrink-0 rounded-full", COLORS[part.key])} aria-hidden="true" />
                    <span className="flex-1 text-fg-muted">{t.status.meter[part.key]}</span>
                    <span className="tabular-nums">{t.status.meter.about(grouped(part.tokens))}</span>
                  </li>
                ))}
              </ul>
            </>
          )}
        </div>
      )}
    </span>
  );
}
