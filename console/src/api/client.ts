// 控制台只通过公开接口访问后端。身份来自 Cookie 会话；任何接口返回 401 都统一交给登录流程。

import { reportHalted } from "./connection";
import type {
  Account,
  AssignedParams,
  AttachmentRef,
  Config,
  ContextUsage,
  ErrorParam,
  FolderListing,
  Language,
  LocalSkills,
  LocalStatus,
  LocalToken,
  LocalValidity,
  LocalWiki,
  MemoryEntry,
  MemoryList,
  MemoryType,
  MenuSkill,
  Messages,
  ModelChoice,
  ModelsResponse,
  ParamValue,
  PluginDefault,
  PluginDefaultChange,
  PluginKind,
  Plugins,
  Project,
  PanelInfo,
  CaptureState,
  NoteDay,
  NoteDetail,
  RunListPage,
  RunNode,
  RunsSummary,
  RunView,
  SavedParams,
  SessionSummary,
  ContentHit,
  SubmitMode,
  TodayUsageView,
  TraceCall,
  TracePage,
  Turn,
  TurnSummary,
  User,
  UserList,
  UserSettingsView,
} from "./types";
import { asViewed, setViewedUser, viewedUser } from "./viewing";

/**
 * 接口错误：状态码、服务器给的代码和参数（console-i18n I9）、英文诊断。`message` 就是诊断，只给开发者和日志看；
 * 画面上的文字在渲染时由 `text/errors.ts` 按当前语言说。
 */
export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string | null,
    readonly params: Readonly<Record<string, ErrorParam>>,
    readonly diagnostic: string,
  ) {
    super(diagnostic);
  }
}

/** 网络断了，请求没有到服务器。 */
export class NetworkError extends Error {}

type Unauthorized = () => void;
let unauthorized: Unauthorized = () => undefined;

/** 登录流程注册：收到 401 时回到登录页。登录和查询当前用户自己处理 401，不走这里。 */
export function onUnauthorized(handler: Unauthorized): void {
  unauthorized = handler;
}

/** SSE 收到 401 时也走同一个登录流程。 */
export function notifyUnauthorized(): void {
  unauthorized();
}

interface Options extends RequestInit {
  /** 为 true 时 401 不触发登录流程，只作为错误返回。 */
  quiet401?: boolean;
}

async function call<T>(path: string, { quiet401 = false, ...init }: Options = {}): Promise<T> {
  // 只读查看别人的会话时不发任何写请求（退出登录除外）：页面已经不给入口，这里再兜一层，免得写到自己的账号上
  if (viewedUser() !== null && (init.method ?? "GET") !== "GET" && path !== "/api/auth/logout") {
    throw new ApiError(403, "view/read-only", {}, "read-only while viewing another user's sessions");
  }
  const response = await fetch(path, {
    ...init,
    credentials: "same-origin",
    headers: { "content-type": "application/json", ...init.headers },
  });
  const body = await response.text();
  if (response.status === 401 && !quiet401) unauthorized();
  // 任何接口返回 503 表示服务已停止处理事件，界面要说明。
  if (response.status === 503) reportHalted();
  if (!response.ok) {
    const failure = errorOf(response.status, body);
    // 不再是管理员，或者功能被关了：回到自己的会话
    if (failure.code === "view/forbidden") setViewedUser(null);
    throw failure;
  }
  return (body === "" ? undefined : JSON.parse(body)) as T;
}

/** 错误的响应体 `{"error", "code", "params"}`；老服务器、代理返回的不是 JSON 时只有状态码。 */
function errorOf(status: number, body: string): ApiError {
  try {
    const parsed = JSON.parse(body) as { error?: unknown; code?: unknown; params?: unknown };
    const params = parsed.params !== null && typeof parsed.params === "object" ? (parsed.params as Record<string, ErrorParam>) : {};
    return new ApiError(status, typeof parsed.code === "string" ? parsed.code : null, params, typeof parsed.error === "string" ? parsed.error : "");
  } catch {
    return new ApiError(status, null, {}, "");
  }
}

