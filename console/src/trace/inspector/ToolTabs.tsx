// 工具调用的页签：概述、参数、结果、Schema、计时，edit_file 另有差异。参数可在树形和原始 JSON（保留空白）之间切换。

import { useState } from "react";
import type { ToolCall, TraceRecord } from "../../api/types";
import { useText } from "../../text";
import { cx } from "../../ui/cx";
import { LineDiff } from "../../ui/LineDiff";
import { between, formatMs } from "../format";
import { useTraceCall } from "../hooks";
import { toolLabel } from "../rows";
import { JsonTree, parseJsonContainer } from "./JsonTree";
import { LongText } from "./LongText";
import { Fields, Loading, TabbedBody, Timestamp } from "./parts";

function Payload({ tool }: { tool: ToolCall }) {
  const t = useText().trace;
  const parsed = tool.arguments ?? parseJsonContainer(tool.rawArguments);
  const [raw, setRaw] = useState(parsed === undefined);
  if (tool.rawArguments === "") return <p className="text-xs text-fg-faint">{t.record.noPayload}</p>;
  const toggle = "rounded-xs px-1.5 py-0.5 text-[11px]";
  return (
    <>
      {parsed !== undefined && (
        <div className="flex gap-1">
          <button type="button" aria-pressed={!raw} onClick={() => setRaw(false)} className={cx(toggle, !raw ? "bg-active text-fg" : "text-fg-faint hover:text-fg")}>
            {t.record.tree}
          </button>
          <button type="button" aria-pressed={raw} onClick={() => setRaw(true)} className={cx(toggle, raw ? "bg-active text-fg" : "text-fg-faint hover:text-fg")}>
            {t.record.rawJson}
          </button>
        </div>
      )}
      {raw || parsed === undefined ? <LongText text={tool.rawArguments} /> : <JsonTree value={parsed} />}
    </>
  );
}

function Result({ tool }: { tool: ToolCall }) {
  const t = useText().trace;
  if (tool.status === "WAITING" || tool.status === "RUNNING") return <p className="text-xs text-fg-faint">{t.toolStatus[tool.status]}</p>;
  // 出错时原样保留结果文字，包括只有空白的；没有文字才显示错误码（DSH README“理解实现”第 5 段）。
  const text = tool.result ?? "";
  const parsed = parseJsonContainer(text);
  if (text === "") return <p className="text-xs text-fg-faint">{tool.code ?? t.record.noResult}</p>;
  return parsed === undefined ? <LongText text={text} /> : <JsonTree value={parsed} />;
}

function Schema({ session, tool, request }: { session: string; tool: ToolCall; request: TraceRecord | undefined }) {
  const t = useText().trace;
  const call = request?.kind === "request" ? request.attempts.at(-1)?.call : undefined;
  const detail = useTraceCall(session, call);
  if (call === undefined) return <p className="text-xs text-fg-faint">{t.record.schemaUnavailable}</p>;
  if (detail.data === undefined) return <Loading error={detail.error} />;
  const spec = detail.data.tools.find((entry) => entry.spec.name === tool.name)?.spec;
  // 历史里调过、这次请求的工具清单里已经没有的工具，没有 Schema。
  if (spec === undefined) return <p className="text-xs text-fg-faint">{t.record.schemaUnavailable}</p>;
  return (
    <>
      {spec.description !== "" && <p className="text-xs text-fg-muted">{spec.description}</p>}
      <JsonTree value={spec.schema} />
    </>
  );
}

/** edit_file 的原文和新文字（core-tools 需求 6 第 4 条）；参数不全或不是 edit_file 时为 undefined。 */
function editOf(tool: ToolCall): { before: string; after: string } | undefined {
  if (tool.name !== "edit_file") return undefined;
  const args = tool.arguments ?? parseJsonContainer(tool.rawArguments);
  if (args === null || typeof args !== "object" || Array.isArray(args)) return undefined;
  const { old_string: before, new_string: after } = args as Record<string, unknown>;
  return typeof before === "string" && typeof after === "string" ? { before, after } : undefined;
}

export function ToolTabs({ session, tool, request }: { session: string; tool: ToolCall; request: TraceRecord | undefined }) {
  const t = useText().trace;
  const edit = editOf(tool);
  const queued = between(tool.issuedAt, tool.startedAt);
  const ran = between(tool.startedAt, tool.end);
  return (
    <TabbedBody
      pages={[
        {
          id: "summary",
          label: t.tab.summary,
          render: () => (
            <>
              <Fields
                rows={[
                  [t.details.name, <span className="font-mono">{toolLabel(tool)}</span>],
                  [t.details.server, tool.server],
                  [t.details.status, t.toolStatus[tool.status] ?? tool.status],
                  [t.details.reason, tool.reason],
                  [t.details.code, tool.code],
                  [t.details.parallel, tool.group],
                ]}
              />
              <Result tool={tool} />
            </>
          ),
        },
        ...(edit === undefined
          ? []
          : [{ id: "diff", label: t.tab.diff, render: () => <LineDiff before={edit.before} after={edit.after} hunkHeaders={false} /> }]),
        { id: "payload", label: t.tab.payload, render: () => <Payload tool={tool} /> },
        { id: "result", label: t.tab.result, render: () => <Result tool={tool} /> },
        { id: "schema", label: t.tab.schema, render: () => <Schema session={session} tool={tool} request={request} /> },
        {
          id: "timing",
          label: t.tab.timing,
          render: () => (
            <Fields
              rows={[
                [t.timing.issued, <Timestamp iso={tool.issuedAt} />],
                [t.timing.started, <Timestamp iso={tool.startedAt} />],
                [t.timing.ended, <Timestamp iso={tool.end} />],
                [t.timing.queued, queued === undefined ? undefined : formatMs(queued)],
                [t.timing.duration, ran === undefined ? (tool.end === undefined ? t.running : t.timing.notRecorded) : formatMs(ran)],
              ]}
            />
          ),
        },
      ]}
    />
  );
}
