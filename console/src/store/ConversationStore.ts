// 按条目 ID 保存不可变对象，组件只订阅自己的条目：一个分片只重绘一个条目。不依赖 React。

import type { Item, Messages, ModelChoice } from "../api/types";

export interface Live {
  text: string;
  reasoning: string;
}

const EMPTY_ORDER: readonly string[] = Object.freeze([]);
const EMPTY_LIVE: Live = Object.freeze({ text: "", reasoning: "" });

/** 已加载部分的第一个条目在会话里的位置，以及前面是否还有更早的条目。 */
export interface Paging {
  start: number;
  hasMore: boolean;
}

const NO_PAGING: Paging = Object.freeze({ start: 0, hasMore: false });

/** 会话现在的模型选择和有没有工具变化（model-picker），来自会话视图，换了模型的条目推过来时跟着换。 */
export interface SessionModel {
  model: ModelChoice | null;
  toolChangesInHistory: boolean;
}

const NO_MODEL: SessionModel = Object.freeze({ model: null, toolChangesInHistory: false });

type Listener = () => void;

/** SSE 消息 ID 形如 `seq.index`，取事件序号。 */
export function seqOf(eventId: string | undefined): number | undefined {
  if (eventId === undefined) return undefined;
  const seq = Number(eventId.split(".")[0]);
  return Number.isFinite(seq) ? seq : undefined;
}

export class ConversationStore {
  private readonly items = new Map<string, Item>();
  /** 每个条目最后一次更新来自哪个事件序号；接口快照和 SSE 推送按序号大的为准合并。 */
  private readonly versions = new Map<string, number>();
  /** 会话到条目 ID 的顺序；每次变化换成新数组，所以引用可以直接比较。 */
  private readonly order = new Map<string, readonly string[]>();
  /** 会话到翻页位置。 */
  private readonly paging = new Map<string, Paging>();
  /** 会话到现在的模型选择。 */
  private readonly models = new Map<string, SessionModel>();
  /** 运行到流式分片；结算后清掉。 */
  private readonly live = new Map<string, Live>();
  private readonly listeners = new Map<string, Set<Listener>>();

  static itemKey(id: string): string {
    return `item:${id}`;
  }

  static orderKey(session: string): string {
    return `order:${session}`;
  }

  static liveKey(run: string): string {
    return `live:${run}`;
  }

