// 路由、登录状态、SSE 和框架。对话区从这里拿到 store。

import { useQueryClient } from "@tanstack/react-query";
import { useEffect, useState } from "react";
import { LoginPage } from "../auth/LoginPage";
import { useCurrentUser } from "../auth/useCurrentUser";
import { ComposerDock } from "../composer/ComposerDock";
import { useSessionModel } from "../store/hooks";
import { readChoice } from "../composer/drafts";
import { pickChoice, sentChoice, useModels } from "../composer/useModels";
import { ConversationPage } from "../conversation/ConversationPage";
import { PluginsDialog } from "../plugins/PluginsDialog";
import { SettingsDialog } from "../settings/SettingsDialog";
import { AppFrame } from "../shell/AppFrame";
import { ConnectionBanner } from "../shell/ConnectionBanner";
import { sessionTitle } from "../shell/SessionRow";
import { Sidebar } from "../shell/Sidebar";
import { useConfig } from "../shell/useConfig";
import { useSessions } from "../shell/useSessions";
import { ConversationStore } from "../store/ConversationStore";
import { zh } from "../text/zh";
import { StatsPage } from "../stats/StatsPage";
import { TracePage } from "../trace/TracePage";
import { Tabs } from "../ui/Tabs";
import { useEventStream } from "../store/events";
import { fetchOlderTrace } from "../trace/hooks";
import { TraceStore } from "../trace/TraceStore";
import { loginPath, navigate, sessionPath, statsPath, tracePath, useRoute, type Route } from "./router";

// 页签 ID 就是路由的页面名，选中状态直接比较。
const TABS = [
  { id: "session", label: zh.tabs.conversation },
  { id: "trace", label: zh.tabs.trace },
  { id: "stats", label: zh.tabs.stats },
] as const;

function tabPath(id: string, session: string): string {
  if (id === "trace") return tracePath(session);
  if (id === "stats") return statsPath(session);
  return sessionPath(session);
}

/** 新会话只是一个新 ID；后端在第一次提交时才创建它。 */
export function newSessionId(): string {
  return `s-${crypto.randomUUID().slice(0, 8)}`;
}

export function App() {
  const route = useRoute();
  const { user, loading, setUser, logout } = useCurrentUser();

  // 未登录：记住要去的页面，登录后回去。
  useEffect(() => {
    if (loading || user !== null || route.page === "login") return;
    navigate(loginPath(window.location.pathname + window.location.search), true);
  }, [loading, user, route.page]);

  // 已登录但在登录页或首页：去会话。
  useEffect(() => {
    if (loading || user === null) return;
    if (route.page === "login") navigate(route.next ?? sessionPath(newSessionId()), true);
    else if (route.page === "home" || route.page === "notFound") navigate(sessionPath(newSessionId()), true);
  }, [loading, user, route]);

  if (loading) return null;
  if (user === null) return <LoginPage onLoggedIn={setUser} />;
  if (route.page !== "session" && route.page !== "trace" && route.page !== "stats") return null;
  return <Workspace route={route} username={user.username} onLogout={() => void logout()} />;
}

interface WorkspaceProps {
  route: Extract<Route, { page: "session" | "trace" | "stats" }>;
  username: string;
  onLogout: () => void;
}

function Workspace({ route, username, onLogout }: WorkspaceProps) {
  const queries = useQueryClient();
  const [store] = useState(() => new ConversationStore());
  const [trace] = useState(() => new TraceStore(fetchOlderTrace));
  useEventStream(store, queries, trace);
  const sessions = useSessions();
  const config = useConfig();
  const models = useModels();
  const sessionModel = useSessionModel(store, route.session);
  // 设置页按当前会话选的模型算压缩点：没发出的选择 → 刚发出的 → 会话现在的 → 上次的 → 默认
  const settingsChoice = pickChoice(readChoice(route.session), sentChoice(route.session) ?? sessionModel.model, models.data);
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [pluginsOpen, setPluginsOpen] = useState(false);
  const list = sessions.data ?? [];
  const summary = list.find((session) => session.id === route.session);
  const title = sessionTitle(summary);

  return (
    <AppFrame
      header={
        <div className="flex min-w-0 flex-1 items-center gap-4">
          <h1 className="min-w-0 flex-1 truncate text-sm font-medium" title={title}>
            {title}
          </h1>
          <Tabs
            label={zh.tabs.conversation}
            tabs={TABS}
            selected={route.page}
            onSelect={(id) => navigate(tabPath(id, route.session))}
            className="h-12"
          />
        </div>
      }
      sidebar={({ rail, toggle, close }) => (
        <Sidebar
          sessions={list}
          loading={sessions.isPending}
          error={sessions.error?.message ?? null}
          currentSession={route.session}
          rail={rail}
          onToggle={toggle}
          username={username}
          onNewSession={() => {
            close();
            navigate(sessionPath(newSessionId()));
          }}
          onSelectSession={(id) => {
            close();
            if (id !== route.session) navigate(sessionPath(id));
          }}
          onSettings={() => setSettingsOpen(true)}
          onPlugins={() => setPluginsOpen(true)}
          onLogout={onLogout}
        />
      )}
    >
      <div className="flex h-full min-h-0 flex-col">
        <ConnectionBanner />
        <SettingsDialog
          open={settingsOpen}
          onClose={() => setSettingsOpen(false)}
          config={config.data}
          choice={settingsChoice}
          username={username}
          onLogout={onLogout}
        />
        <PluginsDialog open={pluginsOpen} onClose={() => setPluginsOpen(false)} />
        <div className="min-h-0 flex-1">
          {route.page === "trace" ? (
            <TracePage key={route.session} store={trace} session={route.session} run={route.run} record={route.record} />
          ) : route.page === "stats" ? (
            <StatsPage key={route.session} store={trace} session={route.session} />
          ) : (
            <div className="flex h-full min-h-0 flex-col">
              <ConversationPage store={store} session={route.session} />
              <ComposerDock store={store} session={route.session} config={config.data} />
            </div>
          )}
        </div>
      </div>
    </AppFrame>
  );
}
