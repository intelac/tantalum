import { useEffect, useState } from "react";
import { searchContent } from "../api/client";
import type { ContentHit } from "../api/types";

/** 停止输入多久后才按内容搜（DSH WorkspaceBrowser 250 ms）。 */
export const CONTENT_DELAY_MS = 250;

export interface ContentSearch {
  /** 这次查询的内容结果；还没回来、没在搜时为 null。 */
  result: { hits: ContentHit[]; more: boolean } | null;
  loading: boolean;
  failed: boolean;
}

const IDLE: ContentSearch = { result: null, loading: false, failed: false };

/**
 * 按内容搜会话（session-find-pin R3.3、R3.4）：停止输入 {@link CONTENT_DELAY_MS} 后请求，查询变了就中止旧请求、丢掉旧结果。
 * 失败时 {@code failed} 为 true，调用方保留本地结果。
 */
export function useContentSearch(query: string): ContentSearch {
  const [state, setState] = useState<ContentSearch>(IDLE);
  const needle = query.trim();
  useEffect(() => {
    if (needle === "") {
      setState(IDLE);
      return;
    }
    setState({ result: null, loading: true, failed: false });
    const abort = new AbortController();
    const timer = window.setTimeout(() => {
      searchContent(needle, abort.signal)
        .then((result) => {
          if (!abort.signal.aborted) setState({ result, loading: false, failed: false });
        })
        .catch(() => {
          if (!abort.signal.aborted) setState({ result: null, loading: false, failed: true });
        });
    }, CONTENT_DELAY_MS);
    return () => {
      window.clearTimeout(timer);
      abort.abort();
    };
  }, [needle]);
  return state;
}
