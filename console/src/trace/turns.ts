// 轮次标签（trace-rounds 需求 1）：纯函数。一轮就是一个有轮号的运行；手动压缩没有轮号，不出标签，只在“全部”里。

import type { TraceRecord, UserInput } from "../api/types";
import { runStart } from "./TraceStore";

export type TurnState = "running" | "waiting" | "failed" | "done";

export interface TurnTab {
  run: string;
  turn: number;
  /** 这一轮人写的那句提问；没有（比如只有插件续写）时为 undefined。 */
  prompt?: string;
  /** 这一轮是怎么触发的，没有提问时拿它当提示。 */
  trigger?: "PROMPT" | "FOLLOW_UP" | "MANUAL_COMPACTION";
  state: TurnState;
}

interface Collected {
  turn?: number;
  prompt?: UserInput;
  trigger?: TurnTab["trigger"];
  ended: boolean;
  failed: boolean;
  waiting: boolean;
  head: boolean;
}

/** 已加载的每一轮一个标签，按运行开始的先后。 */
export function turnTabs(records: readonly TraceRecord[]): TurnTab[] {
  const runs = new Map<string, Collected>();
  for (const record of records) {
    let run = runs.get(record.run);
    if (run === undefined) {
      run = { ended: false, failed: false, waiting: false, head: false };
      runs.set(record.run, run);
    }
    if (record.turn !== undefined) run.turn = record.turn;
    if (record.kind === "run") {
      run.head = true;
      run.trigger = record.trigger;
      run.ended = record.endReason !== undefined;
      run.failed = record.endReason === "FAILED";
    } else if (record.kind === "user" && record.mode !== "CONTINUE" && record.source.type !== "plugin" && run.prompt === undefined) {
      run.prompt = record;
    } else if (record.kind === "question" && record.status === "WAITING") {
      run.waiting = true;
    }
  }
  return [...runs.entries()]
    .filter(([, run]) => run.turn !== undefined)
    .sort(([a], [b]) => runStart(a) - runStart(b))
    .map(([id, run]): TurnTab => ({
      run: id,
      turn: run.turn as number,
      prompt: run.prompt === undefined ? undefined : run.prompt.text.replace(/\s+/g, " ").trim(),
      trigger: run.trigger,
      // 没有运行头（这一页只带到它的一部分）时当作已经结束，不乱标“进行中”
      state: run.failed ? "failed" : run.head && !run.ended ? (run.waiting ? "waiting" : "running") : "done",
    }));
}

/** 只留一轮的记录；`run` 为 null（全部）或这一轮不在已加载的记录里时原样返回。 */
export function recordsOfTurn(records: readonly TraceRecord[], run: string | null): readonly TraceRecord[] {
  if (run === null) return records;
  const own = records.filter((record) => record.run === run);
  return own.length === 0 ? records : own;
}
