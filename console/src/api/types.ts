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
  /** 当前模型收不收图片。旧服务没有这个字段，当作收。 */
  vision?: boolean;
  /** 附件的各项上限，前端先查一遍；服务器还会再查。 */
  attachments?: AttachmentLimits;
}

export interface AttachmentLimits {
  maxImagesPerMessage: number;
  maxImageBytesPerMessage: number;
  maxImageBytes: number;
  maxFilesPerMessage: number;
  maxFileBytes: number;
}

/** 附件引用，和服务器的 `AttachmentRef` 相同：`kind` 在前，没有值的字段不出现。 */
export type AttachmentRef = ImageAttachment | FileAttachment;

export interface ImageAttachment {
  kind: "image";
  /** `sha256:<64 位十六进制>`。 */
  id: string;
  name: string;
  mediaType: string;
  bytes: number;
  width: number;
  height: number;
  /** 上传时缩小过才有：原尺寸。 */
  sourceWidth?: number;
  sourceHeight?: number;
}

export interface FileAttachment {
  kind: "file";
  id: string;
  name: string;
  /** 浏览器报的类型，只供显示。 */
  mediaType: string;
  bytes: number;
  /** 只有 PDF 有。 */
  pages?: number;
}

export interface SessionSummary {
  id: string;
  title: string;
  lastActivity: string;
  /** `idle` 或内核状态名，如 `AwaitingModel`。 */
  runStatus: string;
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

/** 服务重启时没结束的运行以 CANCELLED 收尾，detail 是这个固定值（内核 CancelSteps.INTERRUPTED_BY_RESTART）。 */
export const INTERRUPTED_BY_RESTART = "interrupted by restart";
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
  /** 按上传顺序；没有附件时不出现。 */
  attachments?: AttachmentRef[];
  queued: boolean;
}

