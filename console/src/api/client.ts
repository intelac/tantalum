// 控制台只通过公开接口访问后端。身份来自 Cookie 会话；任何接口返回 401 都统一交给登录流程。

import { reportHalted } from "./connection";
import type {
  Account,
  AttachmentRef,
  Config,
  ErrorParam,
  Language,
  LocalStatus,
  LocalToken,
  LocalValidity,
  MemoryEntry,
  MemoryList,
  MemoryType,
  Messages,
  ModelChoice,
  ModelsResponse,
  PluginKind,
  Plugins,
  RunView,
  SessionSummary,
  SubmitMode,
  TraceCall,
  TracePage,
  Turn,
  TurnSummary,
  User,
  UserSettingsView,
} from "./types";

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
  const response = await fetch(path, {
    ...init,
    credentials: "same-origin",
    headers: { "content-type": "application/json", ...init.headers },
  });
  const body = await response.text();
  if (response.status === 401 && !quiet401) unauthorized();
  // 任何接口返回 503 表示服务已停止处理事件，界面要说明。
  if (response.status === 503) reportHalted();
  if (!response.ok) throw errorOf(response.status, body);
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
export function listUsers(): Promise<{ users: Account[] }> {
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
  return call("/api/sessions");
}

/** 默认最近一页；`before` 是已加载部分的 `start`。还没有任何提交的会话在后端不存在，当作空会话。 */
export async function listMessages(session: string, before?: number): Promise<Messages> {
  const page = before === undefined ? "" : `?before=${before}`;
  try {
    return await call(`/api/sessions/${encodeURIComponent(session)}/messages${page}`);
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
): Promise<{ submissionId: string; duplicate: boolean }> {
  const body: Record<string, unknown> = { requestId, mode, text };
  if (attachments.length > 0) body.attachments = attachments.map(({ id, name }) => ({ id, name }));
  // 选择随开始新运行的提交发出（model-picker M6）；插话不换模型，不带
  if (model !== null && mode !== "steer") body.model = model;
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
  return `/api/attachments/${encodeURIComponent(ref.id)}?${query.toString()}`;
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
    return await call(`/api/sessions/${encodeURIComponent(session)}/runs`);
  } catch (failure) {
    if (failure instanceof ApiError && failure.status === 404) return { runs: [] };
    throw failure;
  }
}

/** 轨迹的一页：不带参数取最新一页；`before` 取更早一页；`run` 从最新一直取到包含这个运行的一页。 */
export function getTracePage(session: string, cursor: { before?: number; run?: string } = {}): Promise<TracePage> {
  const query = cursor.before !== undefined ? `?before=${cursor.before}` : cursor.run !== undefined ? `?run=${encodeURIComponent(cursor.run)}` : "";
  return call(`/api/sessions/${encodeURIComponent(session)}/trace${query}`);
}

/** 一次尝试的完整输入（system、工具、消息），定下来后不会再变。 */
export function getTraceCall(session: string, command: string): Promise<TraceCall> {
  return call(`/api/sessions/${encodeURIComponent(session)}/trace/calls/${encodeURIComponent(command)}`);
}

export function listTurns(run: string): Promise<{ turns: TurnSummary[] }> {
  return call(`/api/runs/${encodeURIComponent(run)}/turns`);
}

export function getTurn(run: string, number: number): Promise<Turn> {
  return call(`/api/runs/${encodeURIComponent(run)}/turns/${number}`);
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

export function listPlugins(): Promise<Plugins> {
  return call("/api/plugins");
}

/** 只改当前登录用户的开关。400 时的 ApiError.message 是拒绝原因（如必须开启的插件）。 */
export function setPlugin(kind: PluginKind, name: string, enabled: boolean): Promise<unknown> {
  return call(`/api/plugins/${kind}/${encodeURIComponent(name)}`, { method: "PUT", body: JSON.stringify({ enabled }) });
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

/** 回答运行问的问题（waiting-state）：选项和（允许时）补充的文字至少一个。404 表示问题已经不在了（答过、运行结束）。 */
export function answerQuestion(session: string, question: string, answer: { choice?: string; text?: string }): Promise<unknown> {
  return call(`/api/sessions/${encodeURIComponent(session)}/questions/${encodeURIComponent(question)}`, {
    method: "POST",
    body: JSON.stringify(answer),
  });
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