export function login(username: string, password: string): Promise<User> {
  return call("/api/auth/login", { method: "POST", body: JSON.stringify({ username, password }), quiet401: true });
}

export function logout(): Promise<void> {
  return call("/api/auth/logout", { method: "POST", body: "{}", quiet401: true });
}

/** 未登录时返回 null，而不是抛错。 */
export async function me(): Promise<User | null> {
  try {
    return await call<User>("/api/auth/me", { quiet401: true });
  } catch (failure) {
    if (failure instanceof ApiError && failure.status === 401) return null;
    throw failure;
  }
}

/** 自己改密码（ops-hardening-2 需求 2.6）：要当前密码；改后别处的登录失效，这里的保留。 */
export function changePassword(current: string, next: string): Promise<{ changed: boolean }> {
  return call("/api/auth/password", { method: "PUT", body: JSON.stringify({ current, next }) });
}

// 用户管理（管理员；需求 2.4）
export function listUsers(): Promise<UserList> {
  return call("/api/users");
}

export function createUser(user: { name: string; password: string; admin: boolean }): Promise<Account> {
  return call("/api/users", { method: "POST", body: JSON.stringify(user) });
}

export function updateUser(name: string, change: { disabled?: boolean; admin?: boolean }): Promise<Account> {
  return call(`/api/users/${encodeURIComponent(name)}`, { method: "PUT", body: JSON.stringify(change) });
}

export function resetUserPassword(name: string, password: string): Promise<{ name: string; reset: boolean }> {
  return call(`/api/users/${encodeURIComponent(name)}/password`, { method: "PUT", body: JSON.stringify({ password }) });
}

export function getConfig(): Promise<Config> {
  return call("/api/config");
}

export function listSessions(): Promise<{ sessions: SessionSummary[] }> {
  return call(asViewed("/api/sessions"));
}

/** 改名进账；空串恢复自动标题（workspaces W7）。 */
export function renameSession(session: string, title: string): Promise<{ title: string }> {
  return call(`/api/sessions/${encodeURIComponent(session)}`, { method: "PATCH", body: JSON.stringify({ title }) });
}

export function setArchived(session: string, archived: boolean): Promise<{ archived: boolean }> {
  return call(`/api/sessions/${encodeURIComponent(session)}/archived`, { method: "PUT", body: JSON.stringify({ archived }) });
}

export function setPinned(session: string, pinned: boolean): Promise<{ pinned: boolean }> {
  return call(`/api/sessions/${encodeURIComponent(session)}/pinned`, { method: "PUT", body: JSON.stringify({ pinned }) });
}

/** 按内容搜会话，最多 20 个（session-find-pin R3）。查询变了由调用方中止。 */
export function searchContent(query: string, signal?: AbortSignal): Promise<{ hits: ContentHit[]; more: boolean }> {
  return call(`/api/sessions/search?q=${encodeURIComponent(query)}`, { signal });
}

export function listProjects(): Promise<{ projects: Project[] }> {
  return call(asViewed("/api/projects"));
}

export function addProject(path: string, title?: string): Promise<Project> {
  return call("/api/projects", { method: "POST", body: JSON.stringify(title === undefined || title.trim() === "" ? { path } : { path, title }) });
}

export function renameProject(id: string, title: string): Promise<Project> {
  return call(`/api/projects/${encodeURIComponent(id)}`, { method: "PATCH", body: JSON.stringify({ title }) });
}

export function deleteProject(id: string): Promise<void> {
  return call(`/api/projects/${encodeURIComponent(id)}`, { method: "DELETE" });
}

/** 经连接程序列电脑上的目录；不给 `path` 时列允许的目录本身。 */
export function browseFolders(path?: string): Promise<FolderListing> {
  return call(`/api/projects/browse${path === undefined ? "" : `?path=${encodeURIComponent(path)}`}`);
}

