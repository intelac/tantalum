// 后端接口的类型。条目格式由 shell 的 ItemJson 决定，内容块与 journal 一致。

export type SubmitMode = "prompt" | "steer" | "follow-up";

/** 控制台的语言（console-i18n I1）。 */
export type Language = "zh" | "en" | "ja";

/** 登录和 `/api/auth/me` 的返回。`language` 是存在服务器上的选择，null 表示跟随浏览器（I3）。 */
export interface User {
  username: string;
  language?: Language | null;
  /** 管理员才有设置页的“用户”一页（ops-hardening-2 需求 2.3）。 */
  admin?: boolean;
}

/** 用户管理接口里的一个账号。 */
export interface Account {
  name: string;
  admin: boolean;
  disabled: boolean;
  createdAt: string;
  usageToday?: UsageToday;
}

/** 插件作者写给人看的文字（I10）：一个字符串照原样显示，带语言的按用户的语言选，没有就用英文。 */
export type LocalText = string | { en: string; zh?: string; ja?: string };

/** 接口错误的参数：只有数字和名字（I9）。 */
export type ErrorParam = string | number | readonly string[];

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
  /** 管理员配的首屏那句话；没配就没有。`text` 是默认那句，`byLanguage` 按界面语言另给。 */
  welcome?: { text?: string; byLanguage?: Record<string, string> };
}

/** 自己今天（UTC）的用量。`used` 是读入的（含缓存）加输出的；`limit` 只在有开着的插件报了每日上限时才有。 */
export interface TodayUsageView {
  day: string;
  input: number;
  output: number;
  cacheRead: number;
  cacheWrite: number;
  used: number;
  limit?: number;
}

/** 会话的上下文占用，只有数字。还没调过模型时是空对象；不知道窗口时没有 `window` 和 `limit`（压缩点）。 */
export interface ContextUsage {
  used?: number;
  window?: number;
  limit?: number;
  /** 三项估算按比例摊到 `used` 上，加起来等于它。 */
  parts?: { system: number; tools: number; messages: number };
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
  /** 运行停在等你回答（waiting-state 需求 7.3）。 */
  awaitingAnswer?: boolean;
  /** 会话在用户电脑上的工作目录，第一次提问时定下；没有时不出现（workspaces W3）。 */
  dir?: string | null;
  /** 归档了（workspaces W8）。 */
  archived?: boolean;
}

/** 一个工作区：用户电脑上的一个目录加一个名字，每人一份（workspaces W2）。 */
export interface Project {
  id: string;
  title: string;
  path: string;
  created: string;
}

/** 添加框里电脑上的一层目录。`path` 为空表示列的是允许的目录本身。 */
export interface FolderListing {
  path: string | null;
  dirs: string[];
  truncated: boolean;
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

/** 钩子追加的说明（hook-patch），只在 SYSTEM 消息里。 */
export interface HookTextBlock {
  kind: "hookText";
  hook: string;
  text: string;
}

/** 会话中途的工具变化（core-tools C14），只在 SYSTEM 消息里。 */
export interface ToolChangeBlock {
  kind: "toolChange";
  added: string[];
  removed: string[];
  unavailable?: string[];
}

export type ContentBlock = TextBlock | ReasoningBlock | ToolCallBlock | ToolResultBlock | HookTextBlock | ToolChangeBlock;

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
  /** 排队时被移除了：从没发给模型，页面不显示。 */
  removed?: boolean;
  /** 文字里点名的技能，按出现顺序；没有时不出现。 */
  skills?: SkillNote[];
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
  /** 这次运行各次模型调用的用量合计，不算压缩；模型没报过用量时没有。 */
  usage?: TokenUsage;
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

export type QuestionStatus = "OPEN" | "ANSWERED" | "EXPIRED" | "CANCELLED";

/**
 * 运行停下来问你的问题（waiting-state 需求 7.1）：等回答时显示按钮；答过后带回答和时间；期限到或取消后是 EXPIRED / CANCELLED。
 * 文字是插件作者写的（按语言的对象）或模型写的原文（字符串）。askedBy 是钩子名，或 ask_user。
 */
export interface QuestionItem extends ItemBase {
  kind: "question";
  run: string;
  askedBy: string;
  questionId: string;
  title: LocalText;
  detail: LocalText;
  choices: { id: string; label: LocalText }[];
  freeText: boolean;
  deadline?: string;
  status: QuestionStatus;
  choice?: string;
  text?: string;
  settledAt?: string;
  /** 规则插件在工具调用前问的问题：被问的那次调用。有它的问题显示成批准卡片。 */
  toolCallId?: string;
}

export type Item = UserItem | TextItem | ThinkingItem | ToolStepItem | RunStatusItem | CompactionItem | ToolChangeItem | ModelChangeItem | QuestionItem;

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
  /** 文字里点名的技能连同附上的内容；没有时不出现。 */
  skills?: SkillNote[];
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
  result: "STOP" | "NEED_WORK" | "PATCH" | "ASK" | "ERROR";
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


/** 运行停下来等用户回答（waiting-state 需求 7.4）：从问到答的一段，不算进模型或工具时长。 */
export interface QuestionRecord extends TraceHead {
  kind: "question";
  question: string;
  askedBy: string;
  title: LocalText;
  status: "WAITING" | "ANSWERED" | "EXPIRED" | "CANCELLED";
  choice?: string;
  text?: string;
}

export type TraceRecord = RunHead | SystemPrompt | UserInput | ContextChange | Compacted | ModelRequest | ToolCall | PluginWork | HookDecision | QuestionRecord;
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
  /** 这次请求的消息里钩子追加的说明（hook-patch）；没有时不出现。 */
  additions?: { hook: string; text: string }[];
}

