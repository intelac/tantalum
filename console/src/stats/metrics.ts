// 统计页签的指标，从轨迹记录在浏览器里算（设计第 6 节，DSH client-ui-stats）。每轮一行，展开后每步一行；
// 自动压缩的摘要请求单独一行，不并入步骤和轮次的合计。没有记录的指标为 undefined，界面显示“未记录”。

import type { ModelRequest, RunHead, ToolCall, TraceRecord, UserInput } from "../api/types";
import { toolLabel } from "../trace/rows";
import { comparePos, runStart } from "../trace/TraceStore";

export interface Metrics {
  /** 提示词总量：输入 + 缓存读 + 缓存写。轮次取最后一步（DSH“上下文口径·最新”）。 */
  context?: number;
  output?: number;
  cacheRead?: number;
  /** 缓存读 ÷ 提示词总量；轮次按 token 加权。 */
  cacheRate?: number;
  ttft?: number;
  model?: number;
  tool?: number;
  /** tok/s：输出 ÷（结束 − 首 token）。 */
  speed?: number;
  /** 首 token 到结束的时长记了但为 0，算不出速度（DSH“时长过短”）。 */
  tooShort?: boolean;
  total?: number;
}

export interface StepStats extends Metrics {
  key: string;
  step?: number;
  /** 压缩组时为真，不计入轮次合计。 */
  compaction: boolean;
  request?: ModelRequest;
  /** 这一步的工具名和次数；没有工具时为空。 */
  tools: { name: string; count: number }[];
  /** 这一步由插件续写引出。 */
  continued: boolean;
  /** 上下文比同轮上一步多了多少（负数是变少，比如压缩后）。 */
  contextDelta?: number;
}

export interface TurnStats extends Metrics {
  run: string;
  turn?: number;
  head?: RunHead;
  /** 触发这一轮的用户输入（人写的提示词显示正文）。 */
  prompt?: UserInput;
  steps: StepStats[];
  contextDelta?: number;
}

const ms = (iso: string | undefined) => (iso === undefined ? undefined : Date.parse(iso));

function span(from: string | undefined, to: string | undefined): number | undefined {
  const start = ms(from);
  const end = ms(to);
  return start === undefined || end === undefined || !Number.isFinite(start) || !Number.isFinite(end) ? undefined : Math.max(0, end - start);
}

function sum(values: (number | undefined)[]): number | undefined {
  const known = values.filter((value): value is number => value !== undefined);
  return known.length === 0 ? undefined : known.reduce((total, value) => total + value, 0);
}

/** 区间的并的总长：并行的工具不重复计时。 */
export function unionLength(intervals: [number, number][]): number {
  let total = 0;
  let current: [number, number] | null = null;
  for (const [start, end] of [...intervals].sort((a, b) => a[0] - b[0])) {
    if (current === null || start > current[1]) {
      if (current !== null) total += current[1] - current[0];
      current = [start, end];
    } else current[1] = Math.max(current[1], end);
  }
  return current === null ? total : total + current[1] - current[0];
}

function toolTime(tools: readonly ToolCall[]): number | undefined {
  const intervals = tools.flatMap((tool): [number, number][] => {
    const start = ms(tool.startedAt ?? tool.issuedAt);
    const end = ms(tool.end);
    return start === undefined || end === undefined ? [] : [[start, Math.max(start, end)]];
  });
  return intervals.length === 0 ? undefined : unionLength(intervals);
}

/** 一次请求的指标。首 token 和速度取最后一次尝试，模型用时是各尝试之和。 */
export function requestMetrics(request: ModelRequest): Metrics {
  const usage = request.usage;
  const context = usage === undefined ? undefined : usage.input + usage.cacheRead + usage.cacheWrite;
  const last = request.attempts.at(-1);
  const generation = span(last?.firstTokenAt, last?.endedAt);
  return {
    context,
    output: usage?.output,
    cacheRead: usage?.cacheRead,
    cacheRate: usage === undefined || context === 0 || context === undefined ? undefined : usage.cacheRead / context,
    ttft: span(last?.startedAt ?? last?.issuedAt, last?.firstTokenAt),
    model: sum(request.attempts.map((attempt) => span(attempt.startedAt ?? attempt.issuedAt, attempt.endedAt))),
    speed: usage === undefined || generation === undefined || generation <= 0 ? undefined : usage.output / (generation / 1_000),
    tooShort: usage !== undefined && generation === 0,
  };
}

function groupKey(record: TraceRecord): string | undefined {
  if (record.step !== undefined) return `s${record.step}`;
  if (record.compaction !== undefined) return `c${record.compaction}`;
  return undefined;
}

