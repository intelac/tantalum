import type { ConversationStore } from "../store/ConversationStore";
import type { Block } from "./blocks";
import { CompactionMark } from "./CompactionMark";
import { JumpToBottom } from "./JumpToBottom";
import { RunBlock } from "./RunBlock";
import { UserBubble } from "./UserBubble";
import { useScrollFollow } from "./useScrollFollow";

interface Props {
  store: ConversationStore;
  blocks: readonly Block[];
  /** 前面还有更早的条目。 */
  hasMore: boolean;
  /** 加载上一页，完成后新的块会出现在最前面。 */
  onLoadOlder: () => Promise<void>;
}

/** 消息列表，内容列 680 px 居中。每个条目自己订阅 store，列表只在顺序变化时重绘。 */
export function MessageList({ store, blocks, hasMore, onLoadOlder }: Props) {
  const { scroller, content, stick, toBottom, onScroll } = useScrollFollow(blocks, hasMore, onLoadOlder);
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
              <RunBlock key={block.run} store={store} run={block.run} process={block.process} reply={block.reply} />
            ),
          )}
        </div>
      </div>
      {!stick && <JumpToBottom onClick={toBottom} />}
    </div>
  );
}
