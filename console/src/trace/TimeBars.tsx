import type { Trace } from "../api/types";
import { formatDuration } from "../text/time";
import { zh } from "../text/zh";
import { cx } from "../ui/cx";
import { layoutBars, type BarRow } from "./layout";

interface Props {
  trace: Trace;
  now: number;
}

const ROWS: { row: BarRow; label: string; color: string }[] = [
  { row: "input", label: zh.trace.input, color: "bg-bar-input" },
  { row: "model", label: zh.trace.model, color: "bg-bar-model" },
  { row: "tool", label: zh.trace.tool, color: "bg-bar-tool" },
];

/** 三行彩色时间条：输入、模型、工具。每段按占总时长的比例定位，纯 CSS。 */
export function TimeBars({ trace, now }: Props) {
  const bars = layoutBars(trace, now);
  return (
    <div className="flex flex-col gap-1 py-2" role="img" aria-label={zh.trace.title}>
      {ROWS.map(({ row, label, color }) => (
        <div key={row} className="flex h-4 items-center gap-2">
          <span className="w-8 shrink-0 text-right text-[11px] text-fg-faint">{label}</span>
          <div className="relative h-3 flex-1 rounded-xs bg-muted">
            {bars
              .filter((bar) => bar.row === row)
              .map((bar, index) => (
                <div
                  key={index}
                  className={cx("absolute top-0 h-full rounded-xs", color, bar.open && "animate-pulse")}
                  style={{ left: `${bar.left}%`, width: `${bar.width}%` }}
                  title={`${zh.trace.handlers[bar.handler] ?? bar.handler} · ${bar.open ? zh.trace.ongoing : formatDuration(bar.durationMs)}`}
                />
              ))}
          </div>
        </div>
      ))}
    </div>
  );
}
