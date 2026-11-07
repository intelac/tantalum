import { CircleHelp } from "lucide-react";
import type { SessionSummary } from "../api/types";
import { relativeTime } from "../text/format";
import { type Text, useText } from "../text";
import { cx } from "../ui/cx";

interface Props {
  session: SessionSummary;
  selected: boolean;
  onSelect: (id: string) => void;
  now?: Date;
}

/** 会话的显示标题：还没有第一条消息时是“新会话”。 */
export function sessionTitle(session: SessionSummary | undefined, t: Text): string {
  return session === undefined || session.title === "" ? t.sidebar.untitled : session.title;
}

/** 一行会话：标题，右侧相对时间；运行中显示跳动的点；停在等你回答时显示“待回答”（waiting-state 需求 7.3）。 */
export function SessionRow({ session, selected, onSelect, now }: Props) {
  const t = useText();
  const waiting = session.awaitingAnswer === true;
  const running = !waiting && session.runStatus !== "idle";
  const title = sessionTitle(session, t);
  return (
    <li>
      <button
        type="button"
        aria-current={selected ? "page" : undefined}
        onClick={() => onSelect(session.id)}
        title={title}
        className={cx(
          "flex h-9 w-full items-center gap-2 rounded-md px-3 text-left text-sm transition-colors duration-[var(--duration-fast)]",
          selected ? "bg-active text-fg" : "text-fg hover:bg-hover",
        )}
      >
        <span className="min-w-0 flex-1 truncate">{title}</span>
        {waiting && (
          <span className="flex shrink-0 items-center gap-1 text-xs text-warning" aria-label={t.sidebar.awaitingAnswer}>
            <CircleHelp size={12} aria-hidden="true" />
            {t.sidebar.awaitingAnswer}
          </span>
        )}
        {running && (
          <span className="flex shrink-0 items-center gap-1 text-xs text-accent" aria-label={t.sidebar.running}>
            <span className="h-1.5 w-1.5 animate-pulse rounded-full bg-accent" aria-hidden="true" />
            {t.sidebar.running}
          </span>
        )}
        {!running && !waiting && (
          <span className="shrink-0 text-xs text-fg-faint">{relativeTime(session.lastActivity, now)}</span>
        )}
      </button>
    </li>
  );
}
