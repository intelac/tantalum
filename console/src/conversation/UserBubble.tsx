import { AttachmentView } from "../attachment/AttachmentView";
import type { ConversationStore } from "../store/ConversationStore";
import { useItem } from "../store/hooks";
import { clockTime } from "../text/format";
import { useText } from "../text";
import { CopyButton } from "../ui/CopyButton";

interface Props {
  store: ConversationStore;
  id: string;
}

/** 右对齐的浅蓝气泡，圆角 20 px；附件在气泡上方；下方时间和复制。排队中的提交带标签。只有附件时没有文字气泡。 */
export function UserBubble({ store, id }: Props) {
  const t = useText();
  const item = useItem(store, id);
  if (item === undefined || item.kind !== "user") return null;
  return (
    <div className="group flex flex-col items-end gap-1 py-2">
      <AttachmentView attachments={item.attachments ?? []} />
      {item.text !== "" && (
        <div className="max-w-[85%] rounded-xl bg-bubble px-4 py-2.5 text-[15px] leading-relaxed whitespace-pre-wrap text-fg">
          {item.text}
        </div>
      )}
      <div className="flex h-6 items-center gap-1 text-xs text-fg-faint">
        {item.queued && <span className="rounded-xs bg-muted px-1.5 py-0.5 text-[11px] text-fg-muted">{t.conversation.queued}</span>}
        <span>{clockTime(item.at)}</span>
        <CopyButton text={item.text} className="opacity-0 transition-opacity group-hover:opacity-100 focus-visible:opacity-100" />
      </div>
    </div>
  );
}
