// 轨迹记录按会话、按 ID 保存，按版本合并：接口给的一页和 SSE 推送来自同一个投影，合并后和整页重放的结果相同。不依赖 React。

import type { TracePage, TraceRecord } from "../api/types";

/** 翻页位置：`before` 是已加载部分最早运行的开始序号（下一页的游标），`loading` 表示正在取更早的一页。 */
export interface TracePaging {
  loaded: boolean;
  before?: number;
  hasMore: boolean;
  loading: boolean;
}

type Listener = () => void;
type Schedule = (flush: () => void) => void;
type Fetch = (session: string, before: number) => Promise<TracePage>;

const EMPTY: readonly TraceRecord[] = Object.freeze([]);
const NOT_LOADED: TracePaging = Object.freeze({ loaded: false, hasMore: false, loading: false });

/** 运行 ID 是 `<会话>_<开始序号>`（后端 `RunId.of`）。 */
export function sessionOfRun(run: string): string {
  const cut = run.lastIndexOf("_");
  return cut < 0 ? run : run.slice(0, cut);
}

export function runStart(run: string): number {
  return Number(run.slice(run.lastIndexOf("_") + 1));
}

/** `pos` 是“序号.下标”，按数字比较。 */
export function comparePos(a: string, b: string): number {
  const [aSeq = 0, aIndex = 0] = a.split(".").map(Number);
  const [bSeq = 0, bIndex = 0] = b.split(".").map(Number);
  return aSeq - bSeq || aIndex - bIndex;
}

class SessionTrace {
  readonly records = new Map<string, TraceRecord>();
  /** 每条记录最后一次更新来自哪个事件序号。 */
  readonly versions = new Map<string, number>();
  sorted: readonly TraceRecord[] = EMPTY;
  paging: TracePaging = NOT_LOADED;
  older: Promise<void> | null = null;
}

function requestFrame(flush: () => void): void {
  if (typeof requestAnimationFrame === "function") requestAnimationFrame(flush);
  else setTimeout(flush, 0);
}

export class TraceStore {
  private readonly sessions = new Map<string, SessionTrace>();
  private readonly listeners = new Map<string, Set<Listener>>();
  private readonly dirty = new Set<string>();
  private scheduled = false;

  /** {@code schedule} 决定何时通知：默认按帧合并，一批推送只重算一次行；测试传同步执行。 */
  constructor(
    private readonly fetchOlder: Fetch,
    private readonly schedule: Schedule = requestFrame,
  ) {}

  static recordsKey(session: string): string {
    return `trace:${session}`;
  }

  static pagingKey(session: string): string {
    return `trace-paging:${session}`;
  }

  subscribe(key: string, listener: Listener): () => void {
    let set = this.listeners.get(key);
    if (set === undefined) {
      set = new Set();
      this.listeners.set(key, set);
    }
    set.add(listener);
    return () => {
      set.delete(listener);
      if (set.size === 0) this.listeners.delete(key);
    };
  }

  /** 按 `pos` 排好的记录；变化时才换新数组。 */
  getRecords(session: string): readonly TraceRecord[] {
    return this.sessions.get(session)?.sorted ?? EMPTY;
  }

  getRecord(session: string, id: string): TraceRecord | undefined {
    return this.sessions.get(session)?.records.get(id);
  }

  getPaging(session: string): TracePaging {
    return this.sessions.get(session)?.paging ?? NOT_LOADED;
  }

  /**
   * SSE `trace`：同一 ID 的新版本覆盖旧版本，不比已有版本新的丢掉。
   * 运行开始得比已加载部分还早的记录不收：翻到那一页时页面已经是最终状态。
   */
  applyRecord(record: TraceRecord, seq: number): void {
    const session = sessionOfRun(record.run);
    const trace = this.session(session);
    const { paging } = trace;
    if (paging.loaded && paging.hasMore && paging.before !== undefined && runStart(record.run) < paging.before) return;
    if (!this.put(trace, record, seq)) return;
    this.resort(session, trace);
  }

