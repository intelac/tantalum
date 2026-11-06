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
  /** 窗口大小从哪来：模型报告的，或者服务器配置的（读不到模型时）。 */
  windowSource?: "model" | "config";
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
/** 换到窗口更小的模型之前压缩失败时运行的 detail，后面接 `路线/模型`（内核 RunEnd.SWITCH_COMPACTION_FAILED，model-picker M10）。 */
export const SWITCH_COMPACTION_FAILED = "compaction failed before switching to ";
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

/**
 * 会话中途工具变了：新增了哪些，哪些不能用了。disabled 是用户在插件页关了（来源为 local 的是本机工作区没连上或没允许），
 * gone 是服务器上已经没有（插件删了或没加载）。sources 是这几个工具的来源：builtin、local 或 mcp:<服务器名>。
 */
export interface ToolChangeItem extends ItemBase {
  kind: "toolChange";
  run: string;
  added: string[];
  disabled: string[];
  gone: string[];
  sources?: Record<string, string>;
}

/** 这轮换了模型（model-picker 需求 8.4）：画面写“换成 {显示名} · {档位}”。没有档位的模型没有 effort。 */
export interface ModelChangeItem extends ItemBase {
  kind: "modelChange";
  run: string;
  route: string;
  model: string;
  effort?: string | null;
}

export type Item = UserItem | TextItem | ThinkingItem | ToolStepItem | RunStatusItem | CompactionItem | ToolChangeItem | ModelChangeItem;

export interface Messages {
  /** 快照截至的事件序号；没有该会话时为 -1。 */
  seq: number;
  items: Item[];
  /** 这一页第一个条目在会话里的位置，用作上一页的 `before`。 */
  start: number;
  /** 前面还有更早的条目。 */
  hasMore: boolean;
  /** 这个会话现在的模型选择；本 spec 之前的会话、旧服务为 null 或没有。 */
  model?: ModelChoice | null;
  /** 会话中途改过工具：不支持的 Claude 模型在选择器里灰掉（M11）。 */
  toolChangesInHistory?: boolean;
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
  /** 用户在插件页改了开关，这次提问起新增和移除的工具；没有时不出现。 */
  toolsAdded?: string[];
  toolsRemoved?: string[];
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
  /** 按结果行还原的请求和当时记下的指纹不一致（record-results 需求 4.2）；一致时不出现。 */
  fingerprintMismatch?: boolean;
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

/** 插件工作在对话页上问用户的问题（如工具审批）：选一个选项交回去。 */
export interface Question {
  id: string;
  title: string;
  /** 补充说明，如工具参数的 JSON；可以为空。 */
  detail: string;
  choices: { id: string; label: string }[];
}

/** SSE 的 `question` 事件：这个会话的问题变了，再去取。 */
export interface QuestionChanged {
  session: string;
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

/** 插件页（core-tools 需求 7）：当前用户自己的开关。 */
export type PluginKind = "tool" | "mcp" | "plugin" | "local" | "web";

export interface PluginTool {
  name: string;
  description: string;
  enabled: boolean;
}

export interface McpServerView {
  name: string;
  /** READY、CONNECTING、RECONNECTING、UNAUTHORIZED、FAILED、CLOSED，未知时 UNKNOWN。 */
  state: string;
  enabled: boolean;
  tools: PluginTool[];
}

export interface HookPlugin {
  name: string;
  description: string;
  /** 挂在哪些位置：钩子方法名、afterToolExecution、work:<工作类型>。 */
  points: string[];
  mandatory: boolean;
  enabled: boolean;
}

/** 联网的开关：总开关 `web` 和“抓网页”“浏览器”两个子开关（web-tools W10）。 */
export type WebSwitch = "web" | "web-fetch" | "browser";

/** 插件页的“联网”一块：这个用户的开关、管理员开着哪几项、管理员是否禁止访问内网。两项都被管理员关掉时照样返回。 */
export interface WebPlugins {
  switches: Record<WebSwitch, boolean>;
  /** `false` 的那项管理员在配置里关掉了，没有注册工具。 */
  admin: Record<Exclude<WebSwitch, "web">, boolean>;
  blockPrivateNetwork: boolean;
}

export interface Plugins {
  /** 打开的工具能否在同一个会话的下一次提问就用上；不能时要等新会话。 */
  midSessionChanges: boolean;
  tools: PluginTool[];
  mcp: McpServerView[];
  web: WebPlugins;
  plugins: HookPlugin[];
}

/** 本机工作区的开关：总开关和读文件、Git 查询、改文件、命令行四个子开关（local-workspace L17）。 */
export type LocalSwitch = "local-workspace" | "fs-read" | "git" | "file-edit" | "shell";

/**
 * 连着的电脑在连上时说的：电脑、允许的目录（第一个是主目录）、能不能改文件和跑命令、有没有 git。都由连接程序的启动参数和那台电脑决定。
 * Git 工具不看目录是不是仓库，每次调用按路径找仓库。
 */
export interface LocalLink {
  device: { name: string; os: string; osVersion: string; arch: string };
  roots: { path: string }[];
  git: boolean;
  write: boolean;
  /** 命令行不能用时 reason 写明原因，如 “--allow-shell was not given”。 */
  shell: { available: boolean; reason?: string | null };
  network: boolean;
  connectedAt: string;
  expiresAt: string;
}

/** GET /api/local：开关和现在的连接，没连上时 link 为 null。 */
export interface LocalStatus {
  switches: Record<LocalSwitch, boolean>;
  link: LocalLink | null;
}

export type LocalValidity = "30m" | "1h" | "4h" | "1d";

/** 新生成的令牌：只在这一次响应里出现。 */
export interface LocalToken {
  token: string;
  command: string;
  expiresAt: string;
}

/** 设置页“模型”一栏的压缩点：一个百分比和它对应的 token 数，由服务器按当前窗口算好。 */
export interface CompactionPoint {
  percent: number;
  tokens: number;
}

/** 当前登录用户自己的设置。窗口未知时 `points` 为空。`model` 是上次在选择器里选的，现在不可用时为 null。 */
export interface UserSettingsView {
  compaction: {
    percent: number;
    points: CompactionPoint[];
  };
  model?: ModelChoice | null;
}

// ---- 模型路线（`/api/models`，model-picker）----

/** 一次提问用哪个模型：路线、模型、思考档位；没有档位的模型 effort 为 null。 */
export interface ModelChoice {
  route: string;
  model: string;
  effort: string | null;
}

/** 一个模型能做什么；`points` 是这个用户的四个压缩点在这个模型上的 token 数。 */
export interface ModelInfo {
  id: string;
  name: string;
  window: number;
  windowSource: "model" | "config";
  maxTokens: number;
  vision: boolean;
  levels: string[];
  defaultLevel: string | null;
  toolChanges: boolean;
  points: CompactionPoint[];
}

/** 一条路线和它最近一次读到的列表；`unavailable` 是一次也没读到，`error` 只写类别（连不上、401、超时）。 */
export interface RouteView {
  name: string;
  displayName: string;
  kind: "claude" | "openai";
  cloud: boolean;
  status: "ok" | "stale" | "unavailable";
  error: string | null;
  models: ModelInfo[];
}

export interface ModelsResponse {
  routes: RouteView[];
  default: ModelChoice;
  mine: ModelChoice | null;
  /** 这个用户选的压缩点百分比。 */
  percent: number;
}
