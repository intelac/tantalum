import type { ReactNode } from "react";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { listMessages } from "../api/client";
import type { ConversationStore } from "../store/ConversationStore";
import { useItem, useOrder } from "../store/hooks";
import { groupBlocks } from "./blocks";
import { EmptyState } from "./EmptyState";
import { MessageList } from "./MessageList";

/** 输入栏需要知道的会话状态。 */
export interface ComposerState {
  /** 最近一次运行的 ID，没有时为空。 */
  latestRun: string | null;
  /** 最近一次运行还没结束。 */
  running: boolean;
  /** 排队中的用户条目 ID。 */
  queued: readonly string[];
}

interface Props {
  store: ConversationStore;
  session: string;
  /** 每次重连加一，页面据此重新拉取，保证和 journal 一致。 */
  reloadKey: number;
  composer: (state: ComposerState) => ReactNode;
}

/** 已加载部分的起点和前面是否还有。 */
interface Loaded {
  session: string;
  start: number;
  hasMore: boolean;
}

/** 一个会话：先从接口加载最近一页，之后由 SSE 增量更新；滚到顶部时向上加载更早的页。 */
export function ConversationPage({ store, session, reloadKey, composer }: Props) {
  const order = useOrder(store, session);
  const [error, setError] = useState<string | null>(null);
  const [loaded, setLoaded] = useState<Loaded>({ session, start: 0, hasMore: false });
  const loadedRef = useRef(loaded);
  loadedRef.current = loaded;

  useEffect(() => {
    let cancelled = false;
    setError(null);
    listMessages(session)
      .then((messages) => {
        if (cancelled) return;
        const keptOlder = store.replace(session, messages.items, messages.seq);
        // 重连时保留了之前向上加载的部分，起点不变；否则从这一页开始。
        const current = loadedRef.current;
        if (keptOlder === 0 || current.session !== session) {
          setLoaded({ session, start: messages.start, hasMore: messages.hasMore });
        }
      })
      .catch((failure: Error) => {
        if (!cancelled) setError(failure.message);
      });
    return () => {
      cancelled = true;
    };
  }, [store, session, reloadKey]);

  const loadOlder = useCallback(async () => {
    const current = loadedRef.current;
    if (current.session !== session || !current.hasMore) return;
    try {
      const page = await listMessages(session, current.start);
      if (loadedRef.current !== current) return;
      store.prependItems(session, page.items, page.seq);
      setLoaded({ session, start: page.start, hasMore: page.hasMore });
    } catch (failure) {
      setError((failure as Error).message);
    }
  }, [store, session]);

  const blocks = useMemo(() => groupBlocks(order, (id) => store.getItem(id)), [order, store]);
  const latest = [...blocks].reverse().find((block) => block.kind === "run");
  const latestStatus = useItem(store, latest?.kind === "run" && latest.statusId !== undefined ? latest.statusId : "");
  const queued = useMemo(
    () => order.filter((id) => store.getItem(id)?.kind === "user" && store.getItem(id)?.run === undefined),
    [order, store],
  );
  const state: ComposerState = {
    latestRun: latest?.kind === "run" ? latest.run : null,
    running: latestStatus?.kind === "run" && latestStatus.endReason === undefined,
    queued,
  };

  return (
    <div className="flex h-full min-h-0 flex-col">
      <div className="min-h-0 flex-1">
        {error !== null && (
          <p role="alert" className="mx-auto mt-4 max-w-[var(--content-width)] px-4 text-sm text-danger">
            {error}
          </p>
        )}
        {blocks.length === 0 ? <EmptyState /> : <MessageList store={store} blocks={blocks} hasMore={loaded.session === session && loaded.hasMore} onLoadOlder={loadOlder} />}
      </div>
      {composer(state)}
    </div>
  );
}
