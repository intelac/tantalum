// 后端接口的类型。条目格式由 shell 的 ItemJson 决定，内容块与 journal 一致。

export type SubmitMode = "prompt" | "steer" | "follow-up";

export interface User {
  username: string;
}

export interface Config {
  provider: string;
  model: string;
  cloud: boolean;
  /** 不知道时省略，状态行就不显示占比。 */
  contextWindow?: number;
}

export interface SessionSummary {
  id: string;
  title: string;
  lastActivity: string;
  /** `idle` 或内核状态名，如 `AwaitingModel`。 */
  runStatus: string;
  pendingRecovery: boolean;
}

export interface TextBlock {
  kind: "text";
  text: string;
}

export interface ReasoningBlock {
  kind: "reasoning";
  provider: string;
  raw: unknown;
}

export interface ToolCallBlock {
  kind: "toolCall";
  id: string;
  name: string;
  rawArguments: string;
  arguments?: unknown;
}

export interface ToolResultBlock {
  kind: "toolResult";
  toolCallId: string;
  text: string;
  error: boolean;
  code?: string;
}

export type ContentBlock = TextBlock | ReasoningBlock | ToolCallBlock | ToolResultBlock;

export type StepStatus = "WAITING" | "RUNNING" | "DONE" | "FAILED" | "BLOCKED";
export type EndReason = "COMPLETED" | "FAILED" | "CANCELLED" | "STOPPED";
export type FailureReason =
  | "CONTEXT_OVERFLOW"
  | "REFUSAL"
  | "RATE_LIMITED"
  | "TIMEOUT"
  | "SERVER_ERROR"
  | "CONNECTION_ERROR"
  | "INVALID_REQUEST"
  | "UNKNOWN";

interface ItemBase {
  id: string;
  session: string;
  run?: string;
  at: string;
}

export interface UserItem extends ItemBase {
  kind: "user";
  text: string;
  queued: boolean;
}

export interface TextItem extends ItemBase {
  kind: "text";
  text: string;
  interrupted: boolean;
}

export interface ThinkingItem extends ItemBase {
  kind: "thinking";
  text: string;
  redacted: boolean;
}

export interface ToolStepItem extends ItemBase {
  kind: "tool";
  call: ToolCallBlock;
  status: StepStatus;
  group?: string;
  startedAt?: string;
  endedAt?: string;
  result?: ToolResultBlock;
}

export interface RunStatusItem extends ItemBase {
  kind: "run";
  run: string;
  updatedAt: string;
  phase: string;
  endReason?: EndReason;
  detail?: string;
  failure?: FailureReason;
}

export type Item = UserItem | TextItem | ThinkingItem | ToolStepItem | RunStatusItem;

export interface Messages {
  /** 快照截至的事件序号；没有该会话时为 -1。 */
  seq: number;
  items: Item[];
  followUps: number;
}

export interface RunView {
  run: string;
  session: string;
  phase: string;
  pendingRecovery: boolean;
  endReason?: EndReason;
  detail?: string;
}

export interface TraceEntry {
  seq: number;
  at: string;
  type: string;
  phase: string;
  turn?: number;
}

export interface TraceSegment {
  handler: string;
  from: string;
  to?: string;
}

export interface Trace {
  events: TraceEntry[];
  segments: TraceSegment[];
}

export interface TurnSummary {
  number: number;
  command: string;
  messages: number;
  outcome: "replied" | "failed" | "pending";
}

export interface Message {
  role: "USER" | "ASSISTANT";
  content: ContentBlock[];
  interrupted: boolean;
}

export interface TokenUsage {
  input: number;
  output: number;
  cacheRead: number;
  cacheWrite: number;
}

export interface Turn {
  number: number;
  prompt: {
    system: string;
    tools: { name: string; description: string }[];
    messages: Message[];
  };
  reply?: { content: ContentBlock[]; stop: string; actualModel: string; usage?: TokenUsage };
  failure?: { reason: FailureReason; retryable: boolean; detail: string; partialText?: string };
}

/** SSE 的 `chunk` 事件。 */
export interface Chunk {
  run: string;
  session: string;
  kind: "text" | "reasoning";
  text: string;
}

/** SSE 的 `PhaseChanged` 事件。 */
export interface PhaseChanged {
  run: string;
  session: string;
  phase: string;
}
