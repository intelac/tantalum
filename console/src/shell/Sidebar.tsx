import { LogOut, MoreHorizontal, PanelLeftClose, PanelLeftOpen, Settings, SquarePen } from "lucide-react";
import type { SessionSummary } from "../api/types";
import { zh } from "../text/zh";
import { Button } from "../ui/Button";
import { cx } from "../ui/cx";
import { Menu } from "../ui/Menu";
import { Brand } from "./Brand";
import { SessionRow } from "./SessionRow";

interface Props {
  sessions: readonly SessionSummary[];
  loading: boolean;
  error: string | null;
  currentSession: string | null;
  rail: boolean;
  onToggle: () => void;
  onNewSession: () => void;
  onSelectSession: (id: string) => void;
  onSettings: () => void;
  onLogout: () => void;
  username: string;
}

/** 侧栏：顶部品牌和收起按钮，“新会话”按钮，会话列表，底部“更多”菜单。图标栏模式只留图标。 */
export function Sidebar({
  sessions,
  loading,
  error,
  currentSession,
  rail,
  onToggle,
  onNewSession,
  onSelectSession,
  onSettings,
  onLogout,
  username,
}: Props) {
  const current = currentSession !== null && !sessions.some((session) => session.id === currentSession);
  const menuItems = [
    { id: "settings", label: zh.sidebar.settings, icon: <Settings size={16} />, onSelect: onSettings },
    { id: "logout", label: zh.sidebar.logout, icon: <LogOut size={16} />, danger: true, onSelect: onLogout },
  ];

  if (rail) {
    return (
      <div className="flex h-full flex-col items-center gap-2 py-3">
        <Button variant="icon" aria-label={zh.sidebar.expand} onClick={onToggle}>
          <Brand symbolOnly size="sm" />
        </Button>
        <Button variant="icon" aria-label={zh.sidebar.newSession} onClick={onNewSession} title={zh.sidebar.newSession}>
          <SquarePen size={18} />
        </Button>
        <div className="flex-1" />
        <Menu label={zh.sidebar.more} items={menuItems} direction="up" buttonProps={{ variant: "icon", title: username }}>
          <MoreHorizontal size={18} />
        </Menu>
      </div>
    );
  }

  return (
    <div className="flex h-full min-h-0 flex-col">
      <div className="flex h-14 shrink-0 items-center justify-between px-3">
        <Brand />
        <Button variant="icon" size="sm" aria-label={zh.sidebar.collapse} onClick={onToggle}>
          <PanelLeftClose size={18} />
        </Button>
      </div>
      <div className="px-3 pb-3">
        <Button variant="secondary" className="w-full" onClick={onNewSession}>
          <SquarePen size={16} />
          {zh.sidebar.newSession}
        </Button>
      </div>
      <nav className="min-h-0 flex-1 overflow-y-auto px-3" aria-label={zh.sidebar.sessions}>
        <ul className="flex flex-col gap-0.5">
          {current && (
            <SessionRow
              session={{ id: currentSession, title: "", lastActivity: new Date().toISOString(), runStatus: "idle", pendingRecovery: false }}
              selected
              onSelect={onSelectSession}
            />
          )}
          {sessions.map((session) => (
            <SessionRow key={session.id} session={session} selected={session.id === currentSession} onSelect={onSelectSession} />
          ))}
        </ul>
        {loading && sessions.length === 0 && <p className="px-3 py-2 text-xs text-fg-faint">{zh.common.loading}</p>}
        {error !== null && (
          <p role="alert" className="px-3 py-2 text-xs text-danger">
            {error}
          </p>
        )}
        {!loading && error === null && sessions.length === 0 && !current && (
          <p className="px-3 py-2 text-xs text-fg-faint">{zh.sidebar.empty}</p>
        )}
      </nav>
      <div className={cx("flex h-12 shrink-0 items-center justify-between px-3")}>
        <Menu label={zh.sidebar.more} items={menuItems} direction="up" buttonProps={{ variant: "ghost", size: "sm" }}>
          <MoreHorizontal size={16} />
          {zh.sidebar.more}
        </Menu>
        <span className="truncate text-xs text-fg-faint" title={username}>
          {username}
        </span>
        <PanelLeftOpen className="hidden" size={0} aria-hidden="true" />
      </div>
    </div>
  );
}