  /**
   * 最新一页（打开轨迹页和每次重连都会调）。{@code page.seq} 之后推过的版本保留，页面里没有但比页面新的记录也保留；
   * 其余以页面为准。之前翻过的更早的页，只要和这一页接得上（这一页最早的运行原来就在），连同翻页位置一起保留。
   */
  loadLatest(session: string, page: TracePage): void {
    const trace = this.session(session);
    const pageRuns = new Set(page.records.map((record) => record.run));
    const pageIds = new Set(page.records.map((record) => record.id));
    const oldestRun = page.records.reduce<string | undefined>(
      (oldest, record) => (oldest === undefined || runStart(record.run) < runStart(oldest) ? record.run : oldest),
      undefined,
    );
    const connected = trace.paging.loaded && oldestRun !== undefined && [...trace.records.values()].some((record) => record.run === oldestRun);
    const pageStart = oldestRun === undefined ? Infinity : runStart(oldestRun);
    for (const [id, record] of trace.records) {
      const newer = (trace.versions.get(id) ?? -1) > page.seq;
      const older = connected && runStart(record.run) < pageStart && !pageRuns.has(record.run);
      if (!newer && !older && !pageIds.has(id)) {
        trace.records.delete(id);
        trace.versions.delete(id);
      }
    }
    for (const record of page.records) this.put(trace, record, page.seq);
    if (!connected) trace.paging = { loaded: true, before: page.before, hasMore: page.hasMore, loading: trace.paging.loading };
    else if (!trace.paging.loaded) trace.paging = { ...trace.paging, loaded: true };
    this.resort(session, trace);
    this.mark(TraceStore.pagingKey(session));
  }

  /** 取更早的一页。同一会话同时只有一个请求，轨迹页和统计页共用；没有更早的或还没加载时什么也不做。 */
  loadOlder(session: string): Promise<void> {
    const trace = this.session(session);
    if (trace.older !== null) return trace.older;
    const { paging } = trace;
    if (!paging.loaded || !paging.hasMore || paging.before === undefined) return Promise.resolve();
    const from = paging.before;
    trace.paging = { ...paging, loading: true };
    this.mark(TraceStore.pagingKey(session));
    const request = this.fetchOlder(session, from)
      .then((page) => {
        // 中间重新加载过、位置变了，这一页就不要了。
        if (trace.paging.before !== from) return;
        for (const record of page.records) {
          if (!trace.records.has(record.id)) this.put(trace, record, page.seq);
        }
        trace.paging = { loaded: true, before: page.before, hasMore: page.hasMore, loading: false };
        this.resort(session, trace);
      })
      .finally(() => {
        trace.older = null;
        if (trace.paging.loading) trace.paging = { ...trace.paging, loading: false };
        this.mark(TraceStore.pagingKey(session));
      });
    trace.older = request;
    return request;
  }

  private session(session: string): SessionTrace {
    let trace = this.sessions.get(session);
    if (trace === undefined) {
      trace = new SessionTrace();
      this.sessions.set(session, trace);
    }
    return trace;
  }

  /** 版本不比已有的新就不放；返回是否放了。 */
  private put(trace: SessionTrace, record: TraceRecord, seq: number): boolean {
    const version = trace.versions.get(record.id);
    if (version !== undefined && version >= seq && trace.records.has(record.id)) return false;
    trace.records.set(record.id, record);
    trace.versions.set(record.id, seq);
    return true;
  }

  private resort(session: string, trace: SessionTrace): void {
    trace.sorted = [...trace.records.values()].sort((a, b) => comparePos(a.pos, b.pos));
    this.mark(TraceStore.recordsKey(session));
  }

  private mark(key: string): void {
    this.dirty.add(key);
    if (this.scheduled) return;
    this.scheduled = true;
    this.schedule(() => {
      this.scheduled = false;
      const keys = [...this.dirty];
      this.dirty.clear();
      for (const key of keys) {
        const set = this.listeners.get(key);
        if (set !== undefined) for (const listener of set) listener();
      }
    });
  }
}
