// 轨迹页：工具栏、记录表、检查器。页面只有界面状态（收起的轮和调用、搜索词、选中、定位），记录在 TraceStore。
// 换会话时由调用方用 `key` 重建，界面状态随之清空。

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useText } from "../text";
import { useLatestTrace, useTracePaging, useTraceRecords } from "./hooks";
import { buildLedger, expansionFor, focusRows } from "./rows";
import { Inspector } from "./inspector/Inspector";
import { Ledger } from "./Ledger";
import { matchRecords } from "./search";
import { localArea, readStored, writeStored } from "../ui/storage";
import { Timeline } from "./Timeline";
import { buildTimeline, spansInRange, type TimelineMode, type TimeRange } from "./segments";
import { Toolbar } from "./Toolbar";
import type { TraceStore } from "./TraceStore";

interface Props {
  store: TraceStore;
  session: string;
  /** 地址里的运行：一次取到包含它的页，滚到那一轮的标题。 */
  run?: string;
  /** 地址里的记录：展开所在的轮和调用，滚到并选中。 */
  record?: string;
}

const NONE: ReadonlySet<string> = new Set();
const MODE_KEY = "tantalum.trace.timelineMode";

function storedMode(): TimelineMode {
  return readStored(MODE_KEY, localArea()) === "sequence" ? "sequence" : "duration";
}
/** 比这窄时检查器盖在记录表上（手机 390 px）。 */
const OVERLAY_WIDTH_PX = 640;

function without(set: ReadonlySet<string>, remove: ReadonlySet<string>): ReadonlySet<string> {
  if (![...remove].some((id) => set.has(id))) return set;
  return new Set([...set].filter((id) => !remove.has(id)));
}

function toggled(set: ReadonlySet<string>, id: string): ReadonlySet<string> {
  const next = new Set(set);
  if (next.has(id)) next.delete(id);
  else next.add(id);
  return next;
}

