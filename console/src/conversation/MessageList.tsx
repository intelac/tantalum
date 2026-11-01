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
}

/**
 * 消息列表。内容列 680 px 居中。用户停在底部时新内容自动跟随；向上翻看后停止跟随并显示回到底部。
 * 每个条目自己订阅 store，列表只在顺序变化时重绘。
 */
export function MessageList({ store, blocks }: Props) {
  const scroller = useRef<HTMLDivElement>(null);
  const content = useRef<HTMLDivElement>(null);
  const [stick, setStick] = useState(true);
  const stickRef = useRef(true);

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

  useLayoutEffect(() => {
    if (stickRef.current) toBottom();
  }, [blocks, toBottom]);

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
