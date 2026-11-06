import { useCallback, useEffect, useMemo, useState } from "react";
import { listMessages } from "../api/client";
import { useConnection } from "../api/connection";
import type { ConversationStore } from "../store/ConversationStore";
import { useOrder, usePaging } from "../store/hooks";
import { groupBlocks } from "./blocks";
import { EmptyState } from "./EmptyState";
import { MessageList } from "./MessageList";
import { errorLine } from "../text/errors";

interface Props {
  store: ConversationStore;
  session: string;
}

/** 一个会话：先从接口加载最近一页，之后由 SSE 增量更新；滚到顶部时向上加载更早的页。 */
export function ConversationPage({ store, session }: Props) {
  const order = useOrder(store, session);
  const paging = usePaging(store, session);
  const { connects } = useConnection();
  const [error, setError] = useState<unknown>(null);

  // 打开会话和每次重新连上都重新拉最近一页，和 journal 对齐。
  useEffect(() => {
    let cancelled = false;
    setError(null);
    listMessages(session)
      .then((page) => {
        if (!cancelled) store.loadLatest(session, page);
      })
      .catch((failure: unknown) => {
        if (!cancelled) setError(failure);
      });
    return () => {
      cancelled = true;
    };
  }, [store, session, connects]);

  const loadOlder = useCallback(async () => {
    const from = store.getPaging(session).start;
    try {
      store.loadOlder(session, from, await listMessages(session, from));
    } catch (failure) {
      setError(failure);
    }
  }, [store, session]);

  const blocks = useMemo(() => groupBlocks(order, (id) => store.getItem(id)), [order, store]);

  return (
    <div className="min-h-0 flex-1">
      {error !== null && (
        <p role="alert" className="mx-auto mt-4 max-w-[var(--content-width)] px-4 text-sm text-danger">
          {errorLine(error)}
        </p>
      )}
      {blocks.length === 0 ? <EmptyState /> : <MessageList store={store} blocks={blocks} hasMore={paging.hasMore} onLoadOlder={loadOlder} />}
    </div>
  );
}
