// 其余记录的页签：运行、用户、上下文、已压缩、插件工作、钩子决定。钩子和它要的工作、被拦的工具可以互相跳转。

import type { Compacted, ContextChange, HookDecision, PluginWork, RunHead, TraceRecord, UserInput } from "../../api/types";
import { Markdown } from "../../markdown/Markdown";
import { zh } from "../../text/zh";
import { between, formatMs, formatTokens } from "../format";
import { JsonTree } from "./JsonTree";
import { LineDiff } from "./LineDiff";
import { LongText } from "./LongText";
import { Fields, Section, sourceText, TabbedBody, Timestamp } from "./parts";

const t = zh.trace;

function Link({ id, onOpen }: { id: string | undefined; onOpen: (id: string) => void }) {
  if (id === undefined) return null;
  return (
    <button type="button" onClick={() => onOpen(id)} className="font-mono text-accent hover:underline">
      {t.details.openRecord} {id}
    </button>
  );
}

function Value({ value }: { value: unknown }) {
  if (value === undefined || value === null) return <p className="text-xs text-fg-faint">{t.record.noOutput}</p>;
  return typeof value === "string" ? <LongText text={value} /> : <JsonTree value={value} />;
}

function RunPages(run: RunHead) {
  return [
    {
      id: "summary",
      label: t.tab.summary,
      render: () => (
        <Fields
          rows={[
            [t.details.trigger, t.trigger[run.trigger]],
            [t.details.phase, run.phase],
            [t.details.endReason, run.endReason === undefined ? t.running : (t.endReason[run.endReason] ?? run.endReason)],
            [t.details.endDetail, run.endDetail],
            [t.timing.started, <Timestamp iso={run.at} />],
            [t.timing.totalDuration, between(run.at, run.end) === undefined ? undefined : formatMs(between(run.at, run.end) as number)],
          ]}
        />
      ),
    },
  ];
}

function UserPages(user: UserInput) {
  return [
    {
      id: "summary",
      label: t.tab.summary,
      render: () => (
        <>
          <Fields rows={[[t.details.mode, t.userMode[user.mode]]]} />
          <Markdown text={user.text} />
        </>
      ),
    },
    { id: "source", label: t.tab.source, render: () => <Fields rows={[[t.tab.source, sourceText(user.source)]]} /> },
  ];
}

function ContextPages(context: ContextChange) {
  return [
    {
      id: "sections",
      label: t.tab.sections,
      render: () =>
        context.sections.map((section) => (
          <Section key={section.name} title={section.name}>
            <LineDiff before={section.before ?? ""} after={section.after ?? ""} />
          </Section>
        )),
    },
    { id: "source", label: t.tab.source, render: () => <Fields rows={[[t.tab.source, t.source.context]]} /> },
  ];
}

function CompactedPages(compacted: Compacted) {
  return [
    {
      id: "summary",
      label: t.tab.summary,
      render: () => (
        <>
          <Fields
            rows={[
              [t.details.trigger, compacted.trigger],
              [t.details.adopted, compacted.adopted ? t.details.yes : t.details.no],
              [t.details.estimate, `${formatTokens(compacted.estimateBefore)} → ${formatTokens(compacted.estimateAfter)}`],
              [t.details.reason, compacted.reason],
            ]}
          />
          {compacted.summary !== undefined && (
            <Section title={t.details.summaryText}>
              <LongText text={compacted.summary} />
            </Section>
          )}
        </>
      ),
    },
    {
      id: "usage",
      label: t.tab.usage,
      render: () => (compacted.usage === undefined ? <p className="text-xs text-fg-faint">{t.tokens.notReported}</p> : <JsonTree value={compacted.usage} />),
    },
  ];
}

function WorkPages(work: PluginWork, onOpen: (id: string) => void) {
  return [
    {
      id: "summary",
      label: t.tab.summary,
      render: () => (
        <Fields
          rows={[
            [t.details.workType, work.workType],
            [t.details.hook, work.hook],
            [t.details.point, work.point === undefined ? undefined : t.hookPoint[work.point]],
            [t.details.status, t.workStatus[work.status]],
            [t.details.resumeAt, work.resumeAt === undefined ? undefined : t.hookPoint[work.resumeAt]],
            [t.details.after, work.after === undefined ? undefined : t.workAfter[work.after]],
            [t.details.continueText, work.continueText],
            [t.details.linkedHook, <Link id={work.decision} onOpen={onOpen} />],
          ]}
        />
      ),
    },
    { id: "input", label: t.tab.input, render: () => <Value value={work.input} /> },
    { id: "result", label: t.tab.result, render: () => <Value value={work.result} /> },
    {
      id: "timing",
      label: t.tab.timing,
      render: () => (
        <Fields
          rows={[
            [t.timing.issued, <Timestamp iso={work.at} />],
            [t.timing.started, <Timestamp iso={work.startedAt} />],
            [t.timing.ended, <Timestamp iso={work.end} />],
            [t.timing.duration, between(work.startedAt ?? work.at, work.end) === undefined ? t.running : formatMs(between(work.startedAt ?? work.at, work.end) as number)],
          ]}
        />
      ),
    },
  ];
}

function HookPages(hook: HookDecision, onOpen: (id: string) => void) {
  return [
    {
      id: "summary",
      label: t.tab.summary,
      render: () => (
        <>
          <Fields
            rows={[
              [t.details.hook, hook.hook],
              [t.details.point, hook.point === undefined ? undefined : t.hookPoint[hook.point]],
              [t.details.hookResult, t.hookResult[hook.result]],
              [t.details.applied, hook.result === "PATCH" ? `${t.details.patch(hook.systemChars, hook.tools.join(", "))}${hook.applied === undefined ? "" : ` · ${t.hookApplied[hook.applied]}`}` : undefined],
              [t.details.workType, hook.workType],
              [t.details.linkedTool, <Link id={hook.tool} onOpen={onOpen} />],
              [t.details.linkedWork, <Link id={hook.work} onOpen={onOpen} />],
            ]}
          />
          {hook.reason !== undefined && (
            <Section title={t.details.reason}>
              <LongText text={hook.reason} />
            </Section>
          )}
        </>
      ),
    },
    { id: "source", label: t.tab.source, render: () => <Fields rows={[[t.tab.source, t.source.plugin(hook.hook)]]} /> },
  ];
}

/** 不是请求、工具、system 的记录。 */
export function RecordTabs({ record, onOpen }: { record: Exclude<TraceRecord, { kind: "request" | "tool" | "system" }>; onOpen: (id: string) => void }) {
  const pages = (() => {
    switch (record.kind) {
      case "run":
        return RunPages(record);
      case "user":
        return UserPages(record);
      case "context":
        return ContextPages(record);
      case "compacted":
        return CompactedPages(record);
      case "work":
        return WorkPages(record, onOpen);
      case "hook":
        return HookPages(record, onOpen);
    }
  })();
  return <TabbedBody pages={pages} />;
}
