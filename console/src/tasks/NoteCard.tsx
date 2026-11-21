import { NotebookPen } from "lucide-react";
import { navigate, sessionPath } from "../app/router";
import type { ConversationStore } from "../store/ConversationStore";
import { useItem } from "../store/hooks";
import { useText } from "../text";

interface Props {
  store: ConversationStore;
  id: string;
}

/**
 * 记一笔（dispatch 需求 3）：主窗口开的那个会话里你直接说了话，主窗口记下的一笔。虚线框，不是用户气泡；点过去看那个会话。
 */
export function NoteCard({ store, id }: Props) {
  const item = useItem(store, id);
  const t = useText().tasks;
  if (item?.kind !== "note") return null;
  const href = sessionPath(item.from);
  return (
    <section aria-label={t.note} className="my-1.5 rounded-xl border border-dashed border-line-strong bg-muted px-3 py-2 text-sm">
      <p className="flex flex-wrap items-center gap-2 text-xs text-fg-muted">
        <NotebookPen size={14} className="shrink-0 text-fg-faint" aria-hidden="true" />
        <span>{t.note}</span>
        <a
          href={href}
          onClick={(event) => {
            event.preventDefault();
            navigate(href);
          }}
          className="text-accent underline-offset-2 hover:underline"
        >
          {t.openSession}
        </a>
      </p>
      <p className="mt-1 whitespace-pre-wrap break-words text-fg">{item.text}</p>
    </section>
  );
}
