import { Archive, ArchiveRestore, CircleHelp, MoreHorizontal, Pencil } from "lucide-react";
import type { SessionSummary } from "../api/types";
import { relativeTime } from "../text/format";
import { type Text, useText } from "../text";
import { cx } from "../ui/cx";
import { Menu } from "../ui/Menu";
import { useViewedUser } from "../api/viewing";

interface Props {
  session: SessionSummary;
  selected: boolean;
  onSelect: (id: string) => void;
  now?: Date;
  /** 有这两个时行尾多一个菜单：改名、归档或取消归档（workspaces 需求 5、6）。双击标题也是改名。 */
  onRename?: (session: SessionSummary) => void;
  onArchive?: (session: SessionSummary, archived: boolean) => void;
  /** 搜索结果里在标题下写工作区名。 */
  detail?: string;
}

/** 会话的显示标题：还没有第一条消息时是“新会话”。 */
export function sessionTitle(session: SessionSummary | undefined, t: Text): string {
  return session === undefined || session.title === "" ? t.sidebar.untitled : session.title;
}

/**
 * 一行会话：标题，右侧相对时间；运行中显示跳动的点；停在等你回答时显示“待回答”（waiting-state 需求 7.3）。
 * 已归档的变灰，时间换成“已归档”。
 */
export function SessionRow({ session, selected, onSelect, now, onRename, onArchive, detail }: Props) {
  const t = useText();
  const waiting = session.awaitingAnswer === true;
  const running = !waiting && session.runStatus !== "idle";
  const archived = session.archived === true;
  const title = sessionTitle(session, t);
  // 只读查看别人的会话时不能改名、归档
  const viewing = useViewedUser() !== null;
  const menu =
    !viewing && onRename !== undefined && onArchive !== undefined
      ? [
          { id: "rename", label: t.projects.rename, icon: <Pencil size={16} />, onSelect: () => onRename(session) },
          archived
            ? { id: "unarchive", label: t.projects.unarchive, icon: <ArchiveRestore size={16} />, onSelect: () => onArchive(session, false) }
            : { id: "archive", label: t.projects.archive, icon: <Archive size={16} />, onSelect: () => onArchive(session, true) },
        ]
      : null;
  return (
    <li className="group relative">
      <button
        type="button"
        aria-current={selected ? "page" : undefined}
        onClick={() => onSelect(session.id)}
        onDoubleClick={viewing || onRename === undefined ? undefined : () => onRename(session)}
        title={title}
        className={cx(
          "flex w-full items-center gap-2 rounded-md px-3 text-left text-sm transition-colors duration-[var(--duration-fast)]",
          detail === undefined ? "h-9" : "py-1.5",
          menu !== null && "pr-9",
          selected ? "bg-active text-fg" : "text-fg hover:bg-hover",
          archived && "text-fg-faint",
        )}
      >
        <span className="flex min-w-0 flex-1 flex-col">
          <span className="truncate">{title}</span>
          {detail !== undefined && <span className="truncate text-xs text-fg-faint">{detail}</span>}
        </span>
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
          <span className="shrink-0 text-xs text-fg-faint">{archived ? t.projects.archived : relativeTime(session.lastActivity, now)}</span>
        )}
      </button>
      {menu !== null && (
        // 菜单按钮平时藏着，悬停、键盘聚焦或选中时出现。这一层有 translate，自成一层叠放：菜单开着（焦点在里面）时整层提到最上，
        // 否则往左展开的菜单盖不过下面的行，点“归档”点到的是下一行
        <div className="absolute top-1/2 right-1 -translate-y-1/2 opacity-0 group-focus-within:opacity-100 group-hover:opacity-100 focus-within:z-50">
          <Menu label={t.projects.sessionMenu(title)} items={menu} align="end" buttonProps={{ variant: "icon", size: "sm" }}>
            <MoreHorizontal size={14} />
          </Menu>
        </div>
      )}
    </li>
  );
}
