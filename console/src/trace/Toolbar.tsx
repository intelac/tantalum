// 轨迹工具栏：一键展开或收起所有轮次、所有调用，搜索（Enter 下一个，Shift+Enter 上一个）。窄时换行（DSH TrajectoryToolbar）。

import { ChevronDown, ChevronUp, Search } from "lucide-react";
import { Button } from "../ui/Button";
import { zh } from "../text/zh";
import type { TimelineMode } from "./segments";

const t = zh.trace.toolbar;

interface Props {
  turnsCollapsed: boolean;
  callsCollapsed: boolean;
  onTurns: (collapse: boolean) => void;
  onCalls: (collapse: boolean) => void;
  query: string;
  onQuery: (query: string) => void;
  /** 当前是第几个匹配（从 1 数），没有匹配时为 0。 */
  current: number;
  total: number;
  onJump: (step: 1 | -1) => void;
  /** 时间概览的画法：按实际时长，或每个操作等宽。 */
  mode: TimelineMode;
  onMode: (mode: TimelineMode) => void;
}

export function Toolbar({ turnsCollapsed, callsCollapsed, onTurns, onCalls, query, onQuery, current, total, onJump, mode, onMode }: Props) {
  return (
    <div role="toolbar" aria-label={t.aria} className="flex shrink-0 flex-wrap items-center gap-x-3 gap-y-1.5 border-b border-line px-3 py-1.5 text-xs">
      <span className="flex items-center gap-1">
        <span className="text-fg-faint">{t.turns}</span>
        <Button size="sm" variant="ghost" onClick={() => onTurns(!turnsCollapsed)}>
          {turnsCollapsed ? t.expandTurns : t.collapseTurns}
        </Button>
      </span>
      <span className="flex items-center gap-1">
        <span className="text-fg-faint">{t.calls}</span>
        <Button size="sm" variant="ghost" onClick={() => onCalls(!callsCollapsed)}>
          {callsCollapsed ? t.expandCalls : t.collapseCalls}
        </Button>
      </span>
      <span className="flex items-center gap-1">
        <span className="text-fg-faint">{t.duration}</span>
        <Button size="sm" variant="ghost" aria-pressed={mode === "duration"} onClick={() => onMode(mode === "duration" ? "sequence" : "duration")}>
          {mode === "duration" ? t.useEqualWidth : t.useActualDuration}
        </Button>
      </span>
      <label className="ml-auto flex min-w-[180px] flex-1 items-center gap-1.5 rounded-md border border-line bg-raised px-2 sm:max-w-[320px]">
        <Search size={13} aria-hidden className="shrink-0 text-fg-faint" />
        <input
          type="search"
          value={query}
          aria-label={t.search}
          placeholder={t.searchPlaceholder}
          onChange={(event) => onQuery(event.target.value)}
          onKeyDown={(event) => {
            if (event.key !== "Enter") return;
            event.preventDefault();
            onJump(event.shiftKey ? -1 : 1);
          }}
          className="h-7 min-w-0 flex-1 bg-transparent text-xs text-fg outline-none placeholder:text-fg-faint"
        />
        {query.trim() !== "" && (
          <>
            <span className="shrink-0 font-mono text-[11px] text-fg-faint" aria-live="polite">
              {t.matches(current, total)}
            </span>
            <button type="button" aria-label={t.previousMatch} disabled={total === 0} onClick={() => onJump(-1)} className="text-fg-faint hover:text-fg disabled:opacity-40">
              <ChevronUp size={13} aria-hidden />
            </button>
            <button type="button" aria-label={t.nextMatch} disabled={total === 0} onClick={() => onJump(1)} className="text-fg-faint hover:text-fg disabled:opacity-40">
              <ChevronDown size={13} aria-hidden />
            </button>
          </>
        )}
      </label>
    </div>
  );
}