/** 默认最近一页；`before` 是已加载部分的 `start`。还没有任何提交的会话在后端不存在，当作空会话。 */
export async function listMessages(session: string, before?: number): Promise<Messages> {
  const page = before === undefined ? "" : `?before=${before}`;
  try {
    return await call(asViewed(`/api/sessions/${encodeURIComponent(session)}/messages${page}`));
  } catch (failure) {
    if (failure instanceof ApiError && failure.status === 404) return { seq: -1, items: [], start: 0, hasMore: false };
    throw failure;
  }
}

/** `requestId` 由调用方保存，重试时用同一个，后端据此去重。附件只传 ID 和名字，尺寸和大小以服务器的为准。 */
export function submit(
  session: string,
  mode: SubmitMode,
  text: string,
  requestId: string,
  attachments: readonly AttachmentRef[] = [],
  model: ModelChoice | null = null,
  project: string | null = null,
): Promise<{ submissionId: string; duplicate: boolean }> {
  const body: Record<string, unknown> = { requestId, mode, text };
  if (attachments.length > 0) body.attachments = attachments.map(({ id, name }) => ({ id, name }));
  // 选择随开始新运行的提交发出（model-picker M6）；插话不换模型，不带
  if (model !== null && mode !== "steer") body.model = model;
  // 工作区只跟着提问走，服务器只在会话的第一次提问时用它（workspaces W3）
  if (project !== null && mode === "prompt") body.project = project;
  return call(`/api/sessions/${encodeURIComponent(session)}/submissions`, { method: "POST", body: JSON.stringify(body) });
}

/** 上传进度：已发送的字节数和总字节数。 */
export type UploadProgress = (sent: number, total: number) => void;

/**
 * 上传一个附件：请求体是原始字节（设计第 2 节）。用 XMLHttpRequest 是因为 fetch 报不了上传进度。`signal` 取消时以 AbortError 结束。
 * 被拒绝时抛 ApiError，消息是服务器给的原因。
 */
export function uploadAttachment(session: string, file: File, onProgress: UploadProgress, signal?: AbortSignal): Promise<AttachmentRef> {
  return new Promise((resolve, reject) => {
    const request = new XMLHttpRequest();
    const query = new URLSearchParams({ name: file.name });
    if (file.type !== "") query.set("type", file.type);
    request.open("POST", `/api/sessions/${encodeURIComponent(session)}/attachments?${query.toString()}`);
    request.withCredentials = true;
    request.setRequestHeader("content-type", "application/octet-stream");
    request.upload.onprogress = (event) => onProgress(event.loaded, event.lengthComputable ? event.total : file.size);
    request.onload = () => {
      if (request.status === 401) unauthorized();
      if (request.status === 503) reportHalted();
      if (request.status >= 200 && request.status < 300) resolve(JSON.parse(request.responseText) as AttachmentRef);
      else reject(errorOf(request.status, request.responseText));
    };
    request.onerror = () => reject(new NetworkError("network error"));
    request.onabort = () => reject(new DOMException("upload cancelled", "AbortError"));
    signal?.addEventListener("abort", () => request.abort(), { once: true });
    if (signal?.aborted) {
      request.abort();
      return;
    }
    request.send(file);
  });
}

/** 附件的地址。`download` 时作为下载；`name` 是下载用的文件名。地址由内容决定，浏览器只取一次。 */
export function attachmentUrl(ref: Pick<AttachmentRef, "id" | "name">, download = false): string {
  const query = new URLSearchParams({ name: ref.name });
  if (download) query.set("download", "1");
  return asViewed(`/api/attachments/${encodeURIComponent(ref.id)}?${query.toString()}`);
}

/** 手动压缩空闲会话；运行中或没有可压缩的内容时后端返回 409。 */
export function compactSession(session: string, instructions?: string): Promise<{ submissionId: string; duplicate: boolean }> {
  return call(`/api/sessions/${encodeURIComponent(session)}/compact`, {
    method: "POST",
    body: JSON.stringify(instructions === undefined ? {} : { instructions }),
  });
}

export function cancelRun(run: string): Promise<unknown> {
  return call(`/api/runs/${encodeURIComponent(run)}/cancel`, { method: "POST", body: "{}" });
}