  static pagingKey(session: string): string {
    return `paging:${session}`;
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

  getItem(id: string): Item | undefined {
    return this.items.get(id);
  }

  getOrder(session: string): readonly string[] {
    return this.order.get(session) ?? EMPTY_ORDER;
  }

  getLive(run: string): Live {
    return this.live.get(run) ?? EMPTY_LIVE;
  }

  getPaging(session: string): Paging {
    return this.paging.get(session) ?? NO_PAGING;
  }

  static modelKey(session: string): string {
    return `model:${session}`;
  }

  getModel(session: string): SessionModel {
    return this.models.get(session) ?? NO_MODEL;
  }

  private setModel(session: string, next: SessionModel): void {
    const current = this.models.get(session);
    if (current !== undefined && JSON.stringify(current) === JSON.stringify(next)) return;
    this.models.set(session, next);
    this.notify(ConversationStore.modelKey(session));
  }

  /**
   * SSE `item`：同一 ID 覆盖，位置不变；新 ID 追加到会话末尾。
   * {@code seq} 是产生这条推送的事件序号；比已有版本旧的推送（重连补读时会有）不覆盖。
   */
  applyItem(item: Item, seq?: number): void {
    const isNew = !this.items.has(item.id);
    if (!isNew && seq !== undefined && (this.versions.get(item.id) ?? -1) > seq) return;
    this.items.set(item.id, item);
    if (seq !== undefined) this.versions.set(item.id, seq);
    if (item.kind === "modelChange") {
      const model = { route: item.route, model: item.model, effort: item.effort ?? null };
      this.setModel(item.session, { ...this.getModel(item.session), model });
    }
    if (isNew) {
      this.order.set(item.session, [...this.getOrder(item.session), item.id]);
      this.notify(ConversationStore.orderKey(item.session));
    }
    this.notify(ConversationStore.itemKey(item.id));
  }

  /** 流式分片只通知这个运行的进行中条目。 */
  applyChunk(run: string, kind: "text" | "reasoning", text: string): void {
    const current = this.getLive(run);
    this.live.set(run, kind === "text" ? { ...current, text: current.text + text } : { ...current, reasoning: current.reasoning + text });
    this.notify(ConversationStore.liveKey(run));
  }

  clearLive(run: string): void {
    if (!this.live.delete(run)) return;
    this.notify(ConversationStore.liveKey(run));
  }

  /**
   * 从接口加载一个会话的最近一页（打开会话和每次重连都会调）。{@code page.seq} 是快照截至的事件序号：
   * SSE 已经推过更新版本的条目保留，快照里没有但比快照新的条目也保留并排在后面；其余以快照为准。只通知真正变了的条目。
   * 之前向上加载过的更早条目，只要这一页的第一个条目原来就在，就接在前面保留，翻页位置不变（中间没有缺口）；
   * 否则丢掉，翻页位置从这一页开始。快照和推送会重叠，是因为重连时既按 Last-Event-ID 补读、又重新拉这一页。
   */
  loadLatest(session: string, page: Messages): void {
    const changed: string[] = [];
    const ids = page.items.map((item) => item.id);
    for (const item of page.items) {
      const before = this.items.get(item.id);
      if (before !== undefined && (this.versions.get(item.id) ?? -1) > page.seq) continue;
      if (before === undefined || !sameItem(before, item)) {
        this.items.set(item.id, item);
        changed.push(item.id);
      }
      this.versions.set(item.id, Math.max(page.seq, this.versions.get(item.id) ?? -1));
    }
    const previous = this.getOrder(session);
    const firstIndex = ids.length > 0 ? previous.indexOf(ids[0]) : -1;
    const older = firstIndex > 0 ? previous.slice(0, firstIndex).filter((id) => !ids.includes(id)) : [];
    const kept = new Set(older);
    for (const id of previous) {
      if (ids.includes(id) || kept.has(id)) continue;
      if ((this.versions.get(id) ?? -1) > page.seq) ids.push(id);
      else {
        this.items.delete(id);
        this.versions.delete(id);
      }
    }
    const next = [...older, ...ids];
    const sameOrder = previous.length === next.length && previous.every((id, index) => id === next[index]);
    if (!sameOrder) {
      this.order.set(session, next);
      this.notify(ConversationStore.orderKey(session));
    }
    for (const id of changed) this.notify(ConversationStore.itemKey(id));
    if (older.length === 0) this.setPaging(session, { start: page.start, hasMore: page.hasMore });
    if (page.model !== undefined) this.setModel(session, { model: page.model, toolChangesInHistory: page.toolChangesInHistory ?? false });
  }

  /**
   * 向上加载的更早一页，接在最前面；{@code from} 是请求这一页时的翻页位置，位置已经变了（中间重新加载过）就丢掉这一页。
   * 已经有的条目不动（SSE 可能推过更新的版本）。
   */
  loadOlder(session: string, from: number, page: Messages): void {
    if (this.getPaging(session).start !== from) return;
    const fresh = page.items.filter((item) => !this.items.has(item.id));
    for (const item of fresh) {
      this.items.set(item.id, item);
      this.versions.set(item.id, page.seq);
    }
    if (fresh.length > 0) {
      this.order.set(session, [...fresh.map((item) => item.id), ...this.getOrder(session)]);
      this.notify(ConversationStore.orderKey(session));
    }
    this.setPaging(session, { start: page.start, hasMore: page.hasMore });
  }

  private setPaging(session: string, next: Paging): void {
    const current = this.paging.get(session);
    if (current !== undefined && current.start === next.start && current.hasMore === next.hasMore) return;
    this.paging.set(session, next);
    this.notify(ConversationStore.pagingKey(session));
  }

  private notify(key: string): void {
    const set = this.listeners.get(key);
    if (set === undefined) return;
    for (const listener of set) listener();
  }
}

function sameItem(a: Item, b: Item): boolean {
  return JSON.stringify(a) === JSON.stringify(b);
}
