import { useText } from "../text";
import { formatMs, formatTokens } from "./format";
import type { Totals } from "./totals";

interface Props {
  totals: Totals;
  /** 只是已加载的部分（还有更早的轮没加载）。 */
  partial?: boolean;
  /** 窄时不显示 token，进提示。 */
  compact?: boolean;
  className?: string;
}

/** 总数的各段文字：没有的项不出现。 */
export function totalParts(totals: Totals, t: ReturnType<typeof useText>["trace"]["totals"], compact: boolean): string[] {
  const parts = [t.requests(totals.requests), t.tools(totals.tools)];
  if (totals.elapsed !== undefined) parts.push(totals.running ? t.elapsedSoFar(formatMs(totals.elapsed)) : t.elapsed(formatMs(totals.elapsed)));
  else if (totals.running) parts.push(t.running);
  if (!compact && totals.input !== undefined && totals.output !== undefined) parts.push(t.tokens(formatTokens(totals.input), formatTokens(totals.output)));
  return parts;
}

/**
 * 一段轨迹的总数（trace-rounds 需求 2）：`13 次模型请求 · 22 个工具调用 · 耗时 10m 21s · 3.0M → 19k tok`。
 * 用在轮次标签右边（当前看的范围）和每一轮的标题上。
 */
export function TraceTotals({ totals, partial = false, compact = false, className }: Props) {
  const t = useText().trace.totals;
  const text = totalParts(totals, t, compact).join(" · ");
  const tokens = totals.input !== undefined && totals.output !== undefined ? t.tokensHint(formatTokens(totals.input), formatTokens(totals.output)) : "";
  return (
    <span className={className} title={[tokens, t.hint].filter((part) => part !== "").join(" ")}>
      {partial ? t.partial(text) : text}
    </span>
  );
}
