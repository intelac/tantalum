// 路由、登录状态、SSE 和框架。对话区从这里拿到 store。

import { useQueryClient } from "@tanstack/react-query";
import { useEffect, useRef, useState } from "react";
import { setViewedUser, useViewedUser } from "../api/viewing";
import { LoginPage } from "../auth/LoginPage";
import { ME_KEY, useCurrentUser } from "../auth/useCurrentUser";
import { ComposerDock } from "../composer/ComposerDock";
import { useSessionModel } from "../store/hooks";
import { readChoice } from "../composer/drafts";
import { pickChoice, sentChoice, useModels } from "../composer/useModels";
import { ConversationPage } from "../conversation/ConversationPage";
import { PluginsDialog } from "../plugins/PluginsDialog";
import { AddProjectDialog } from "../projects/AddProjectDialog";
import { projectOf } from "../projects/group";
import { useProjects } from "../projects/useProjects";
import { savePending } from "../projects/viewState";
import { SettingsDialog } from "../settings/SettingsDialog";
import { AppFrame } from "../shell/AppFrame";
import { ConnectionBanner } from "../shell/ConnectionBanner";
import { ViewingBanner, ViewingBar } from "../shell/ViewingBanner";
import { SessionPlan } from "../conversation/PlanBar";
import { sessionTitle } from "../shell/SessionRow";
import { Sidebar } from "../shell/Sidebar";
import { useConfig } from "../shell/useConfig";
import { useSessions } from "../shell/useSessions";
import { ConversationStore } from "../store/ConversationStore";
import { useText } from "../text";
import { StatsPage } from "../stats/StatsPage";
import { TracePage } from "../trace/TracePage";
import { Tabs } from "../ui/Tabs";
import { useEventStream } from "../store/events";
import { fetchOlderTrace } from "../trace/hooks";
import { TraceStore } from "../trace/TraceStore";
import { loginPath, navigate, newSessionId, runsPath, sessionPath, statsPath, tracePath, useRoute, type Route } from "./router";
import { ArrowLeft } from "lucide-react";
import { ObservePage } from "../observe/ObservePage";
import { RunPage } from "../observe/RunPage";
import { Button } from "../ui/Button";
import { readStored, sessionArea, writeStored } from "../ui/storage";

/** 上次打开的会话：观测页面的“返回对话”回到这里（这个标签页内）。 */
const LAST_SESSION_KEY = "tantalum.lastSession";
import { errorLine } from "../text/errors";

// 页签 ID 就是路由的页面名，选中状态直接比较。值是页签名在 t.tabs 里的键，渲染时按当前语言取。
const TABS = [
  { id: "session", key: "conversation" },
  { id: "trace", key: "trace" },
  { id: "stats", key: "stats" },
] as const;

function tabPath(id: string, session: string): string {
  if (id === "trace") return tracePath(session);
  if (id === "stats") return statsPath(session);
  return sessionPath(session);
}

export { newSessionId } from "./router";

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

  // 管理员只读查看别人的会话：进入和退出时都把会话数据的缓存清掉、回到首页，下面的工作区整个重新挂载
  // （对话、轨迹、事件流都重来），两个人的数据不会混在一起。查看状态只在内存里，刷新就回到自己。
  const viewed = useViewedUser();
  const queries = useQueryClient();
  const shown = useRef(viewed);
  useEffect(() => {
    if (shown.current === viewed) return;
    shown.current = viewed;
    queries.removeQueries({ predicate: (query) => query.queryKey[0] !== ME_KEY[0] });
    navigate(sessionPath(newSessionId()), true);
  }, [viewed, queries]);
  // 退出登录时不留在查看状态里
  useEffect(() => {
    if (user === null) setViewedUser(null);
  }, [user]);

  if (loading) return null;
  if (user === null) return <LoginPage onLoggedIn={setUser} />;
  if (route.page !== "session" && route.page !== "trace" && route.page !== "stats" && route.page !== "runs" && route.page !== "run") return null;
  return (
    <Workspace key={viewed ?? ""} route={route} username={user.username} admin={user.admin === true} viewed={viewed} onLogout={() => void logout()} />
  );
}

interface WorkspaceProps {
  route: Extract<Route, { page: "session" | "trace" | "stats" | "runs" | "run" }>;
  username: string;
  admin: boolean;
  /** 管理员正在只读查看的人；没有是 null。 */
  viewed: string | null;
  onLogout: () => void;
}