export interface TextItem extends ItemBase {
  kind: "text";
  text: string;
  interrupted: boolean;
  /** 回复因输出上限被截断。 */
  truncated: boolean;
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

export type CompactionTrigger = "AUTO" | "OVERFLOW" | "MANUAL";

/** 一次上下文压缩。成功时有 summary，失败时有 failure。估算是压缩前后视图的 token 估算。 */
export interface CompactionItem extends ItemBase {
  kind: "compaction";
  run: string;
  trigger: CompactionTrigger;
  summary?: string;
  estimateBefore: number;
  estimateAfter: number;
  usage?: TokenUsage;
  failure?: string;
}

export type Item = UserItem | TextItem | ThinkingItem | ToolStepItem | RunStatusItem | CompactionItem;

export interface Messages {
  /** 快照截至的事件序号；没有该会话时为 -1。 */
  seq: number;
  items: Item[];
  /** 这一页第一个条目在会话里的位置，用作上一页的 `before`。 */
  start: number;
  /** 前面还有更早的条目。 */
  hasMore: boolean;
}

export interface RunView {
  run: string;
  session: string;
  phase: string;
  endReason?: EndReason;
  detail?: string;
}

// ---- 轨迹记录（`/api/sessions/{s}/trace`），和 `tantalum.shell.trace.TraceRecord` 一一对应。 ----

export type HookPoint = "BEFORE_MODEL_CALL" | "BEFORE_TOOL_CALL" | "ON_FAILURE" | "BEFORE_RUN_END" | "ON_WORK_RESULT" | "BEFORE_COMPACT";
export type StopReason = "END_TURN" | "TOOL_USE" | "MAX_TOKENS" | "OTHER";

/** 所有记录共有的字段。`pos` 是创建它的事件“序号.下标”，按它排序；`end` 为空表示还在进行。 */
export interface TraceHead {
  id: string;
  pos: string;
  run: string;
  /** 手动压缩的运行没有轮号，显示在“轮次之间”。 */
  turn?: number;
  step?: number;
  /** 自动压缩组的名字；和 `step` 二选一。 */
  compaction?: string;
  at: string;
  end?: string;
}

/** `user` 用户、`context` 上下文组装、`plugin` 插件（`name` 是钩子名）、`unknown` 未记录来源。 */
export interface TraceSource {
  type: "user" | "context" | "plugin" | "unknown";
  name?: string;
}

export interface TraceSegment {
  chars: number;
  source: TraceSource;
}

export interface RunHead extends TraceHead {
  kind: "run";
  trigger: "PROMPT" | "FOLLOW_UP" | "MANUAL_COMPACTION";
  phase: string;
  endReason?: EndReason;
  endDetail?: string;
}

export interface SystemPrompt extends TraceHead {
  kind: "system";
  initial: boolean;
  systemChars: number;
  segments: TraceSegment[];
  added: string[];
  removed: string[];
  toolCount: number;
  /** 这次尝试的命令 ID，原文从 `/trace/calls/{call}` 取。 */
  call: string;
}

export interface UserInput extends TraceHead {
  kind: "user";
  mode: "PROMPT" | "FOLLOW_UP" | "STEER" | "CONTINUE";
  text: string;
  source: TraceSource;
  /** 按上传顺序；没有附件时不出现。 */
  attachments?: AttachmentRef[];
}

export interface ContextSection {
  name: string;
  before?: string;
  after?: string;
}

export interface ContextChange extends TraceHead {
  kind: "context";
  sections: ContextSection[];
}

export interface Compacted extends TraceHead {
  kind: "compacted";
  trigger: "AUTO" | "OVERFLOW" | "MANUAL";
  adopted: boolean;
  trimOnly: boolean;
  estimateBefore: number;
  estimateAfter: number;
  usage?: TokenUsage;
  reason?: string;
  summary?: string;
}

export interface RequestOptions {
  provider: string;
  model: string;
  maxTokens: number;
  toolsAllowed: boolean;
  extra?: unknown;
}

export interface Attempt {
  call: string;
  issuedAt: string;
  startedAt?: string;
  firstTokenAt?: string;
  endedAt?: string;
  outcome: "PENDING" | "REPLIED" | "FAILED" | "INTERRUPTED";
  reason?: FailureReason;
  /** 供应商的原始错误；认证失败时不给。 */
  detail?: string;
  /** ISO-8601 时长，例如 `PT2S`：失败后等了多久再试。 */
  retryAfter?: string;
}

export interface ModelRequest extends TraceHead {
  kind: "request";
  number: number;
  purpose: "NORMAL" | "SUMMARY";
  status: "WAITING" | "DONE" | "FAILED" | "INTERRUPTED";
  attempts: Attempt[];
  maxAttempts: number;
  provider?: string;
  actualModel?: string;
  stop?: StopReason;
  toolCalls: number;
  usage?: TokenUsage;
  total?: TokenUsage;
  text?: string;
  thinking?: string;
  partial?: string;
  options?: RequestOptions;
  /** 最后一次失败的原因名；认证失败只给 `auth`。 */
  failure?: string;
  /** 这次请求里因图片预算省略的附件 ID；没有时不出现。 */
  omittedImages?: string[];
}

export interface ToolCall extends TraceHead {
  kind: "tool";
  call: string;
  name: string;
  server?: string;
  rawArguments: string;
  arguments?: unknown;
  status: "WAITING" | "RUNNING" | "DONE" | "ERROR" | "BLOCKED" | "NOT_SENT" | "UNKNOWN";
  result?: string;
  code?: string;
  reason?: string;
  /** 发出它的事件；同组即并行。被拦下和没有发出的调用没有组和发出时刻。 */
  group?: string;
  issuedAt?: string;
  startedAt?: string;
  request?: string;
}

export interface PluginWork extends TraceHead {
  kind: "work";
  command: string;
  workType: string;
  hook: string;
  point?: HookPoint;
  /** 要求这轮工作的钩子记录 ID。 */
  decision?: string;
  resumeAt?: HookPoint;
  input?: unknown;
  status: "RUNNING" | "DONE" | "FAILED" | "INTERRUPTED";
  result?: unknown;
  continueText?: string;
  after?: "CONTINUE" | "END";
  startedAt?: string;
}

export interface HookDecision extends TraceHead {
  kind: "hook";
  hook: string;
  /** 钩子抛异常时可能不知道位置。 */
  point?: HookPoint;
  result: "STOP" | "NEED_WORK" | "PATCH" | "ERROR";
  reason?: string;
  systemChars: number;
  tools: string[];
  workType?: string;
  applied?: "APPLIED" | "NOT_ADOPTED" | "INEFFECTIVE";
  /** 关联的工具记录 ID。 */
  tool?: string;
  /** 关联的工作记录 ID。 */
  work?: string;
}

export type TraceRecord = RunHead | SystemPrompt | UserInput | ContextChange | Compacted | ModelRequest | ToolCall | PluginWork | HookDecision;
export type TraceKind = TraceRecord["kind"];

/** 一页记录：按运行从新到旧切。`seq` 是重放到的最后序号；`before` 是下一页的游标。 */
export interface TracePage {
  seq: number;
  records: TraceRecord[];
  before?: number;
  hasMore: boolean;
}

export interface ToolSpec {
  name: string;
  description: string;
  schema: unknown;
}

/** 一次尝试的完整输入。`previous` 是上一次请求的 system 和工具，会话第一次请求时为空。 */
export interface TraceCall {
  call: string;
  system: string;
  segments: TraceSegment[];
  tools: { spec: ToolSpec; source: TraceSource }[];
  messages: Message[];
  output: { toolsAllowed: boolean; maxTokens: number };
  previous?: { system: string; tools: ToolSpec[] };
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

export type MemoryType = "user" | "feedback" | "project" | "reference";

export interface MemorySummary {
  name: string;
  type: MemoryType;
  description: string;
  updatedAt: string;
}

export interface MemoryEntry extends MemorySummary {
  content: string;
}

export interface MemoryList {
  entries: MemorySummary[];
  used: number;
  max: number;
}
