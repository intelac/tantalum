import type { SessionSummary } from "../api/types";
import { relativeTime } from "../text/time";
import { zh } from "../text/zh";
import { cx } from "../ui/cx";

interface Props {
  session: SessionSummary;
  selected: boolean;
  onSelect: (id: string) => void;
  now?: Date;
}

/** 会话的显示标题：还没有第一条消息时是“新会话”。 */
export function sessionTitle(session: SessionSummary | undefined): string {
  return session === undefined || session.title === "" ? zh.sidebar.untitled : session.title;
}

/** 一行会话：标题，右侧相对时间；运行中显示跳动的点。 */
export function SessionRow({ session, selected, onSelect, now }: Props) {
  const running = session.runStatus !== "idle";
  const title = sessionTitle(session);
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
        {running && (
          <span className="flex shrink-0 items-center gap-1 text-xs text-accent" aria-label={zh.sidebar.running}>
            <span className="h-1.5 w-1.5 animate-pulse rounded-full bg-accent" aria-hidden="true" />
            {zh.sidebar.running}
          </span>
        )}
        {!running && (
          <span className="shrink-0 text-xs text-fg-faint">{relativeTime(session.lastActivity, now)}</span>
        )}
      </button>
    </li>
  );
}
