// 按条目 ID 保存不可变对象，组件只订阅自己的条目：一个分片只重绘一个条目。不依赖 React。

import type { Item } from "../api/types";

export interface Live {
  text: string;
  reasoning: string;
}

const EMPTY_ORDER: readonly string[] = Object.freeze([]);
const EMPTY_LIVE: Live = Object.freeze({ text: "", reasoning: "" });

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

  /**
   * SSE `item`：同一 ID 覆盖，位置不变；新 ID 追加到会话末尾。
   * {@code seq} 是产生这条推送的事件序号；比已有版本旧的推送（重连补读时会有）不覆盖。
   */
  applyItem(item: Item, seq?: number): void {
    const isNew = !this.items.has(item.id);
    if (!isNew && seq !== undefined && (this.versions.get(item.id) ?? -1) > seq) return;
    this.items.set(item.id, item);
    if (seq !== undefined) this.versions.set(item.id, seq);
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
   * 从接口整体加载一个会话。{@code seq} 是快照截至的事件序号：SSE 已经推过更新版本的条目保留，
   * 快照里没有但比快照新的条目也保留并排在后面；其余以快照为准。只通知真正变了的条目。
   * 不给 {@code seq} 时快照无条件生效，且不记录版本，之后任何推送都能更新这些条目。
   */
  replace(session: string, items: readonly Item[], seq?: number): void {
    const changed: string[] = [];
    const ids = items.map((item) => item.id);
    const cutoff = seq ?? Number.MAX_SAFE_INTEGER;
    for (const item of items) {
      const before = this.items.get(item.id);
      if (before !== undefined && (this.versions.get(item.id) ?? -1) > cutoff) continue;
      if (before === undefined || !sameItem(before, item)) {
        this.items.set(item.id, item);
        changed.push(item.id);
      }
      if (seq === undefined) this.versions.delete(item.id);
      else this.versions.set(item.id, Math.max(seq, this.versions.get(item.id) ?? -1));
    }
    const previous = this.getOrder(session);
    for (const id of previous) {
      if (ids.includes(id)) continue;
      if ((this.versions.get(id) ?? -1) > cutoff) ids.push(id);
      else {
        this.items.delete(id);
        this.versions.delete(id);
      }
    }
    const sameOrder = previous.length === ids.length && previous.every((id, index) => id === ids[index]);
    if (!sameOrder) {
      this.order.set(session, ids);
      this.notify(ConversationStore.orderKey(session));
    }
    for (const id of changed) this.notify(ConversationStore.itemKey(id));
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
