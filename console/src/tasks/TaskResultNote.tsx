import { GitBranch } from "lucide-react";
import type { ConversationStore } from "../store/ConversationStore";
import { useItem } from "../store/hooks";
import { useText } from "../text";
import { OutcomeBadge } from "./TaskCard";

interface Props {
  store: ConversationStore;
  id: string;
}

/**
 * 一个任务的结果回到了这个对话（subagent 需求 3.5）：虚线框，不是用户气泡。它是任务写的，不是你说的话，
 * 模型看到的也标明了来源。
 */
export function TaskResultNote({ store, id }: Props) {
  const item = useItem(store, id);
  const t = useText().tasks;
  if (item?.kind !== "taskResult") return null;
  return (
    <section aria-label={t.result(item.task)} className="my-1.5 rounded-xl border border-dashed border-line-strong bg-muted px-3 py-2 text-sm">
      <p className="flex flex-wrap items-center gap-2 text-xs text-fg-muted">
        <GitBranch size={14} className="shrink-0 text-fg-faint" aria-hidden="true" />
        <span>{t.result(item.task)}</span>
        <OutcomeBadge outcome={item.outcome} waiting={false} />
      </p>
      {item.text !== undefined && item.text.trim() !== "" ? (
        <p className="mt-1 whitespace-pre-wrap break-words text-fg">{item.text}</p>
      ) : (
        item.outcome === "COMPLETED" && <p className="mt-1 text-fg-faint">{t.noText}</p>
      )}
    </section>
  );
}
