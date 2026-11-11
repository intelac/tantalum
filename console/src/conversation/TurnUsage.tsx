import { Database } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import type { TokenUsage } from "../api/types";
import { useText } from "../text";
import { formatNumber } from "../text/format";
import { cacheHit, compact, total } from "./usage";

interface Props {
  usage: TokenUsage;
}

/** 回答下面的“用量 12.2K”标记，点开是这一轮的明细（input-area 需求 5；DSH `TurnUsagePanel.tsx`）。Esc 或点外面关上。 */
export function TurnUsage({ usage }: Props) {
  const t = useText();
  const [open, setOpen] = useState(false);
  const root = useRef<HTMLSpanElement>(null);
  const trigger = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    if (!open) return;
    function onPointerDown(event: PointerEvent): void {
      if (root.current !== null && !root.current.contains(event.target as Node)) setOpen(false);
    }
    document.addEventListener("pointerdown", onPointerDown);
    return () => document.removeEventListener("pointerdown", onPointerDown);
  }, [open]);

  const hit = cacheHit(usage);
  const text = t.conversation.usage;
  return (
    <span
      ref={root}
      className="relative"
      onKeyDown={(event) => {
        if (event.key !== "Escape" || !open) return;
        event.stopPropagation();
        setOpen(false);
        trigger.current?.focus();
      }}
    >
      <button
        ref={trigger}
        type="button"
        aria-haspopup="dialog"
        aria-expanded={open}
        onClick={() => setOpen(!open)}
        className="flex items-center gap-1 rounded-xs px-1 py-0.5 hover:bg-hover hover:text-fg"
      >
        <Database size={12} aria-hidden="true" />
        {text.chip(compact(total(usage)))}
      </button>
      {open && (
        <div role="dialog" aria-label={text.title} className="absolute bottom-full left-0 z-20 mb-1 w-56 rounded-md border border-line bg-surface p-3 text-xs text-fg shadow-lg">
          <div className="flex items-baseline justify-between gap-3 border-b border-line pb-2 font-medium">
            <span>{text.title}</span>
            <span className="tabular-nums">{formatNumber(total(usage))}</span>
          </div>
          <dl className="mt-2 grid grid-cols-[1fr_auto] gap-x-3 gap-y-1 text-fg-muted">
            {hit !== null && (
              <>
                <dt>{text.cacheHit}</dt>
                <dd className="text-right tabular-nums">{`${hit}%`}</dd>
              </>
            )}
            <dt>{text.input}</dt>
            <dd className="text-right tabular-nums">{formatNumber(usage.input)}</dd>
            {usage.cacheRead > 0 && (
              <>
                <dt>{text.cacheRead}</dt>
                <dd className="text-right tabular-nums">{formatNumber(usage.cacheRead)}</dd>
              </>
            )}
            {usage.cacheWrite > 0 && (
              <>
                <dt>{text.cacheWrite}</dt>
                <dd className="text-right tabular-nums">{formatNumber(usage.cacheWrite)}</dd>
              </>
            )}
            <dt>{text.output}</dt>
            <dd className="text-right tabular-nums">{formatNumber(usage.output)}</dd>
          </dl>
        </div>
      )}
    </span>
  );
}
