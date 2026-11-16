import { Check, Plus, Search, SlidersHorizontal, X } from "lucide-react";
import { type ReactNode, useEffect, useMemo, useRef, useState } from "react";
import type { Project, SessionSummary } from "../api/types";
import { SessionRow, sessionTitle } from "../shell/SessionRow";
import { useText } from "../text";
import { errorLine } from "../text/errors";
import { Button } from "../ui/Button";
import { Dialog } from "../ui/Dialog";
import { Menu } from "../ui/Menu";
import { PAGE, groupSessions, indentedIn, mergeHits, searchSessions } from "./group";
import { useContentSearch } from "./useContentSearch";
import { ProjectGroup } from "./ProjectGroup";
import { RenameDialog } from "./RenameDialog";
import { useDeleteProject, useRenameProject, useRenameSession, useSetArchived, useSetPinned } from "./useProjects";
import { useCollapsed, useViewOptions } from "./viewState";
import { useViewedUser } from "../api/viewing";

interface Props {
  sessions: readonly SessionSummary[];
  projects: readonly Project[];
  currentSession: string | null;
  onSelectSession: (id: string) => void;
  /** 在某个工作区里新建会话（菜单“在此新建”）。 */
  onNewSessionIn: (project: Project) => void;
  onAddProject: () => void;
  /** 列表最上面的一行：刚新建、还没发出第一句的会话。 */
  pinned?: ReactNode;
}

/** 撤销提示留多久。 */
const NOTICE_MS = 6000;

type Renaming = { kind: "session"; session: SessionSummary } | { kind: "project"; project: Project };

/**
 * 侧栏的会话区（workspaces 需求 3、4、5、6）：搜索框，“工作区”一行（视图选项、添加），按工作区分的组；
 * 有搜索词时换成平铺的结果。改名、归档、删工作区的框和归档后的“撤销”提示都在这里。
 */
