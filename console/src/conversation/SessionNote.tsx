import { CornerDownLeft, CornerDownRight, TriangleAlert } from "lucide-react";
import { useState } from "react";
import type { UserItem } from "../api/types";
import { navigate, sessionPath } from "../app/router";
import { Markdown } from "../markdown/Markdown";
import { sessionTitle } from "../shell/SessionRow";
import { useSessions } from "../shell/useSessions";
import { clockTime } from "../text/format";
import { useText } from "../text";
import { CopyButton } from "../ui/CopyButton";

/** 超过这么多行先收起。 */
const FOLD_LINES = 12;

interface Props {
  item: UserItem & { from: NonNullable<UserItem["from"]> };
}

/**
 * 另一个会话送来的一句话：交来的活，或者对交出去的活的回话。它不是用户说的，所以不是右边的气泡：左对齐的带边框一块，
 * 第一行写明是哪个会话送来的（点它打开那个会话），下面是正文（模型写的，按 Markdown 显示），长的先收起。
 */
export function SessionNote({ item }: Props) {
  const t = useText();
  const sessions = useSessions();
  const [open, setOpen] = useState(false);
  const { session, reply, failed } = item.from;
  const known = sessions.data?.find((each) => each.id === session);
  const name = session.startsWith("main-") ? t.sidebar.mainWindow : known === undefined ? t.conversation.otherSession : sessionTitle(known, t);
  const label = !reply ? t.conversation.handedIn(name) : failed === true ? t.conversation.handedBackFailed(name) : t.conversation.handedBack(name);
  const Icon = !reply ? CornerDownRight : failed === true ? TriangleAlert : CornerDownLeft;
  const lines = item.text.split("\n");
  const long = lines.length > FOLD_LINES;
  const shown = long && !open ? lines.slice(0, FOLD_LINES).join("\n") : item.text;
  return (
    <div data-turn={item.id} data-from-session={session} className="group flex scroll-mt-4 flex-col gap-1 py-2">
      <div className={`rounded-md border bg-muted px-3 py-2 ${failed === true ? "border-warning/50" : "border-line"}`}>
        <button
          type="button"
          onClick={() => navigate(sessionPath(session))}
          title={t.conversation.openSession(name)}
          className="flex max-w-full items-center gap-1.5 rounded-xs text-xs text-fg-muted hover:text-fg hover:underline"
        >
          <Icon size={13} aria-hidden="true" className={failed === true ? "shrink-0 text-warning" : "shrink-0"} />
          <span className="truncate">{label}</span>
        </button>
        {item.text !== "" && (
          <div className="mt-1.5 text-sm">
            <Markdown text={shown} />
          </div>
        )}
        {long && (
          <button
            type="button"
            aria-expanded={open}
            onClick={() => setOpen((current) => !current)}
            className="mt-1 rounded-xs text-xs text-fg-muted hover:text-fg hover:underline"
          >
            {open ? t.conversation.noteCollapse : t.conversation.noteShowAll}
          </button>
        )}
      </div>
      <div className="flex h-6 items-center gap-1 text-xs text-fg-faint">
        <span>{clockTime(item.at)}</span>
        <CopyButton text={item.text} className="opacity-0 transition-opacity group-hover:opacity-100 focus-visible:opacity-100" />
      </div>
    </div>
  );
}
