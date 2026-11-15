// 选中节点的详情（observe-page 需求 4.4）：种类、名字、状态、时刻、时长、原因；运行、步骤和压缩组带汇总；
// 能打开轨迹页并定位到这条记录。

import { X } from "lucide-react";
import type { RunNode, Tally } from "../api/types";
import { navigate, traceRecordPath } from "../app/router";
import { useText } from "../text";
import { formatLocal, formatMs, formatTokens } from "../trace/format";
import { Button } from "../ui/Button";
import { nodeLabel } from "./RunTree";

export function TallyList({ tally }: { tally: Tally }) {
  const t = useText().observe.run.tally;
  const rows: [string, string | undefined][] = [
    [t.requests, String(tally.requests)],
    [t.retries, String(tally.retries)],
    [t.toolCalls, String(tally.toolCalls)],
    [t.toolErrors, String(tally.toolErrors)],
    [t.input, formatTokens(tally.tokens.input)],
    [t.output, formatTokens(tally.tokens.output)],
    [t.cacheRead, formatTokens(tally.tokens.cacheRead)],
    [t.cacheWrite, formatTokens(tally.tokens.cacheWrite)],
    [t.context, tally.context === undefined ? undefined : formatTokens(tally.context)],
    [t.modelMs, tally.modelMs === undefined ? undefined : formatMs(tally.modelMs)],
    [t.ttftMs, tally.ttftMs === undefined ? undefined : formatMs(tally.ttftMs)],
    [t.toolMs, tally.toolMs === undefined ? undefined : formatMs(tally.toolMs)],
    [t.duration, tally.ms === undefined ? undefined : formatMs(tally.ms)],
  ];
  return (
    <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1 text-xs">
      {rows
        .filter((row): row is [string, string] => row[1] !== undefined)
        .map(([label, value]) => (
          <div key={label} className="contents">
            <dt className="text-fg-faint">{label}</dt>
            <dd className="tabular-nums text-fg">{value}</dd>
          </div>
        ))}
    </dl>
  );
}

interface Props {
  node: RunNode;
  session: string;
  onClose: () => void;
}

export function NodeDetail({ node, session, onClose }: Props) {
  const text = useText();
  const t = text.observe.run;
  const fields: [string, string | undefined][] = [
    [t.fields.kind, t.kinds[node.kind]],
    [t.fields.name, nodeLabel(node, text)],
    [t.fields.state, t.states[node.state]],
    [t.fields.start, node.start === undefined ? undefined : formatLocal(node.start)],
    [t.fields.end, node.end === undefined ? undefined : formatLocal(node.end)],
    [t.fields.duration, node.ms === undefined ? undefined : formatMs(node.ms)],
    [t.fields.detail, node.detail],
  ];
  return (
    <section aria-label={t.detail} className="flex flex-col gap-3">
      <div className="flex items-center gap-2">
        <h2 className="min-w-0 flex-1 truncate text-sm font-medium">{nodeLabel(node, text)}</h2>
        <Button variant="icon" size="sm" aria-label={t.close} onClick={onClose}>
          <X size={14} />
        </Button>
      </div>
      <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1 text-xs">
        {fields
          .filter((row): row is [string, string] => row[1] !== undefined)
          .map(([label, value]) => (
            <div key={label} className="contents">
              <dt className="text-fg-faint">{label}</dt>
              <dd className={node.state === "error" && label === t.fields.detail ? "break-words text-danger" : "break-words text-fg"}>{value}</dd>
            </div>
          ))}
      </dl>
      {node.tally !== undefined && <TallyList tally={node.tally} />}
      {node.record !== undefined && (
        <Button size="sm" className="self-start" onClick={() => navigate(traceRecordPath(session, node.record as string))}>
          {t.openRecord}
        </Button>
      )}
    </section>
  );
}
