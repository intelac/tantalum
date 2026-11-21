import { Activity, House, LogOut, PanelLeftClose, Puzzle, Settings, SquarePen } from "lucide-react";
import type { Project, SessionSummary } from "../api/types";
import { ProjectSidebar } from "../projects/ProjectSidebar";
import { useText } from "../text";
import { Button } from "../ui/Button";
import { Brand } from "./Brand";
import { SessionRow } from "./SessionRow";
import { useViewedUser } from "../api/viewing";

/**
 * 主窗口（dispatch 需求 1）：每人一个、永不结束，编号 {@code main-用户名}，服务器按相等认出它。只留最近一段原话，
 * 更早的分小批移出、换成一块对话状态，移出去的靠记忆找回。以前按天的主窗口照旧在会话列表里。
 */
export function mainWindowId(username: string): string {
  return `main-${username}`;
}

interface Props {
  sessions: readonly SessionSummary[];
  projects: readonly Project[];
  loading: boolean;
  error: string | null;
  currentSession: string | null;
  rail: boolean;
  onToggle: () => void;
  onNewSession: () => void;
  onNewSessionIn: (project: Project) => void;
  onAddProject: () => void;
  onSelectSession: (id: string) => void;
  onSettings: () => void;
  onPlugins: () => void;
  /** 打开观测页面（observe-page 需求 1）；没有时不显示入口。 */
  onObserve?: () => void;
  /** 现在在观测页面上。 */
  observing?: boolean;
  onLogout: () => void;
  username: string;
}

/**
 * 侧栏：顶部品牌和收起按钮，“新会话”按钮，按工作区分组的会话（workspaces 需求 3、4），
 * 底部插件、设置直接露出，最下面是用户名和退出（W14）。图标栏模式只留图标。
 */
export function Sidebar({
  sessions,
  projects,
  loading,
  error,
  currentSession,
  rail,
  onToggle,
  onNewSession,
  onNewSessionIn,
  onAddProject,
  onSelectSession,
  onSettings,
  onPlugins,
  onObserve,
  observing = false,
  onLogout,
  username,
}: Props) {
  const t = useText();
  // 管理员只读查看别人的会话时：不能新建，没有插件和设置（那些是自己的），也没有占位的新会话
  const viewing = useViewedUser() !== null;
  // 刚新建、还没提交过的会话不在列表里，先放一行占位。
  const unsaved = !viewing && currentSession !== null && !sessions.some((session) => session.id === currentSession);

  if (rail) {
    return (
      <div className="flex h-full flex-col items-center gap-2 py-3">
        <Button variant="icon" aria-label={t.sidebar.expand} onClick={onToggle}>
          <Brand symbolOnly size="sm" />
        </Button>
        {!viewing && (
          <Button variant="icon" aria-label={t.sidebar.newSession} onClick={onNewSession} title={t.sidebar.newSession}>
            <SquarePen size={18} />
          </Button>
        )}
        <div className="flex-1" />
        {onObserve !== undefined && (
          <Button variant="icon" aria-label={t.sidebar.observe} aria-current={observing ? "page" : undefined} onClick={onObserve} title={t.sidebar.observe}>
            <Activity size={18} />
          </Button>
        )}
        {!viewing && (
          <>
            <Button variant="icon" aria-label={t.sidebar.plugins} onClick={onPlugins} title={t.sidebar.plugins}>
              <Puzzle size={18} />
            </Button>
            <Button variant="icon" aria-label={t.sidebar.settings} onClick={onSettings} title={t.sidebar.settings}>
              <Settings size={18} />
            </Button>
          </>
        )}
      </div>
    );
  }

  return (
    <div className="flex h-full min-h-0 flex-col">
      <div className="flex h-14 shrink-0 items-center justify-between px-3">
        <Brand />
        <Button variant="icon" size="sm" aria-label={t.sidebar.collapse} onClick={onToggle}>
          <PanelLeftClose size={18} />
        </Button>
      </div>
      {!viewing && (
        <div className="px-3 pb-3">
          <Button variant="secondary" className="w-full" onClick={onNewSession}>
            <SquarePen size={16} />
            {t.sidebar.newSession}
          </Button>
          <Button
            variant="ghost"
            className="mt-1 w-full justify-start"
            aria-current={currentSession === mainWindowId(username) ? "page" : undefined}
            title={t.sidebar.mainWindowHint}
            onClick={() => onSelectSession(mainWindowId(username))}
          >
            <House size={16} />
            {t.sidebar.mainWindow}
          </Button>
        </div>
      )}
      <ProjectSidebar
        sessions={sessions}
        projects={projects}
        currentSession={currentSession}
        onSelectSession={onSelectSession}
        onNewSessionIn={onNewSessionIn}
        onAddProject={onAddProject}
        pinned={
          unsaved ? (
            <SessionRow
              session={{ id: currentSession, title: "", lastActivity: new Date().toISOString(), runStatus: "idle" }}
              selected
              onSelect={onSelectSession}
            />
          ) : undefined
        }
      />
      {loading && sessions.length === 0 && <p className="px-6 py-2 text-xs text-fg-faint">{t.common.loading}</p>}
      {error !== null && (
        <p role="alert" className="px-6 py-2 text-xs text-danger">
          {error}
        </p>
      )}
      {!loading && error === null && sessions.length === 0 && projects.length === 0 && !unsaved && (
        <p className="px-6 py-2 text-xs text-fg-faint">{t.sidebar.empty}</p>
      )}
      <div className="flex shrink-0 flex-col gap-0.5 border-t border-line px-3 py-2">
        {/* 只读查看时也能看被查看的人的运行 */}
        {onObserve !== undefined && (
          <Button variant="ghost" size="sm" className="justify-start" aria-current={observing ? "page" : undefined} onClick={onObserve}>
            <Activity size={16} />
            {t.sidebar.observe}
          </Button>
        )}
        {!viewing && (
          <>
            <Button variant="ghost" size="sm" className="justify-start" onClick={onPlugins}>
              <Puzzle size={16} />
              {t.sidebar.plugins}
            </Button>
            <Button variant="ghost" size="sm" className="justify-start" onClick={onSettings}>
              <Settings size={16} />
              {t.sidebar.settings}
            </Button>
          </>
        )}
        <div className="flex h-8 items-center gap-2 px-2">
          <span className="min-w-0 flex-1 truncate text-xs text-fg-faint" title={username}>
            {username}
          </span>
          <Button variant="icon" size="sm" aria-label={t.sidebar.logout} title={t.sidebar.logout} onClick={onLogout}>
            <LogOut size={14} />
          </Button>
        </div>
      </div>
    </div>
  );
}