export async function listRuns(session: string): Promise<{ runs: RunView[] }> {
  try {
    return await call(asViewed(`/api/sessions/${encodeURIComponent(session)}/runs`));
  } catch (failure) {
    if (failure instanceof ApiError && failure.status === 404) return { runs: [] };
    throw failure;
  }
}

/** 轨迹的一页：不带参数取最新一页；`before` 取更早一页；`run` 从最新一直取到包含这个运行的一页。 */
export function getTracePage(session: string, cursor: { before?: number; run?: string } = {}): Promise<TracePage> {
  const query = cursor.before !== undefined ? `?before=${cursor.before}` : cursor.run !== undefined ? `?run=${encodeURIComponent(cursor.run)}` : "";
  return call(asViewed(`/api/sessions/${encodeURIComponent(session)}/trace${query}`));
}

/** 一次尝试的完整输入（system、工具、消息），定下来后不会再变。 */
export function getTraceCall(session: string, command: string): Promise<TraceCall> {
  return call(asViewed(`/api/sessions/${encodeURIComponent(session)}/trace/calls/${encodeURIComponent(command)}`));
}

/** 跨会话的运行列表（observe-page）：`query` 是已经拼好的筛选参数（不带 `?`）。 */
export function listAllRuns(query: string, cursor?: string): Promise<RunListPage> {
  const parts = [query, cursor === undefined ? "" : `cursor=${encodeURIComponent(cursor)}`].filter((part) => part !== "");
  return call(asViewed(`/api/runs${parts.length === 0 ? "" : `?${parts.join("&")}`}`));
}

export function getRunsSummary(query: string): Promise<RunsSummary> {
  return call(asViewed(`/api/runs/summary${query === "" ? "" : `?${query}`}`));
}

/** 一次运行的调用树；不存在或不是自己的是 404。 */
export function getRunTree(session: string, run: string): Promise<{ root: RunNode }> {
  return call(asViewed(`/api/sessions/${encodeURIComponent(session)}/runs/${encodeURIComponent(run)}/tree`));
}

/** 这个人能用的插件面板（所属插件开着的）。 */
export function listPanels(): Promise<{ panels: PanelInfo[] }> {
  return call(asViewed("/api/panels"));
}

export function listTurns(run: string): Promise<{ turns: TurnSummary[] }> {
  return call(asViewed(`/api/runs/${encodeURIComponent(run)}/turns`));
}

export function getTurn(run: string, number: number): Promise<Turn> {
  return call(asViewed(`/api/runs/${encodeURIComponent(run)}/turns/${number}`));
}

// ---- 记忆：只操作当前登录用户的，接口按会话取身份。 ----

export function listMemories(): Promise<MemoryList> {
  return call("/api/memory");
}

export function getMemory(name: string): Promise<MemoryEntry> {
  return call(`/api/memory/${encodeURIComponent(name)}`);
}

export interface NewMemory {
  name: string;
  type: MemoryType;
  description: string;
  content: string;
}

/** 400 时抛出的 ApiError.message 就是服务端的拒绝原因，原样显示给用户。 */
export function createMemory(entry: NewMemory): Promise<MemoryEntry> {
  return call("/api/memory", { method: "POST", body: JSON.stringify(entry) });
}

export function deleteMemory(name: string): Promise<void> {
  return call(`/api/memory/${encodeURIComponent(name)}`, { method: "DELETE" });
}

// ---- 每天的笔记（memory-capture）：只读接口在只读查看时也能用。 ----

export function listNotes(): Promise<{ days: NoteDay[] }> {
  return call(asViewed("/api/memory/notes"));
}

export function getNote(day: string, session: string): Promise<NoteDetail> {
  return call(asViewed(`/api/memory/notes/${encodeURIComponent(day)}/${encodeURIComponent(session)}`));
}

export function saveNote(day: string, session: string, text: string): Promise<{ saved: boolean }> {
  return call(`/api/memory/notes/${encodeURIComponent(day)}/${encodeURIComponent(session)}`, { method: "PUT", body: JSON.stringify({ text }) });
}