export interface TurnSummary {
  number: number;
  command: string;
  messages: number;
  outcome: "replied" | "failed" | "pending";
}

export interface Message {
  role: "USER" | "ASSISTANT" | "SYSTEM";
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
  title: LocalText;
  /** 补充说明，如工具参数的 JSON；可以为空。 */
  detail: LocalText;
  choices: { id: string; label: LocalText }[];
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

/** 每个开关都带的状态（plugin-settings 需求 6）。旧服务器不给时当“没改过、没锁”。 */
export interface SwitchState {
  enabled: boolean;
  /** 管理员定的默认（没定就是自带的默认）。 */
  byDefault?: boolean;
  /** 我改过，和默认不是一回事：改回和默认一样也算改过，直到点“恢复默认”。 */
  changed?: boolean;
  /** 开关锁着改不了时有；没锁时没有这个字段。 */
  lock?: PluginLock;
}

export interface PluginTool extends SwitchState {
  name: string;
  description: string;
  /** 内置工具声明的参数；MCP 的工具没有。 */
  params?: ParamView[];
}

export interface McpServerView extends SwitchState {
  name: string;
  /** READY、CONNECTING、RECONNECTING、UNAUTHORIZED、FAILED、CLOSED，未知时 UNKNOWN。 */
  state: string;
  tools: PluginTool[];
}

/** 钩子插件分两组：只处理工具结果的是 `output`，其余是 `rule`。 */
export type PluginGroup = "rule" | "output";

export interface HookPlugin extends SwitchState {
  name: string;
  description: LocalText;
  /** 挂在哪些位置：钩子方法名、afterToolExecution、work:<工作类型>。 */
  points: string[];
  group?: PluginGroup;
  params?: ParamView[];
}

export type ParamType = "text" | "number" | "toggle" | "list" | "secret";
export type ParamValue = string | number | boolean | string[];

/**
 * 插件声明的一个参数和它现在的值。密码项没有 `value`、`source`，只说 `set`（填过没有）和 `stale`（服务器换了密钥、解不开，要重新填）。
 * `source`：`builtin` 插件自带的，`admin` 管理员定的默认，`own` 我自己填的，`assigned` 管理员给这个人指定的。
 */
export interface ParamView {
  key: string;
  type: ParamType;
  label: LocalText;
  hint: LocalText;
  owner: "user" | "admin";
  min?: number;
  max?: number;
  value?: ParamValue;
  source?: "builtin" | "admin" | "own" | "assigned";
  editable: boolean;
  set?: boolean;
  stale?: boolean;
}

/** 保存参数后服务器回的这一项的新参数。 */
export interface SavedParams {
  kind: string;
  name: string;
  params: ParamView[];
}

/** “插件默认值”里的一项（管理员）：`builtin` 自带的默认，`byAdmin` 管理员定的默认（没定就没有）。参数不含密码。 */
export interface PluginDefault {
  kind: "tool" | "mcp" | "plugin";
  name: string;
  group: "tool" | "mcp" | PluginGroup;
  builtin: boolean;
  byAdmin?: boolean;
  lock?: PluginLock;
  params: ParamView[];
}

/** 改默认：给了哪个改哪个；`null` 是清掉、回到自带的。 */
export interface PluginDefaultChange {
  enabled?: boolean | null;
  locked?: boolean;
  reason?: string;
  params?: Record<string, ParamValue | null>;
}

/** 管理员给一个人指定的参数（只有“管理员的”参数）。 */
export interface AssignedParams {
  user: string;
  items: { kind: "tool" | "mcp" | "plugin"; name: string; params: ParamView[] }[];
}

/** 一个人今天（UTC）的用量；`total` 是每日额度插件算的那个数。 */
export interface UsageToday {
  input: number;
  output: number;
  cacheRead: number;
  cacheWrite: number;
  total: number;
}

/** 谁锁的：`code` 插件自己声明必须开启，`config` 写在配置里，`admin` 管理员在页面上锁的（只有它带原因）。 */
export interface PluginLock {
  source: "code" | "config" | "admin";
  reason: string;
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
export type LocalSwitch = "local-workspace" | "fs-read" | "git" | "file-edit" | "shell" | "skills" | "wiki";

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
  /** `code`、`params` 是同一个原因的代码和参数（I12）；老版本连接程序只送英文的 `reason`。 */
  shell: { available: boolean; reason?: string | null; code?: string | null; params?: Record<string, string> | null };
  network: boolean;
  /** 连接程序的协议版本；`current` 为 false 时是旧版，不认会话目录，要更新（workspaces W6）。 */
  protocol?: number;
  current?: boolean;
  connectedAt: string;
  expiresAt: string;
}

/** GET /api/local：开关和现在的连接，没连上时 link 为 null。 */
export interface LocalStatus {
  switches: Record<LocalSwitch, boolean>;
  link: LocalLink | null;
}

export type LocalValidity = "30m" | "1h" | "4h" | "1d";

/**
 * 技能目录、wiki 目录现在的情况（skills-wiki）：`off` 开关关着，`unset` 没填目录，`offline` 电脑没连上，`old` 连接程序太旧，
 * `late` 电脑没按时回，`missing` 目录还不存在，`outside` 不在电脑允许的范围里，`unreadable` 读不出来。
 */
export type LocalDirState = "off" | "unset" | "offline" | "old" | "late" | "ok" | "missing" | "outside" | "unreadable";

/** 目录里的一个技能。`model`：模型能不能自己调；`user`：用户能不能点名；`enabled`：这个用户有没有把它关掉。 */
export interface LocalSkill {
  name: string;
  description: string;
  model: boolean;
  user: boolean;
  enabled: boolean;
}

/** 没通过校验的技能：`folder` 是它的目录名，`code` 是原因。 */
export interface SkillProblem {
  folder: string;
  code: string;
  detail?: string;
}

/** GET /api/local/skills：`dir` 是用户填的，`path` 是电脑上解析出来的真实路径。 */
export interface LocalSkills {
  dir: string;
  state: LocalDirState;
  path?: string;
  items: LocalSkill[];
  problems: SkillProblem[];
}

/** GET /api/local/wiki：`pages` 是列出来的页数，`total` 是总页数，`index` 是有没有 index.md。 */
export interface LocalWiki {
  dir: string;
  state: LocalDirState;
  path?: string;
  pages: number;
  total: number;
  index: boolean;
  truncated: boolean;
}

/** 输入框菜单里的一个技能。`manualOnly`：只能由用户点名，模型不会自己调。 */
export interface MenuSkill {
  name: string;
  description: string;
  manualOnly: boolean;
}

/** 用户消息带的一个点名技能：读到了有 `content`，没读到有 `error`（原因的代码）。 */
export interface SkillNote {
  name: string;
  content?: string;
  error?: string;
}

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
  /** null 表示跟随浏览器（console-i18n I3）。 */
  language?: Language | null;
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
