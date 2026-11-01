// 控制台只通过公开接口访问后端。身份来自 Cookie 会话；任何接口返回 401 都统一交给登录流程。

import { zh } from "../text/zh";
import type {
  Config,
  MemoryEntry,
  MemoryList,
  MemoryType,
  Messages,
  RunView,
  SessionSummary,
  SubmitMode,
  Trace,
  Turn,
  TurnSummary,
  User,
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

type Halted = () => void;
let halted: Halted = () => undefined;

/** 任何接口返回 503 表示服务已停止处理事件，界面要说明。 */
export function onHalted(handler: Halted): void {
  halted = handler;
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
  if (response.status === 503) halted();
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

/** 还没有任何提交的会话在后端不存在，当作空会话。 */
export async function listMessages(session: string): Promise<Messages> {
  try {
    return await call(`/api/sessions/${encodeURIComponent(session)}/messages`);
  } catch (failure) {
    if (failure instanceof ApiError && failure.status === 404) return { seq: -1, items: [], followUps: 0 };
    throw failure;
  }
}

/** `requestId` 由调用方保存，重试时用同一个，后端据此去重。 */
export function submit(session: string, mode: SubmitMode, text: string, requestId: string): Promise<{ submissionId: string; duplicate: boolean }> {
  return call(`/api/sessions/${encodeURIComponent(session)}/submissions`, {
    method: "POST",
    body: JSON.stringify({ requestId, mode, text }),
  });
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

export async function listRuns(session: string): Promise<{ runs: RunView[]; followUps: number }> {
  try {
    return await call(`/api/sessions/${encodeURIComponent(session)}/runs`);
  } catch (failure) {
    if (failure instanceof ApiError && failure.status === 404) return { runs: [], followUps: 0 };
    throw failure;
  }
}

export function getTrace(run: string): Promise<Trace> {
  return call(`/api/runs/${encodeURIComponent(run)}/trace`);
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