export function deleteNote(day: string, session: string): Promise<{ deleted: boolean }> {
  return call(`/api/memory/notes/${encodeURIComponent(day)}/${encodeURIComponent(session)}`, { method: "DELETE" });
}

export function getCapture(): Promise<CaptureState> {
  return call(asViewed("/api/memory/capture"));
}

export function setCapture(enabled: boolean): Promise<CaptureState> {
  return call("/api/memory/capture", { method: "PUT", body: JSON.stringify({ enabled }) });
}

export function listPlugins(): Promise<Plugins> {
  return call("/api/plugins");
}

/** 只改当前登录用户的开关。400 时的 ApiError.message 是拒绝原因（如必须开启的插件）。 */
export function setPlugin(kind: PluginKind, name: string, enabled: boolean): Promise<unknown> {
  return call(`/api/plugins/${kind}/${encodeURIComponent(name)}`, { method: "PUT", body: JSON.stringify({ enabled }) });
}

/** 开关恢复默认：删掉我自己的设置，跟着管理员的默认走。 */
export function resetPlugin(kind: PluginKind, name: string): Promise<unknown> {
  return call(`/api/plugins/${kind}/${encodeURIComponent(name)}`, { method: "DELETE" });
}

function paramsPath(kind: PluginKind, name: string): string {
  return `/api/plugins/${kind}/${encodeURIComponent(name)}/params`;
}

/** 保存我自己的参数：只带改了的；密码给了字符串才替换。有一项不合格就一项都不存（400 `param/invalid`，带 `key`、`reason`）。 */
export function saveParams(kind: PluginKind, name: string, values: Record<string, ParamValue>): Promise<SavedParams> {
  return call(paramsPath(kind, name), { method: "PUT", body: JSON.stringify({ values }) });
}

/** 参数恢复默认：删掉我填的全部普通参数，密码不动。 */
export function resetParams(kind: PluginKind, name: string): Promise<unknown> {
  return call(paramsPath(kind, name), { method: "DELETE" });
}

export function clearSecret(kind: PluginKind, name: string, key: string): Promise<unknown> {
  return call(`${paramsPath(kind, name)}/${encodeURIComponent(key)}`, { method: "DELETE" });
}

/** 管理员：所有开关的默认、锁、参数默认值。 */
export function listPluginDefaults(): Promise<{ items: PluginDefault[] }> {
  return call("/api/admin/plugins");
}

/** 管理员：改一项的默认。代码或配置里锁住的项改开关和锁会 403，参数默认值照样能改。 */
export function setPluginDefault(kind: string, name: string, change: PluginDefaultChange): Promise<PluginDefault> {
  return call(`/api/admin/plugins/${kind}/${encodeURIComponent(name)}`, { method: "PUT", body: JSON.stringify(change) });
}

/** 管理员：一个人的“管理员的”参数。 */
export function listAssignedParams(user: string): Promise<AssignedParams> {
  return call(`/api/admin/users/${encodeURIComponent(user)}/params`);
}

/** 管理员：给一个人指定参数；`null` 是清掉、回到默认。 */
export function assignParams(user: string, kind: string, plugin: string, values: Record<string, ParamValue | null>): Promise<AssignedParams> {
  return call(`/api/admin/users/${encodeURIComponent(user)}/params/${kind}/${encodeURIComponent(plugin)}`, {
    method: "PUT",
    body: JSON.stringify({ values }),
  });
}

/** 本机工作区的开关和连接状态（local-workspace）。开关用 {@link setPlugin}("local", …)。 */
export function getLocal(): Promise<LocalStatus> {
  return call("/api/local");
}

/** 生成连接令牌；令牌和命令只在这次返回里。 */
export function issueLocalToken(validity: LocalValidity): Promise<LocalToken> {
  return call("/api/local/tokens", { method: "POST", body: JSON.stringify({ validity }) });
}

