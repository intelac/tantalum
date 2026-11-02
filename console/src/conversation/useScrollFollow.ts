// 消息列表的滚动：停在底部时新内容自动跟随；向上翻看后停止跟随；滚到顶部附近时加载上一页，并保持眼前的内容不动。

import { type RefObject, useCallback, useEffect, useLayoutEffect, useRef, useState } from "react";

/** 距底部不超过这个像素数就算“停留在底部”。 */
const STICK_THRESHOLD = 48;
/** 距顶部不超过这个像素数就开始加载上一页。 */
const LOAD_THRESHOLD = 200;

interface ScrollFollow {
  scroller: RefObject<HTMLDivElement | null>;
  content: RefObject<HTMLDivElement | null>;
  /** 正停在底部。 */
  stick: boolean;
  toBottom: () => void;
  onScroll: () => void;
}

/** {@code changed} 是内容变了的信号（这里是渲染块数组）；{@code onLoadOlder} 完成后新的内容会出现在最前面。 */
export function useScrollFollow(changed: unknown, hasMore: boolean, onLoadOlder: () => Promise<void>): ScrollFollow {
  const scroller = useRef<HTMLDivElement>(null);
  const content = useRef<HTMLDivElement>(null);
  const [stick, setStick] = useState(true);
  // 事件回调和 ResizeObserver 里要读到最新值，所以另存一份。
  const stickRef = useRef(true);
  /** 正在加载上一页时，加载前的内容高度和滚动位置。 */
  const anchor = useRef<{ height: number; top: number } | null>(null);
  const loading = useRef(false);

  const loadOlder = useCallback(() => {
    const element = scroller.current;
    if (element === null || !hasMore || loading.current) return;
    loading.current = true;
    anchor.current = { height: element.scrollHeight, top: element.scrollTop };
    onLoadOlder().finally(() => {
      loading.current = false;
    });
  }, [hasMore, onLoadOlder]);

  const toBottom = useCallback(() => {
    const element = scroller.current;
    if (element !== null) element.scrollTop = element.scrollHeight;
    stickRef.current = true;
    setStick(true);
  }, []);

  const onScroll = useCallback(() => {
    const element = scroller.current;
    if (element === null) return;
    const near = element.scrollHeight - (element.scrollTop + element.clientHeight) <= STICK_THRESHOLD;
    if (near !== stickRef.current) {
      stickRef.current = near;
      setStick(near);
    }
    if (element.scrollTop <= LOAD_THRESHOLD) loadOlder();
  }, [loadOlder]);

  // 内容高度变化（分片、展开、图片）时，如果还贴着底部就跟过去。
  useEffect(() => {
    const element = content.current;
    if (element === null || typeof ResizeObserver === "undefined") return;
    const observer = new ResizeObserver(() => {
      if (stickRef.current) toBottom();
    });
    observer.observe(element);
    return () => observer.disconnect();
  }, [toBottom]);

  // 上一页插到最前面后，把滚动位置往下挪新增的高度，眼前的内容不动。
  useLayoutEffect(() => {
    const element = scroller.current;
    const saved = anchor.current;
    if (element !== null && saved !== null && element.scrollHeight !== saved.height) {
      element.scrollTop = saved.top + (element.scrollHeight - saved.height);
      anchor.current = null;
      return;
    }
    if (stickRef.current) toBottom();
  }, [changed, toBottom]);

  // 第一页填不满视口时没法滚动，直接接着加载。
  useEffect(() => {
    const element = scroller.current;
    if (element !== null && element.clientHeight > 0 && element.scrollHeight <= element.clientHeight + LOAD_THRESHOLD) loadOlder();
  }, [changed, loadOlder]);

  return { scroller, content, stick, toBottom, onScroll };
}
