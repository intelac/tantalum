import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { useEffect, useState } from "react";
import { getTrace, listRuns } from "../api/client";
import { navigate, tracePath } from "../app/router";
import { useNow } from "../process/useNow";
import type { ConversationStore } from "../store/ConversationStore";
import { useItem } from "../store/hooks";
import { zh } from "../text/zh";
import { EventList } from "./EventList";
import { ModelCallDetail } from "./ModelCallDetail";
import { TimeBars } from "./TimeBars";

interface Props {
  store: ConversationStore;
  session: string;
  /** 地址栏里的运行；没有时用最近的一次。 */
  run?: string;
}

/** 轨迹页：工具条选运行，三行时间条，事件列表；选中模型调用后在下方看完整输入输出。地址栏能定位到运行。 */
export function TracePage({ store, session, run }: Props) {
  const runs = useQuery({ queryKey: ["runs", session], queryFn: () => listRuns(session), select: (data) => data.runs });
  const list = runs.data ?? [];
  const current = run ?? list[list.length - 1]?.run;
  const status = useItem(store, current === undefined ? "" : `run:${current}`);
  const live = status?.kind === "run" ? `${status.phase}@${status.updatedAt}` : "";
  const ended = status?.kind === "run" ? status.endReason !== undefined : (list.find((entry) => entry.run === current)?.endReason ?? null) !== null;
  const now = useNow(!ended);
  const [selectedTurn, setSelectedTurn] = useState<number | null>(null);

  useEffect(() => {
    setSelectedTurn(null);
  }, [current]);

  const trace = useQuery({
    queryKey: ["trace", current, live],
    // 运行状态变化换了缓存键时沿用上一份数据，不闪成空白。
    placeholderData: keepPreviousData,
    enabled: current !== undefined,
    queryFn: () => getTrace(current as string),
  });

  if (runs.isPending) return <p className="p-4 text-sm text-fg-faint">{zh.common.loading}</p>;
  if (current === undefined) {
    return (
      <div className="flex h-full items-center justify-center px-4 text-sm text-fg-faint">{zh.trace.empty}</div>
    );
  }

  return (
    <div className="flex h-full min-h-0 flex-col">
      <div className="flex h-10 shrink-0 items-center gap-3 border-b border-line px-3 text-xs">
        <label className="flex items-center gap-1.5 text-fg-muted">
          {zh.trace.run}
          <select
            value={current}
            onChange={(event) => navigate(tracePath(session, event.target.value))}
            className="h-7 rounded-sm border border-line bg-raised px-1.5 font-mono text-xs text-fg"
          >
            {list.map((entry) => (
              <option key={entry.run} value={entry.run}>
                {entry.run}
                {entry.endReason === undefined ? ` · ${zh.trace.ongoing}` : ""}
              </option>
            ))}
            {!list.some((entry) => entry.run === current) && <option value={current}>{current}</option>}
          </select>
        </label>
        <span className="hidden text-fg-faint sm:inline">{zh.trace.selectHint}</span>
      </div>
      <div className="min-h-0 flex-1 overflow-y-auto">
        <div className="px-3">
          {trace.isError && (
            <p role="alert" className="py-2 text-sm text-danger">
              {trace.error.message}
            </p>
          )}
          {trace.data !== undefined && (
            <>
              <TimeBars trace={trace.data} now={now} />
              <EventList trace={trace.data} selectedTurn={selectedTurn} onSelectTurn={setSelectedTurn} />
            </>
          )}
        </div>
        {selectedTurn !== null && (
          <div className="mt-2 border-t border-line">
            <ModelCallDetail run={current} turn={selectedTurn} refreshKey={live} />
          </div>
        )}
      </div>
    </div>
  );
}