/** 撤销全部令牌并断开电脑。 */
export function revokeLocal(): Promise<unknown> {
  return call("/api/local/tokens", { method: "DELETE" });
}

/** 技能目录和里面的技能，当场向电脑要（skills-wiki）。“技能”开关用 {@link setPlugin}("local", "skills", …)。 */
export function getLocalSkills(): Promise<LocalSkills> {
  return call("/api/local/skills");
}

/** 保存技能目录，空串是清掉；回新的清单。写法不对回 400 `local/dir-invalid`。 */
export function setSkillsDir(dir: string): Promise<LocalSkills> {
  return call("/api/local/skills", { method: "PUT", body: JSON.stringify({ dir }) });
}

/** 开关一个技能。 */
export function setLocalSkill(name: string, enabled: boolean): Promise<unknown> {
  return call(`/api/local/skills/${encodeURIComponent(name)}`, { method: "PUT", body: JSON.stringify({ enabled }) });
}

export function getLocalWiki(): Promise<LocalWiki> {
  return call("/api/local/wiki");
}

export function setWikiDir(dir: string): Promise<LocalWiki> {
  return call("/api/local/wiki", { method: "PUT", body: JSON.stringify({ dir }) });
}

/** 输入框的技能菜单：这个用户此刻能点名的技能。 */
export function getMenuSkills(): Promise<{ skills: MenuSkill[] }> {
  return call("/api/skills");
}

export type QueueAction = "edit" | "remove" | "steer";

/**
 * 改排队的提问（input-area 需求 1）：换文字、移除、转成当前运行的插话。`item` 是对话里那条排队消息的 id。
 * 404 `queue/gone` 表示它已经不在排队了（轮到了、被别的页面移除了）。
 */
export function changeQueue(session: string, item: string, action: QueueAction, text?: string): Promise<{ submissionId: string }> {
  return call(`/api/sessions/${encodeURIComponent(session)}/queue/${encodeURIComponent(item)}`, {
    method: "POST",
    body: JSON.stringify({ requestId: crypto.randomUUID(), action, ...(text === undefined ? {} : { text }) }),
  });
}

/** 回答运行问的问题（waiting-state）：选项和（允许时）补充的文字至少一个。404 表示问题已经不在了（答过、运行结束）。 */
export function answerQuestion(session: string, question: string, answer: { choice?: string; text?: string }): Promise<unknown> {
  return call(`/api/sessions/${encodeURIComponent(session)}/questions/${encodeURIComponent(question)}`, {
    method: "POST",
    body: JSON.stringify(answer),
  });
}

/** 自己今天的用量和生效的每日上限。 */
export function getTodayUsage(): Promise<TodayUsageView> {
  return call("/api/usage/today");
}

/** 会话的上下文现在占了多少，分系统提示、工具定义、消息三项。 */
export function getContextUsage(session: string): Promise<ContextUsage> {
  return call(asViewed(`/api/sessions/${encodeURIComponent(session)}/context`));
}

export function getSettings(): Promise<UserSettingsView> {
  return call("/api/settings");
}

/** 能选的模型，按路线分组，带这个用户上次的选择和管理员的默认。 */
export function getModels(): Promise<ModelsResponse> {
  return call("/api/models");
}

/** 存成当前登录用户“上次的选择”，新会话从它开始（M7）。 */
export function setModelChoice(choice: ModelChoice): Promise<UserSettingsView> {
  return call("/api/settings/model", { method: "PUT", body: JSON.stringify(choice) });
}

/** 只改当前登录用户的压缩点：50、70、85 或 100（用满），返回保存后的设置。 */
export function setCompactionPercent(percent: number): Promise<UserSettingsView> {
  return call("/api/settings/compaction", { method: "PUT", body: JSON.stringify({ percent }) });
}

/** 存这个用户的控制台语言；null 是跟随浏览器（console-i18n I3）。只改自己的。 */
export function saveLanguage(language: Language | null): Promise<UserSettingsView> {
  return call("/api/settings/language", { method: "PUT", body: JSON.stringify({ language }) });
}
