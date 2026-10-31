import { Markdown } from "../markdown/Markdown";
import type { ConversationStore } from "../store/ConversationStore";
import { useItem } from "../store/hooks";
import { clockTime } from "../text/time";
import { zh } from "../text/zh";
import { CopyButton } from "../ui/CopyButton";

interface Props {
  store: ConversationStore;
  id: string;
  /** 最后一段才显示底部的复制和时间。 */
  last: boolean;
}

/** 模型回复正文：无气泡，Markdown。被取消的回复标记“已中断”。 */
export function AssistantText({ store, id, last }: Props) {
  const item = useItem(store, id);
  if (item === undefined || item.kind !== "text") return null;
  return (
    <div className="py-1">
      <Markdown text={item.text} />
      {item.interrupted && (
        <p className="mt-2 inline-block rounded-xs bg-muted px-1.5 py-0.5 text-xs text-fg-muted">{zh.conversation.interrupted}</p>
      )}
      {last && (
        <div className="mt-2 flex h-7 items-center gap-2 text-xs text-fg-faint">
          <CopyButton text={item.text} />
          <span>{clockTime(item.at)}</span>
        </div>
      )}
    </div>
  );
}
