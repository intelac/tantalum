import { ChevronDown, ChevronRight, Folder, MoreHorizontal, Pencil, SquarePen, Trash2 } from "lucide-react";
import type { SessionSummary } from "../api/types";
import { useViewedUser } from "../api/viewing";
import { SessionRow } from "../shell/SessionRow";
import { useText } from "../text";
import { Menu } from "../ui/Menu";
import { type Group, PAGE, UNGROUPED } from "./group";

interface Props {
  group: Group;
  collapsed: boolean;
  onToggle: () => void;
  currentSession: string | null;
  onSelectSession: (id: string) => void;
  onRenameSession: (session: SessionSummary) => void;
  onArchiveSession: (session: SessionSummary, archived: boolean) => void;
  onPinSession?: (session: SessionSummary, pinned: boolean) => void;
  /** 再显示 {@link PAGE} 条；{@code reset} 为 true 时收回到最初的条数。 */
  onShowMore: (reset: boolean) => void;
  shownMore: boolean;
  onNewHere: () => void;
  onRenameProject: () => void;
  onDeleteProject: () => void;
}

/** 一个工作区的一组：组头（收起、名字、会话数、菜单）、会话行、再显示 N 条（workspaces 需求 3）。“未分组”没有菜单。 */
export function ProjectGroup({
  group,
  collapsed,
  onToggle,
  currentSession,
  onSelectSession,
  onRenameSession,
  onArchiveSession,
  onPinSession,
  onShowMore,
  shownMore,
  onNewHere,
  onRenameProject,
  onDeleteProject,
}: Props) {
  const t = useText();
  const name = group.project?.title ?? t.projects.ungrouped;
  const Chevron = collapsed ? ChevronRight : ChevronDown;
  // 只读查看别人的会话时，工作区不能新建会话、改名、删除
  const viewing = useViewedUser() !== null;
  return (
    <li className="flex flex-col">
      <div className="group relative flex h-8 items-center">
        <button
          type="button"
          aria-expanded={!collapsed}
          aria-label={collapsed ? t.projects.expand(name) : t.projects.collapse(name)}
          onClick={onToggle}
          title={group.project?.path ?? name}
          className="flex h-8 min-w-0 flex-1 items-center gap-1.5 rounded-md px-2 text-left text-xs font-medium text-fg-muted hover:bg-hover"
        >
          <Chevron size={14} className="shrink-0" aria-hidden="true" />
          {group.key !== UNGROUPED && <Folder size={14} className="shrink-0 text-fg-faint" aria-hidden="true" />}
          <span className="min-w-0 flex-1 truncate">{name}</span>
          <span className="shrink-0 tabular-nums text-fg-faint" aria-label={t.projects.count(group.count)}>
            {group.count}
          </span>
        </button>
        {group.project !== null && !viewing && (
          <div className="relative opacity-0 group-focus-within:opacity-100 group-hover:opacity-100 focus-within:z-50">
            <Menu
              label={t.projects.projectMenu(name)}
              align="end"
              buttonProps={{ variant: "icon", size: "sm" }}
              items={[
                { id: "new", label: t.projects.newHere, icon: <SquarePen size={16} />, onSelect: onNewHere },
                { id: "rename", label: t.projects.rename, icon: <Pencil size={16} />, onSelect: onRenameProject },
                { id: "delete", label: t.projects.delete, icon: <Trash2 size={16} />, danger: true, onSelect: onDeleteProject },
              ]}
            >
              <MoreHorizontal size={14} />
            </Menu>
          </div>
        )}
      </div>
      {!collapsed && (
        <ul className="flex flex-col gap-0.5 pl-3">
          {group.rows.map((session) => (
            <SessionRow
              key={session.id}
              session={session}
              selected={session.id === currentSession}
              onSelect={onSelectSession}
              onRename={onRenameSession}
              onArchive={onArchiveSession}
              onPin={onPinSession}
            />
          ))}
          {group.rows.length === 0 && (
            <li className="px-3 py-1 text-xs text-fg-faint">
              {t.projects.empty}
              {/* 空的工作区直接给入口：“在此新建会话”只在“⋯”菜单里时不好找 */}
              <button type="button" onClick={onNewHere} className="ml-1 text-accent hover:underline">
                {t.projects.newHere}
              </button>
            </li>
          )}
          {(group.hidden > 0 || shownMore) && (
            <li>
              <button
                type="button"
                onClick={() => onShowMore(group.hidden === 0)}
                className="px-3 py-1 text-xs text-fg-faint hover:text-fg"
              >
                {group.hidden > 0 ? t.projects.showMore(Math.min(PAGE, group.hidden)) : t.projects.showLess}
              </button>
            </li>
          )}
        </ul>
      )}
    </li>
  );
}