function stepStats(key: string, members: readonly TraceRecord[]): StepStats {
  const request = members.find((record): record is ModelRequest => record.kind === "request");
  const tools = members.filter((record): record is ToolCall => record.kind === "tool");
  const counts = new Map<string, number>();
  for (const tool of tools) counts.set(toolLabel(tool), (counts.get(toolLabel(tool)) ?? 0) + 1);
  const starts = members.map((record) => ms(record.at)).filter((value): value is number => value !== undefined);
  const ends = members.map((record) => ms(record.end));
  const finished = ends.every((value) => value !== undefined);
  const base = request === undefined ? {} : requestMetrics(request);
  return {
    ...base,
    key,
    step: members[0]?.step,
    compaction: key.startsWith("c"),
    request,
    tools: [...counts].map(([name, count]) => ({ name, count })),
    continued: members.some((record) => record.kind === "user" && record.mode === "CONTINUE"),
    tool: toolTime(tools),
    total: starts.length === 0 || !finished ? undefined : Math.max(...(ends as number[])) - Math.min(...starts),
  };
}

/** 一轮的合计：上下文取最后一步，缓存命中率按 token 加权，首 token 取第一步，速度用合计输出 ÷ 合计生成时间。 */
function turnTotals(steps: readonly StepStats[], head: RunHead | undefined): Metrics {
  const counted = steps.filter((step) => !step.compaction && step.request !== undefined);
  const contexts = counted.map((step) => step.context).filter((value): value is number => value !== undefined);
  const contextSum = contexts.reduce((total, value) => total + value, 0);
  const cacheSum = sum(counted.map((step) => step.cacheRead));
  const output = sum(counted.map((step) => step.output));
  const generation = sum(
    counted.map((step) => {
      const last = step.request?.attempts.at(-1);
      return span(last?.firstTokenAt, last?.endedAt);
    }),
  );
  return {
    context: contexts.at(-1),
    output,
    cacheRead: cacheSum,
    cacheRate: cacheSum === undefined || contextSum === 0 ? undefined : cacheSum / contextSum,
    ttft: counted[0]?.ttft,
    model: sum(counted.map((step) => step.model)),
    tool: sum(counted.map((step) => step.tool)),
    speed: output === undefined || generation === undefined || generation <= 0 ? undefined : output / (generation / 1_000),
    total: head === undefined ? undefined : span(head.at, head.end),
  };
}

/** 记录 → 每轮的统计，按运行开始的先后排。 */
export function buildStats(records: readonly TraceRecord[]): TurnStats[] {
  const runs = new Map<string, { head?: RunHead; turn?: number; prompt?: UserInput; groups: Map<string, TraceRecord[]> }>();
  for (const record of records) {
    let run = runs.get(record.run);
    if (run === undefined) {
      run = { groups: new Map() };
      runs.set(record.run, run);
    }
    if (record.turn !== undefined) run.turn = record.turn;
    if (record.kind === "run") {
      run.head = record;
      continue;
    }
    if (record.kind === "user" && record.mode !== "CONTINUE" && run.prompt === undefined) run.prompt = record;
    const key = groupKey(record);
    if (key === undefined) continue;
    const members = run.groups.get(key);
    if (members === undefined) run.groups.set(key, [record]);
    else members.push(record);
  }
  const turns = [...runs.entries()]
    .sort(([a], [b]) => runStart(a) - runStart(b))
    .map(([id, run]): TurnStats => {
      // 组按请求行的位置排，和记录表一样。
      const anchor = (members: TraceRecord[]) => (members.find((record) => record.kind === "request") ?? members[0])?.pos ?? "0";
      const steps = [...run.groups.entries()]
        .sort(([, a], [, b]) => comparePos(anchor(a), anchor(b)))
        .map(([key, members]) => stepStats(key, members));
      let previous: number | undefined;
      for (const step of steps) {
        if (step.compaction || step.context === undefined) continue;
        if (previous !== undefined) step.contextDelta = step.context - previous;
        previous = step.context;
      }
      return { run: id, turn: run.turn, head: run.head, prompt: run.prompt, steps, ...turnTotals(steps, run.head) };
    });
  let previous: number | undefined;
  for (const turn of turns) {
    if (turn.context === undefined) continue;
    if (previous !== undefined) turn.contextDelta = turn.context - previous;
    previous = turn.context;
  }
  return turns;
}

/** 整个已加载部分的合计（合计行）。 */
export function overallTotals(turns: readonly TurnStats[]): Metrics {
  const counted = turns.flatMap((turn) => turn.steps.filter((step) => !step.compaction && step.request !== undefined));
  const contextSum = sum(counted.map((step) => step.context));
  const cacheSum = sum(counted.map((step) => step.cacheRead));
  return {
    context: turns.map((turn) => turn.context).filter((value) => value !== undefined).at(-1),
    output: sum(counted.map((step) => step.output)),
    cacheRead: cacheSum,
    cacheRate: cacheSum === undefined || contextSum === undefined || contextSum === 0 ? undefined : cacheSum / contextSum,
    model: sum(counted.map((step) => step.model)),
    tool: sum(counted.map((step) => step.tool)),
    total: sum(turns.map((turn) => turn.total)),
  };
}
