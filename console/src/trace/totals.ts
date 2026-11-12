// 一段轨迹的总数（trace-rounds 需求 2）：几次模型请求、几个工具调用、耗时、token。纯函数；标签右边那一行和每轮的标题共用。
// 和统计页签（stats/metrics.ts）口径不同的地方：这里的请求数和 token 把压缩用的摘要请求也算上——它们确实发出去了、花了 token；
// 统计页签的轮合计不算它们，摘要请求单独一行。

import type { TraceRecord } from "../api/types";
import { between } from "./format";

export interface Totals {
  requests: number;
  tools: number;
  /** 各轮从开始到结束的时长之和；还在跑的轮不算进来。一个结束的轮都没有时为 undefined。 */
  elapsed?: number;
  /** 有一轮还在跑。 */
  running: boolean;
  /** 所有模型请求读入的 token：输入 + 读缓存 + 写缓存。没有一次请求带用量时为 undefined。 */
  input?: number;
  output?: number;
}

export function totalsOf(records: readonly TraceRecord[]): Totals {
  const totals: Totals = { requests: 0, tools: 0, running: false };
  for (const record of records) {
    if (record.kind === "request") {
      totals.requests++;
      if (record.usage !== undefined) {
        totals.input = (totals.input ?? 0) + record.usage.input + record.usage.cacheRead + record.usage.cacheWrite;
        totals.output = (totals.output ?? 0) + record.usage.output;
      }
    } else if (record.kind === "tool") {
      totals.tools++;
    } else if (record.kind === "run") {
      const span = between(record.at, record.end);
      if (span === undefined) totals.running = true;
      else totals.elapsed = (totals.elapsed ?? 0) + Math.max(0, span);
    }
  }
  return totals;
}
