import { ChevronDown, ChevronUp } from "lucide-react";
import { useState } from "react";
import type { CompactionItem } from "../api/types";
import { tokenCount } from "../composer/status";
import { Markdown } from "../markdown/Markdown";
import type { ConversationStore } from "../store/ConversationStore";
import { useItem } from "../store/hooks";
import { zh } from "../text/zh";

interface Props {
  store: ConversationStore;
  id: string;
}

function triggerLabel(item: CompactionItem): string {
  if (item.trigger === "MANUAL") return ` · ${zh.conversation.compactedManual}`;
  if (item.trigger === "OVERFLOW") return ` · ${zh.conversation.compactedOverflow}`;
  return "";
}

/** 对话里的压缩分隔线：上面的内容在模型看来已经换成摘要。点开看摘要原文；失败时只显示原因。 */
export function CompactionMark({ store, id }: Props) {
  const item = useItem(store, id);
  const [open, setOpen] = useState(false);
  if (item === undefined || item.kind !== "compaction") return null;
  if (item.failure !== undefined) {
    return (
      <div role="note" className="my-3 flex items-center gap-3 text-xs text-fg-faint">
        <span className="h-px flex-1 bg-line" />
        <span>{zh.conversation.compactionFailed(item.failure)}</span>
        <span className="h-px flex-1 bg-line" />
      </div>
    );
  }
  if (item.summary === undefined || item.summary === "") {
    return (
      <div role="note" className="my-3 flex items-center gap-3 text-xs text-fg-faint">
        <span className="h-px flex-1 bg-line" />
        <span>{zh.conversation.trimmed(tokenCount(item.estimateBefore), tokenCount(item.estimateAfter)) + triggerLabel(item)}</span>
        <span className="h-px flex-1 bg-line" />
      </div>
    );
  }
  const label = zh.conversation.compacted(tokenCount(item.estimateBefore), tokenCount(item.estimateAfter)) + triggerLabel(item);
  return (
    <div className="my-3 text-xs text-fg-faint">
      <div className="flex items-center gap-3">
        <span className="h-px flex-1 bg-line" />
        <button
          type="button"
          aria-expanded={open}
          onClick={() => setOpen(!open)}
          className="flex items-center gap-1 rounded-xs px-1.5 py-0.5 hover:bg-hover hover:text-fg"
        >
          {label}
          {open ? <ChevronUp size={12} aria-hidden="true" /> : <ChevronDown size={12} aria-hidden="true" />}
        </button>
        <span className="h-px flex-1 bg-line" />
      </div>
      {open && (
        <div className="mt-2 rounded-md border border-line bg-muted px-3 py-2 text-sm text-fg-muted" aria-label={zh.conversation.summary}>
          <Markdown text={item.summary ?? ""} />
        </div>
      )}
    </div>
  );
}