export function TracePage({ store, session, run, record }: Props) {
  const t = useText().trace;
  const records = useTraceRecords(store, session);
  const paging = useTracePaging(store, session);
  const error = useLatestTrace(store, session, run);
  const [collapsedTurns, setCollapsedTurns] = useState<ReadonlySet<string>>(NONE);
  const [collapsedCalls, setCollapsedCalls] = useState<ReadonlySet<string>>(NONE);
  const [query, setQuery] = useState("");
  const [matchIndex, setMatchIndex] = useState(-1);
  const [selected, setSelected] = useState<string | null>(null);
  const [scrollTo, setScrollTo] = useState<{ key: string; nonce: number } | null>(null);
  const nonce = useRef(0);
  const [mode, setMode] = useState<TimelineMode>(storedMode);
  const [range, setRange] = useState<TimeRange | null>(null);
  const page = useRef<HTMLDivElement>(null);
  const [pageWidth, setPageWidth] = useState(1280);

  useEffect(() => {
    const element = page.current;
    if (element === null || typeof ResizeObserver === "undefined") return;
    const observer = new ResizeObserver(([entry]) => setPageWidth(entry?.contentRect.width ?? 1280));
    observer.observe(element);
    return () => observer.disconnect();
  }, [paging.loaded]);

  const allRows = useMemo(() => buildLedger(records, NONE, NONE), [records]);
  const collapsedRows = useMemo(() => buildLedger(records, collapsedTurns, collapsedCalls), [records, collapsedTurns, collapsedCalls]);
  // 时间概览选了区间时，记录表只留与区间相交的记录。
  const focused = useMemo(() => {
    if (range === null) return null;
    const model = buildTimeline(allRows, mode);
    return model === null ? null : spansInRange(model, range);
  }, [allRows, mode, range]);
  const rows = useMemo(() => (focused === null ? collapsedRows : focusRows(collapsedRows, focused)), [collapsedRows, focused]);
  const matches = useMemo(() => matchRecords(records, query), [records, query]);
  // 匹配按页面上的先后排（不收起时的顺序），跳转时依次经过。
  const ordered = useMemo(() => {
    if (matches.size === 0) return [];
    return allRows.flatMap((row) => (row.type === "record" && matches.has(row.record.id) ? [row.record.id] : []));
  }, [allRows, matches]);

  const reveal = useCallback(
    (id: string, key = id) => {
      const expand = expansionFor(records, [id]);
      setCollapsedTurns((current) => without(current, expand.turns));
      setCollapsedCalls((current) => without(current, expand.calls));
      setScrollTo({ key, nonce: ++nonce.current });
    },
    [records],
  );

  // 地址定位：记录到了就展开、选中、滚过去；只有运行时滚到那一轮的标题。各做一次。
  const located = useRef<string | null>(null);
  useEffect(() => {
    const target = record ?? (run === undefined ? undefined : `turn:${run}`);
    if (target === undefined || located.current === target) return;
    if (record !== undefined && records.some((candidate) => candidate.id === record)) {
      located.current = target;
      setSelected(record);
      reveal(record);
    } else if (record === undefined && run !== undefined && records.some((candidate) => candidate.run === run)) {
      located.current = target;
      setCollapsedTurns((current) => without(current, new Set([run])));
      setScrollTo({ key: target, nonce: ++nonce.current });
    }
  }, [record, run, records, reveal]);

  const jump = useCallback(
    (step: 1 | -1) => {
      if (ordered.length === 0) return;
      const next = (matchIndex + step + ordered.length) % ordered.length;
      setMatchIndex(next);
      const id = ordered[next] as string;
      setSelected(id);
      reveal(id);
    },
    [ordered, matchIndex, reveal],
  );

  const runs = useMemo(() => [...new Set(records.map((entry) => entry.run))], [records]);
  const requestsWithCalls = useMemo(
    () => [...new Set(records.flatMap((entry) => (entry.kind === "tool" && entry.request !== undefined ? [entry.request] : [])))],
    [records],
  );
  const turnsCollapsed = runs.length > 0 && runs.every((id) => collapsedTurns.has(id));
  const callsCollapsed = requestsWithCalls.length > 0 && requestsWithCalls.every((id) => collapsedCalls.has(id));

  const selectedRecord = selected === null ? undefined : records.find((entry) => entry.id === selected);
  const closeInspector = useCallback(() => setSelected(null), []);
  const openRecord = useCallback(
    (id: string) => {
      setSelected(id);
      reveal(id);
    },
    [reveal],
  );

  const loadOlder = useCallback(() => {
    void store.loadOlder(session).catch(() => {
      // 失败时翻页位置不变，滚动或点击加载行可以再试。
    });
  }, [store, session]);

  if (error !== null && !paging.loaded) {
    return (
      <p role="alert" className="p-4 text-sm text-danger">
        {error}
      </p>
    );
  }
  if (paging.loaded && records.length === 0) {
    return <div className="flex h-full items-center justify-center px-4 text-sm text-fg-faint">{t.empty}</div>;
  }

  return (
    <div className="flex h-full min-h-0 flex-col">
      <Toolbar
        turnsCollapsed={turnsCollapsed}
        callsCollapsed={callsCollapsed}
        onTurns={(collapse) => setCollapsedTurns(collapse ? new Set(runs) : NONE)}
        onCalls={(collapse) => setCollapsedCalls(collapse ? new Set(requestsWithCalls) : NONE)}
        query={query}
        onQuery={(next) => {
          setQuery(next);
          setMatchIndex(-1);
        }}
        current={matchIndex < 0 ? 0 : matchIndex + 1}
        total={ordered.length}
        onJump={jump}
        mode={mode}
        onMode={(next) => {
          setMode(next);
          writeStored(MODE_KEY, next, localArea());
        }}
      />
      <Timeline
        rows={allRows}
        mode={mode}
        range={range}
        onRange={setRange}
        selected={selected}
        matches={matches}
        onSelect={openRecord}
        onFocus={(id) => reveal(id)}
        hasEarlier={paging.hasMore}
        onLoadEarlier={loadOlder}
      />
      <div ref={page} className="relative flex min-h-0 flex-1">
        <div className="min-w-0 flex-1">
          <Ledger
            rows={rows}
            paging={paging}
            selected={selected}
            matches={matches}
            scrollTo={scrollTo}
            onLoadOlder={loadOlder}
            onSelect={setSelected}
            onToggleTurn={(id) => setCollapsedTurns((current) => toggled(current, id))}
            onToggleCalls={(id) => setCollapsedCalls((current) => toggled(current, id))}
          />
        </div>
        {selectedRecord !== undefined && (
          <Inspector
            session={session}
            record={selectedRecord}
            lookup={(id) => store.getRecord(session, id)}
            containerWidth={pageWidth}
            overlay={pageWidth < OVERLAY_WIDTH_PX}
            onClose={closeInspector}
            onOpen={openRecord}
          />
        )}
      </div>
    </div>
  );
}
