// 控制台只通过公开接口访问后端。身份来自 Cookie 会话；任何接口返回 401 都统一交给登录流程。

import { zh } from "../text/zh";
import { reportHalted } from "./connection";
import type {
  AttachmentRef,
  Config,
  MemoryEntry,
  MemoryList,
  MemoryType,
  Messages,
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

export class ApiError extends Error {
  constructor(
    readonly status: number,
    message: string,
  ) {
    super(message);
  }
}

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
  if (!response.ok) throw new ApiError(response.status, messageOf(response.status, body));
  return (body === "" ? undefined : JSON.parse(body)) as T;
}

function messageOf(status: number, body: string): string {
  if (status === 429) return zh.error.busy;
  if (status === 503) return zh.error.halted;
  try {
    return (JSON.parse(body) as { error?: string }).error ?? zh.error.request(status);
  } catch {
    return zh.error.request(status);
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
): Promise<{ submissionId: string; duplicate: boolean }> {
  const body: Record<string, unknown> = { requestId, mode, text };
  if (attachments.length > 0) body.attachments = attachments.map(({ id, name }) => ({ id, name }));
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
      else reject(new ApiError(request.status, messageOf(request.status, request.responseText)));
    };
    request.onerror = () => reject(new ApiError(0, zh.attachment.networkError));
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

export function getSettings(): Promise<UserSettingsView> {
  return call("/api/settings");
}

/** 只改当前登录用户的压缩点：50、70、85 或 100（用满），返回保存后的设置。 */
export function setCompactionPercent(percent: number): Promise<UserSettingsView> {
  return call("/api/settings/compaction", { method: "PUT", body: JSON.stringify({ percent }) });
}
