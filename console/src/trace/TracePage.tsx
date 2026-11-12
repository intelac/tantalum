// 轨迹页：工具栏、记录表、检查器。页面只有界面状态（收起的轮和调用、搜索词、选中、定位），记录在 TraceStore。
// 换会话时由调用方用 `key` 重建，界面状态随之清空。

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useText } from "../text";
import { useLatestTrace, useTracePaging, useTraceRecords } from "./hooks";
import { approvalsOf } from "./approval";
import { buildLedger, expansionFor, focusRows } from "./rows";
import { Inspector } from "./inspector/Inspector";
import { Ledger } from "./Ledger";
import { matchRecords } from "./search";
import { localArea, readStored, writeStored } from "../ui/storage";
import { Timeline } from "./Timeline";
import { buildTimeline, spansInRange, type TimelineMode, type TimeRange } from "./segments";
import { Toolbar } from "./Toolbar";
import { totalsOf } from "./totals";
import { TraceTotals } from "./TraceTotals";
import { recordsOfTurn, turnTabs } from "./turns";
import { TurnTabs } from "./TurnTabs";
import type { TraceStore } from "./TraceStore";
import { errorLine } from "../text/errors";

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
  // 全部已加载的记录；下面的表、时间轴、搜索用的是 `records`——选了一轮时只有这一轮的（trace-rounds 需求 1）
  const loaded = useTraceRecords(store, session);
  const [turn, setTurn] = useState<string | null>(null);
  const records = useMemo(() => recordsOfTurn(loaded, turn), [loaded, turn]);
  const tabs = useMemo(() => turnTabs(loaded), [loaded]);
  // 总数算的是当前看的范围，不随时间轴上的框选变
  const totals = useMemo(() => totalsOf(records), [records]);
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
  // 时间概览用不并的行：等人的那一段照旧画在“等回答”一道上（trace-rounds 需求 3.6）
  const timelineRows = useMemo(() => buildLedger(records, NONE, NONE, false), [records]);
  const collapsedRows = useMemo(() => buildLedger(records, collapsedTurns, collapsedCalls), [records, collapsedTurns, collapsedCalls]);
  // 等批准、等回答并进了工具那一行（trace-rounds 需求 3）：被并掉的记录在表里没有自己的行，凡是按记录 id 找行的地方都先换成工具的 id
  const folding = useMemo(() => approvalsOf(records), [records]);
  const rowOf = useCallback((id: string) => folding.into.get(id) ?? id, [folding]);
  // 时间概览选了区间时，记录表只留与区间相交的记录。
  const focused = useMemo(() => {
    if (range === null) return null;
    const model = buildTimeline(timelineRows, mode);
    return model === null ? null : new Set([...spansInRange(model, range)].map(rowOf));
  }, [timelineRows, mode, range, rowOf]);
  const rows = useMemo(() => (focused === null ? collapsedRows : focusRows(collapsedRows, focused)), [collapsedRows, focused]);
  // 搜索照样搜被并掉的记录里的文字，命中算在工具那一行上
  const matches = useMemo(() => new Set([...matchRecords(records, query)].map(rowOf)), [records, query, rowOf]);
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

  const selectTurn = useCallback((next: string | null) => {
    setTurn(next);
    // 范围变了：旧的框选区间和搜索位置都没有意义
    setRange(null);
    setMatchIndex(-1);
  }, []);

  // 地址定位：记录到了就展开、选中、滚过去；只有运行时滚到那一轮的标题。各做一次。
  const located = useRef<string | null>(null);
  useEffect(() => {
    const target = record ?? (run === undefined ? undefined : `turn:${run}`);
    if (target === undefined || located.current === target) return;
    // 要定位的东西在已加载的记录里、却不在当前选的这一轮里：先回到“全部”，下一次再定位
    const wanted = record !== undefined ? loaded.find((candidate) => candidate.id === record)?.run : loaded.some((candidate) => candidate.run === run) ? run : undefined;
    if (turn !== null && wanted !== undefined && wanted !== turn) {
      selectTurn(null);
      return;
    }
    if (record !== undefined && records.some((candidate) => candidate.id === record)) {
      located.current = target;
      setSelected(record);
      reveal(rowOf(record));
    } else if (record === undefined && run !== undefined && records.some((candidate) => candidate.run === run)) {
      located.current = target;
      setCollapsedTurns((current) => without(current, new Set([run])));
      setScrollTo({ key: target, nonce: ++nonce.current });
    }
  }, [record, run, records, reveal, loaded, turn, selectTurn, rowOf]);

  const jump = useCallback(
    (step: 1 | -1) => {
      if (ordered.length === 0) return;
      const next = (matchIndex + step + ordered.length) % ordered.length;
      setMatchIndex(next);
      const id = ordered[next] as string;
      setSelected(id);
      reveal(id);
      // （`ordered` 里已经是行的 id）
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

  const selectedRecord = selected === null ? undefined : loaded.find((entry) => entry.id === selected);
  const closeInspector = useCallback(() => setSelected(null), []);
  /** 从时间轴、搜索选中：被并掉的记录选中的是它的工具行。 */
  const openRecord = useCallback(
    (id: string) => {
      setSelected(rowOf(id));
      reveal(rowOf(id));
    },
    [reveal, rowOf],
  );
  /** 检查面板里点开原始记录：面板显示这条记录本身，表里滚到并高亮它所在的那一行。 */
  const openExact = useCallback(
    (id: string) => {
      setSelected(id);
      reveal(rowOf(id));
    },
    [reveal, rowOf],
  );

  const loadOlder = useCallback(() => {
    void store.loadOlder(session).catch(() => {
      // 失败时翻页位置不变，滚动或点击加载行可以再试。
    });
  }, [store, session]);

  if (error !== null && !paging.loaded) {
    return (
      <p role="alert" className="p-4 text-sm text-danger">
        {errorLine(error)}
      </p>
    );
  }
  if (paging.loaded && loaded.length === 0) {
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
      <div className="flex shrink-0 flex-wrap items-center gap-x-3 gap-y-1 border-b border-line px-3 py-1">
        <TurnTabs tabs={tabs} selected={turn} onSelect={selectTurn} hasEarlier={paging.hasMore} onEarlier={loadOlder} />
        <TraceTotals
          totals={totals}
          partial={turn === null && paging.hasMore}
          compact={pageWidth < OVERLAY_WIDTH_PX}
          className="shrink-0 text-[11px] text-fg-muted tabular-nums"
        />
      </div>
      <Timeline
        rows={timelineRows}
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
            selected={selected === null ? null : rowOf(selected)}
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
            onOpen={openExact}
            approvalOf={(tool) => folding.byTool.get(tool)}
          />
        )}
      </div>
    </div>
  );
}
