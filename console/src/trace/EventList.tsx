import type { Trace, TraceEntry } from "../api/types";
import { tokenCount } from "../composer/status";
import { zh } from "../text/zh";
import { cx } from "../ui/cx";
import { offsetSeconds } from "./layout";

interface Props {
  trace: Trace;
  selectedTurn: number | null;
  onSelectTurn: (turn: number) => void;
}

const TAG_COLORS: Record<string, string> = {
  UserPrompt: "bg-bar-input/30 text-fg",
  SteerSubmitted: "bg-bar-input/30 text-fg",
  FollowUpSubmitted: "bg-bar-input/30 text-fg",
  ContextAssembled: "bg-bar-input/30 text-fg",
  ModelReplied: "bg-bar-model/30 text-fg",
  ModelInterrupted: "bg-bar-model/30 text-fg",
  TimerFired: "bg-bar-model/30 text-fg",
  ToolFinished: "bg-bar-tool/30 text-fg",
  WorkDone: "bg-bar-tool/30 text-fg",
  ModelFailed: "bg-danger-soft text-danger",
  CommandDropped: "bg-danger-soft text-danger",
  Cancel: "bg-muted text-fg-muted",
};

export function tagOf(entry: TraceEntry): string {
  return zh.trace.types[entry.type] ?? entry.type;
}

/** 事件列表：相对时间、类型标签、之后的状态和处理方；带模型调用的事件可点，查看完整输入输出。 */
export function EventList({ trace, selectedTurn, onSelectTurn }: Props) {
  const first = trace.events[0];
  const start = first === undefined ? 0 : new Date(first.at).getTime();
  return (
    <ol className="flex flex-col" aria-label={zh.trace.events}>
      {trace.events.map((entry, index) => {
        const segment = trace.segments[index];
        const clickable = entry.turn !== undefined;
        const selected = clickable && entry.turn === selectedTurn;
        const row = (
          <>
            <span className="w-14 shrink-0 font-mono text-[11px] text-fg-faint">{zh.trace.at(offsetSeconds(entry.at, start))}</span>
            <span className={cx("w-12 shrink-0 rounded-xs px-1.5 py-0.5 text-center text-[11px]", TAG_COLORS[entry.type] ?? "bg-muted text-fg-muted")}>
              {tagOf(entry)}
            </span>
            <span className="min-w-0 flex-1 truncate text-sm text-fg">
              {entry.type}
              {clickable && <span className="ml-2 text-xs text-accent">{zh.trace.turn(entry.turn as number)}</span>}
              {entry.compaction !== undefined && (
                <span className={cx("ml-2 rounded-xs px-1.5 py-0.5 text-xs", entry.compaction.ok ? "bg-accent-soft text-accent" : "bg-danger-soft text-danger")}>
                  {entry.compaction.ok
                    ? zh.trace.compaction(tokenCount(entry.compaction.estimateBefore), tokenCount(entry.compaction.estimateAfter))
                    : zh.trace.compactionFailed(entry.compaction.reason ?? "")}
                </span>
              )}
            </span>
            <span className="hidden shrink-0 text-xs text-fg-faint sm:inline" title={zh.trace.phase}>
              {entry.phase}
            </span>
            <span className="hidden w-20 shrink-0 text-right text-xs text-fg-muted sm:inline" title={zh.trace.handler}>
              {segment === undefined ? "" : (zh.trace.handlers[segment.handler] ?? segment.handler)}
            </span>
          </>
        );
        const className = cx(
          "flex h-9 w-full items-center gap-2 border-b border-line px-2 text-left",
          selected ? "bg-accent-soft" : clickable ? "hover:bg-hover" : "",
        );
        return (
          <li key={entry.seq}>
            {clickable ? (
              <button type="button" aria-pressed={selected} onClick={() => onSelectTurn(entry.turn as number)} className={className}>
                {row}
              </button>
            ) : (
              <div className={className}>{row}</div>
            )}
          </li>
        );
      })}
    </ol>
  );
}
