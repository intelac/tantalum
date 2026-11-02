import { useCallback, useEffect, useLayoutEffect, useRef, useState } from "react";
import { ProcessGroup } from "../process/ProcessGroup";
import type { ConversationStore } from "../store/ConversationStore";
import { AssistantText } from "./AssistantText";
import type { Block } from "./blocks";
import { CompactionMark } from "./CompactionMark";
import { JumpToBottom } from "./JumpToBottom";
import { LiveReply } from "./LiveReply";
import { RunStatusRow } from "./RunStatusRow";
import { isNearBottom } from "./scroll";
import { UserBubble } from "./UserBubble";

interface Props {
  store: ConversationStore;
  blocks: readonly Block[];
  /** 前面还有更早的条目。 */
  hasMore?: boolean;
  /** 加载上一页，完成后新的块会出现在最前面。 */
  onLoadOlder?: () => Promise<void>;
}

/** 距顶部不超过这个像素数就开始加载上一页。 */
const LOAD_THRESHOLD = 200;

/**
 * 消息列表。内容列 680 px 居中。用户停在底部时新内容自动跟随；向上翻看后停止跟随并显示回到底部。
 * 每个条目自己订阅 store，列表只在顺序变化时重绘。滚到顶部附近时加载上一页，用加载前后的内容高度差保持位置。
 */
export function MessageList({ store, blocks, hasMore = false, onLoadOlder }: Props) {
  const scroller = useRef<HTMLDivElement>(null);
  const content = useRef<HTMLDivElement>(null);
  const [stick, setStick] = useState(true);
  const stickRef = useRef(true);
  /** 正在加载上一页时，加载前的内容高度和滚动位置。 */
  const anchor = useRef<{ height: number; top: number } | null>(null);
  const loading = useRef(false);

  const loadOlder = useCallback(() => {
    const element = scroller.current;
    if (element === null || !hasMore || onLoadOlder === undefined || loading.current) return;
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

  function onScroll(): void {
    const element = scroller.current;
    if (element === null) return;
    const near = isNearBottom(element.scrollTop, element.clientHeight, element.scrollHeight);
    if (near !== stickRef.current) {
      stickRef.current = near;
      setStick(near);
    }
    if (element.scrollTop <= LOAD_THRESHOLD) loadOlder();
  }

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
  }, [blocks, toBottom]);

  // 第一页填不满视口时没法滚动，直接接着加载。
  useEffect(() => {
    const element = scroller.current;
    if (element !== null && element.clientHeight > 0 && element.scrollHeight <= element.clientHeight + LOAD_THRESHOLD) loadOlder();
  }, [blocks, loadOlder]);

  return (
    <div className="relative h-full">
      <div ref={scroller} onScroll={onScroll} className="h-full overflow-y-auto overscroll-contain px-4">
        <div ref={content} className="mx-auto flex w-full max-w-[var(--content-width)] flex-col py-4">
          {blocks.map((block) =>
            block.kind === "user" ? (
              <UserBubble key={block.id} store={store} id={block.id} />
            ) : block.kind === "compaction" ? (
              <CompactionMark key={block.id} store={store} id={block.id} />
            ) : (
              <div key={block.run} className="py-1">
                <ProcessGroup store={store} run={block.run} statusId={block.statusId} ids={block.process} />
                {block.reply.map((id, index) => (
                  <AssistantText key={id} store={store} id={id} last={index === block.reply.length - 1} />
                ))}
                <LiveReply store={store} run={block.run} />
                {block.statusId !== undefined && <RunStatusRow store={store} id={block.statusId} />}
              </div>
            ),
          )}
        </div>
      </div>
      {!stick && <JumpToBottom onClick={toBottom} />}
    </div>
  );
}