function Workspace({ route, username, admin, viewed, onLogout }: WorkspaceProps) {
  const t = useText();
  // 观测页面不属于某个会话：侧栏、设置和事件流照常用上次打开的会话（observe-page 需求 1.3）
  const observing = route.page === "runs" || route.page === "run";
  const [fallback] = useState(() => readStored(LAST_SESSION_KEY, sessionArea()) ?? newSessionId());
  const session = route.page === "runs" ? fallback : route.session;
  useEffect(() => {
    if (!observing) writeStored(LAST_SESSION_KEY, session, sessionArea());
  }, [observing, session]);
  const queries = useQueryClient();
  const [store] = useState(() => new ConversationStore());
  const [trace] = useState(() => new TraceStore(fetchOlderTrace));
  useEventStream(store, queries, trace);
  const sessions = useSessions();
  const config = useConfig();
  const models = useModels();
  const sessionModel = useSessionModel(store, session);
  // 设置页按当前会话选的模型算压缩点：没发出的选择 → 刚发出的 → 会话现在的 → 上次的 → 默认
  const settingsChoice = pickChoice(readChoice(session), sentChoice(session) ?? sessionModel.model, models.data);
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [pluginsOpen, setPluginsOpen] = useState(false);
  const [addOpen, setAddOpen] = useState(false);
  const projects = useProjects();
  const projectList = projects.data ?? [];
  const list = sessions.data ?? [];
  const summary = list.find((item) => item.id === session);
  const title = sessionTitle(summary, t);
  const tabs = TABS.map(({ id, key }) => ({ id, label: t.tabs[key] }));
  // 刚开始查看一个人时地址上是一个新的空会话：直接打开他最近的那个
  const first = list[0]?.id;
  useEffect(() => {
    if (viewed !== null && summary === undefined && first !== undefined && route.page === "session") navigate(sessionPath(first), true);
  }, [viewed, summary, first, route.page]);

  return (
    <AppFrame
      header={
        observing ? (
          <div className="flex min-w-0 flex-1 items-center gap-3">
            <h1 className="min-w-0 flex-1 truncate text-sm font-medium">{route.page === "run" ? t.observe.runTitle : t.observe.title}</h1>
            <Button variant="ghost" size="sm" onClick={() => navigate(sessionPath(route.page === "run" ? route.session : fallback))}>
              <ArrowLeft size={16} />
              {t.observe.back}
            </Button>
          </div>
        ) : (
        <div className="flex min-w-0 flex-1 items-center gap-4">
          <h1 className="min-w-0 flex-1 truncate text-sm font-medium" title={title}>
            {title}
          </h1>
          <Tabs
            label={t.tabs.conversation}
            tabs={tabs}
            selected={route.page}
            onSelect={(id) => navigate(tabPath(id, session))}
            className="h-12"
          />
        </div>
        )
      }
      sidebar={({ rail, toggle, close }) => (
        <Sidebar
          sessions={list}
          loading={sessions.isPending}
          error={sessions.error === null ? null : errorLine(sessions.error)}
          // 观测页面不属于某个会话：侧栏不选中、也不放新会话的占位
          currentSession={route.page === "runs" ? null : session}
          rail={rail}
          onToggle={toggle}
          username={username}
          projects={projectList}
          onNewSession={() => {
            close();
            // 新会话默认进当前会话的工作区；当前会话不在工作区里时，输入框旁用上次用的（W11）
            const id = newSessionId();
            const here = summary === undefined ? null : projectOf(summary, projectList);
            if (here !== null) savePending(id, here.id);
            navigate(sessionPath(id));
          }}
          onNewSessionIn={(project) => {
            close();
            const id = newSessionId();
            savePending(id, project.id);
            navigate(sessionPath(id));
          }}
          onAddProject={() => setAddOpen(true)}
          onSelectSession={(id) => {
            close();
            if (id !== session) navigate(sessionPath(id));
          }}
          onSettings={() => setSettingsOpen(true)}
          onPlugins={() => setPluginsOpen(true)}
          onObserve={() => {
            close();
            navigate(runsPath());
          }}
          observing={observing}
          onLogout={onLogout}
        />
      )}
    >
      <div className="flex h-full min-h-0 flex-col">
        <ViewingBanner />
        <ConnectionBanner />
        <SettingsDialog
          open={settingsOpen}
          onClose={() => setSettingsOpen(false)}
          config={config.data}
          choice={settingsChoice}
          username={username}
          admin={admin}
          onLogout={onLogout}
        />
        <PluginsDialog open={pluginsOpen} onClose={() => setPluginsOpen(false)} admin={admin} />
        <AddProjectDialog
          open={addOpen}
          onClose={() => setAddOpen(false)}
          // 还没发出第一句的会话直接用刚加的工作区
          onAdded={(project) => {
            if (summary === undefined) savePending(session, project.id);
          }}
        />
        <div className="min-h-0 flex-1">
          {route.page === "runs" ? (
            <ObservePage query={route.query} />
          ) : route.page === "run" ? (
            <RunPage key={`${route.session}/${route.run}`} session={route.session} run={route.run} />
          ) : route.page === "trace" ? (
            <TracePage key={session} store={trace} session={session} run={route.run} record={route.record} />
          ) : route.page === "stats" ? (
            <StatsPage key={session} store={trace} session={session} />
          ) : (
            <div className="flex h-full min-h-0 flex-col">
              <ConversationPage store={store} session={session} />
              {/* 计划横条（todo-tool 需求 3）：只读查看时也显示 */}
              <SessionPlan store={store} session={session} />
              {viewed !== null ? (
                // 只读：没有输入框，也就没有排队、批准卡片、模型选择和压缩
                <ViewingBar selected={summary !== undefined} any={list.length > 0} />
              ) : (
                <ComposerDock
                  store={store}
                  session={session}
                  config={config.data}
                  onOpenPlugins={() => setPluginsOpen(true)}
                  onAddProject={() => setAddOpen(true)}
                />
              )}
            </div>
          )}
        </div>
      </div>
    </AppFrame>
  );
}