export function ProjectSidebar({ sessions, projects, currentSession, onSelectSession, onNewSessionIn, onAddProject, pinned }: Props) {
  const t = useText();
  const [view, setView] = useViewOptions();
  const [collapsed, toggle] = useCollapsed();
  const [extra, setExtra] = useState<Record<string, number>>({});
  const [query, setQuery] = useState("");
  const [renaming, setRenaming] = useState<Renaming | null>(null);
  const [deleting, setDeleting] = useState<Project | null>(null);
  const [notice, setNotice] = useState<{ text: string; undo?: () => void } | null>(null);
  const search = useRef<HTMLInputElement>(null);
  const renameSession = useRenameSession();
  const renameProject = useRenameProject();
  const deleteProject = useDeleteProject();
  const archive = useSetArchived();
  const pin = useSetPinned();
  const content = useContentSearch(query);
  // 只读查看别人的会话时不能加工作区（行上的改名、归档在 SessionRow、ProjectGroup 里关掉）
  const viewing = useViewedUser() !== null;

  useEffect(() => {
    if (notice === null) return;
    const timer = window.setTimeout(() => setNotice(null), NOTICE_MS);
    return () => window.clearTimeout(timer);
  }, [notice]);

  const groups = useMemo(() => groupSessions(sessions, projects, view, extra, currentSession), [sessions, projects, view, extra, currentSession]);
  const flatIndented = useMemo(() => indentedIn(groups[0]?.rows ?? []), [groups]);
  const found =
    query.trim() === "" ? null : mergeHits(searchSessions(sessions, projects, query, view.showArchived), content.result, sessions, projects, view.showArchived);

  function onArchive(session: SessionSummary, archived: boolean): void {
    if (archived && (session.runStatus !== "idle" || session.awaitingAnswer === true)) {
      setNotice({ text: t.projects.archiveBusy });
      return;
    }
    archive.mutate(
      { id: session.id, archived },
      {
        onSuccess: () => {
          if (archived) {
            setNotice({ text: t.projects.archivedNotice(sessionTitle(session, t)), undo: () => archive.mutate({ id: session.id, archived: false }) });
          }
        },
        onError: (failure) => setNotice({ text: errorLine(failure) }),
      },
    );
  }

  const viewItems = [
    { id: "project", label: t.projects.byProject, icon: view.mode === "project" ? <Check size={16} /> : <span className="w-4" />, onSelect: () => setView({ ...view, mode: "project" }) },
    { id: "flat", label: t.projects.flat, icon: view.mode === "flat" ? <Check size={16} /> : <span className="w-4" />, onSelect: () => setView({ ...view, mode: "flat" }) },
    {
      id: "archived",
      label: t.projects.showArchived,
      icon: view.showArchived ? <Check size={16} /> : <span className="w-4" />,
      onSelect: () => setView({ ...view, showArchived: !view.showArchived }),
    },
  ];

  const rowProps = {
    onSelect: onSelectSession,
    onRename: (session: SessionSummary) => setRenaming({ kind: "session", session }),
    onArchive,
    onPin: (session: SessionSummary, pinned: boolean) => pin.mutate({ id: session.id, pinned }, { onError: (failure) => setNotice({ text: errorLine(failure) }) }),
  };

  return (
    <div className="flex min-h-0 flex-1 flex-col">
      <div className="px-3 pb-2">
        <label className="flex h-8 items-center gap-2 rounded-md border border-line bg-raised px-2 text-sm focus-within:border-line-strong">
          <Search size={14} className="shrink-0 text-fg-faint" aria-hidden="true" />
          <input
            ref={search}
            type="search"
            value={query}
            onChange={(event) => setQuery(event.target.value)}
            onKeyDown={(event) => {
              if (event.key === "Escape") setQuery("");
            }}
            placeholder={t.projects.search}
            aria-label={t.projects.search}
            className="focus-plain min-w-0 flex-1 bg-transparent text-fg placeholder:text-fg-faint"
          />
          {query !== "" && (
            <button type="button" aria-label={t.projects.clearSearch} onClick={() => setQuery("")} className="text-fg-faint hover:text-fg">
              <X size={14} />
            </button>
          )}
        </label>
      </div>
      <div className="flex h-8 shrink-0 items-center gap-1 px-3">
        <span className="min-w-0 flex-1 truncate px-2 text-xs text-fg-faint">{t.projects.workspaces}</span>
        <Menu label={t.projects.view} items={viewItems} buttonProps={{ variant: "icon", size: "sm", title: t.projects.view }}>
          <SlidersHorizontal size={14} />
        </Menu>
        {!viewing && (
          <Button variant="ghost" size="sm" onClick={onAddProject}>
            <Plus size={14} />
            {t.projects.add}
          </Button>
        )}
      </div>
      <nav className="min-h-0 flex-1 overflow-y-auto px-3" aria-label={t.sidebar.sessions}>
        {pinned !== undefined && found === null && <ul className="mb-1 flex flex-col">{pinned}</ul>}
        {found !== null ? (
          <ul className="flex flex-col gap-0.5">
            {found.hits.map((hit) => (
              <SessionRow
                key={hit.session.id}
                session={hit.session}
                selected={hit.session.id === currentSession}
                detail={hit.snippet ?? hit.project?.title ?? t.projects.ungrouped}
                {...rowProps}
              />
            ))}
            {content.loading && <li className="px-3 py-2 text-xs text-fg-faint">{t.projects.contentSearching}</li>}
            {content.failed && <li className="px-3 py-2 text-xs text-warning">{t.projects.contentSearchFailed}</li>}
            {found.hits.length === 0 && !content.loading && <li className="px-3 py-2 text-xs text-fg-faint">{t.projects.noResults}</li>}
            {found.more && <li className="px-3 py-2 text-xs text-fg-faint">{t.projects.moreResults}</li>}
          </ul>
        ) : view.mode === "flat" ? (
          <ul className="flex flex-col gap-0.5">
            {groups[0]?.rows.map((session) => (
              <SessionRow key={session.id} session={session} selected={session.id === currentSession} indent={flatIndented.has(session.id)} {...rowProps} />
            ))}
          </ul>
        ) : (
          <ul className="flex flex-col gap-1">
            {groups.map((group) => {
              const project = group.project;
              return (
                <ProjectGroup
                  key={group.key}
                  group={group}
                  collapsed={collapsed.has(group.key)}
                  onToggle={() => toggle(group.key)}
                  currentSession={currentSession}
                  onSelectSession={onSelectSession}
                  onRenameSession={rowProps.onRename}
                  onArchiveSession={onArchive}
                  onPinSession={rowProps.onPin}
                  shownMore={(extra[group.key] ?? 0) > 0}
                  onShowMore={(reset) => setExtra((current) => ({ ...current, [group.key]: reset ? 0 : (current[group.key] ?? 0) + PAGE }))}
                  onNewHere={() => project !== null && onNewSessionIn(project)}
                  onRenameProject={() => project !== null && setRenaming({ kind: "project", project })}
                  onDeleteProject={() => setDeleting(project)}
                />
              );
            })}
          </ul>
        )}
      </nav>
      {notice !== null && (
        <div role="status" className="mx-3 mb-2 flex items-center gap-2 rounded-md bg-muted px-3 py-2 text-xs text-fg">
          <span className="min-w-0 flex-1">{notice.text}</span>
          {notice.undo !== undefined && (
            <button
              type="button"
              className="shrink-0 font-medium text-accent"
              onClick={() => {
                notice.undo?.();
                setNotice(null);
              }}
            >
              {t.projects.undo}
            </button>
          )}
        </div>
      )}
      <RenameDialog
        open={renaming !== null}
        title={renaming?.kind === "project" ? t.projects.renameProjectTitle : t.projects.renameSessionTitle}
        hint={renaming?.kind === "session" ? t.projects.renameSessionHint : undefined}
        initial={renaming === null ? "" : renaming.kind === "project" ? renaming.project.title : renaming.session.title}
        allowEmpty={renaming?.kind === "session"}
        onClose={() => setRenaming(null)}
        onSave={(name) =>
          renaming?.kind === "project"
            ? renameProject.mutateAsync({ id: renaming.project.id, title: name })
            : renameSession.mutateAsync({ id: renaming?.kind === "session" ? renaming.session.id : "", title: name })
        }
      />
      <Dialog open={deleting !== null} onClose={() => setDeleting(null)} title={deleting === null ? "" : t.projects.deleteTitle(deleting.title)} className="max-w-md">
        <div className="px-4 pt-2 pb-6 sm:px-6">
          <p className="text-sm text-fg-muted">{t.projects.deleteHint}</p>
          {deleteProject.error !== null && (
            <p role="alert" className="mt-2 text-xs text-danger">
              {errorLine(deleteProject.error)}
            </p>
          )}
          <div className="mt-4 flex justify-end gap-2">
            <Button variant="ghost" onClick={() => setDeleting(null)}>
              {t.common.cancel}
            </Button>
            <Button
              variant="danger"
              disabled={deleteProject.isPending}
              onClick={() => deleting !== null && deleteProject.mutate(deleting.id, { onSuccess: () => setDeleting(null) })}
            >
              {t.projects.delete}
            </Button>
          </div>
        </div>
      </Dialog>
    </div>
  );
}
