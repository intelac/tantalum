// 请求的页签：概述、系统提示词、工具、消息、选项、用量、计时（DSH REQUEST_TABS 加上系统提示词、工具、消息）。
// 系统提示词、工具、消息在打开对应页签时才取 `/trace/calls/{命令}`。

import type { ModelRequest, TokenUsage } from "../../api/types";
import { Markdown } from "../../markdown/Markdown";
import { zh } from "../../text/zh";
import { between, formatMs, formatSpeed, formatTokens, parseIsoDuration } from "../format";
import { useTraceCall } from "../hooks";
import { JsonTree } from "./JsonTree";
import { LongText } from "./LongText";
import { Fields, Loading, Section, sourceText, TabbedBody, Timestamp } from "./parts";

const t = zh.trace;

function statusText(request: ModelRequest): string {
  if (request.status === "WAITING") return t.status.pending;
  return request.status === "DONE" ? t.status.completed : t.status.failed;
}

function failureText(failure: string | undefined): string | undefined {
  return failure === "auth" ? t.details.failureAuth : failure;
}

function Summary({ request }: { request: ModelRequest }) {
  const failed = request.attempts.filter((attempt) => attempt.outcome === "FAILED" || attempt.outcome === "INTERRUPTED");
  return (
    <>
      <Fields
        rows={[
          [t.details.status, statusText(request)],
          [t.details.purpose, request.purpose === "SUMMARY" ? t.details.purposeSummary : t.details.purposeNormal],
          [t.details.provider, request.provider ?? request.options?.provider],
          [t.details.model, request.actualModel ?? request.options?.model],
          [t.details.stop, request.stop],
          [t.details.toolCalls, request.toolCalls > 0 ? String(request.toolCalls) : undefined],
          [t.details.attempts, request.attempts.length > 1 ? t.retryProgress(request.attempts.length, request.maxAttempts) : undefined],
          [t.details.error, request.status === "FAILED" || request.status === "INTERRUPTED" ? failureText(request.failure) : undefined],
          [t.details.lastAttemptError, request.status === "DONE" && failed.length > 0 ? (failed.at(-1)?.reason ?? "") : undefined],
        ]}
      />
      {request.attempts.length > 1 && (
        <Section title={t.details.attempts}>
          <ol className="flex flex-col gap-1 text-xs">
            {request.attempts.map((attempt, index) => (
              <li key={attempt.call} className="flex flex-wrap gap-x-2 text-fg-muted">
                <span className="text-fg">{t.details.attempt(index + 1)}</span>
                <span>{attempt.outcome}</span>
                {attempt.reason !== undefined && <span className="text-danger">{attempt.reason}</span>}
                {attempt.detail !== undefined && <span className="break-all">{attempt.detail}</span>}
                {parseIsoDuration(attempt.retryAfter) !== undefined && (
                  <span>
                    {t.details.retryDelay} {formatMs(parseIsoDuration(attempt.retryAfter) as number)}
                  </span>
                )}
              </li>
            ))}
          </ol>
        </Section>
      )}
      {request.text !== undefined && request.text !== "" && (
        <Section title={t.details.reply}>
          <Markdown text={request.text} />
        </Section>
      )}
      {request.partial !== undefined && request.partial !== "" && (
        <Section title={t.details.partial}>
          <LongText text={request.partial} />
        </Section>
      )}
      {request.thinking !== undefined && request.thinking !== "" && (
        <Section title={t.details.thinking}>
          {/* 紧凑 Markdown：13px、20px 行高，标题只加粗（DSH README“检查记录”第 7 段）。 */}
          <Markdown text={request.thinking} muted className="text-[13px] leading-5 [&_h1]:text-[13px] [&_h2]:text-[13px] [&_h3]:text-[13px] [&_h1]:font-semibold [&_h2]:font-semibold [&_h3]:font-semibold" />
        </Section>
      )}
    </>
  );
}

function CallSystem({ session, call }: { session: string; call: string }) {
  const detail = useTraceCall(session, call);
  if (detail.data === undefined) return <Loading error={detail.error} />;
  const { system, segments } = detail.data;
  return (
    <>
      {segments.length > 0 && (
        <Section title={t.details.segments}>
          <ul className="flex flex-col gap-0.5 text-xs text-fg-muted">
            {segments.map((segment, index) => (
              <li key={index}>
                {sourceText(segment.source)} · {t.details.chars(segment.chars)}
              </li>
            ))}
          </ul>
        </Section>
      )}
      <LongText text={system} empty={t.record.systemPromptMissing} />
    </>
  );
}

export function CallTools({ session, call }: { session: string; call: string }) {
  const detail = useTraceCall(session, call);
  if (detail.data === undefined) return <Loading error={detail.error} />;
  if (detail.data.tools.length === 0) return <p className="text-xs text-fg-faint">{t.record.toolsMissing}</p>;
  return (
    <ul className="flex flex-col gap-3">
      {detail.data.tools.map(({ spec, source }) => (
        <li key={spec.name} className="flex flex-col gap-1">
          <span className="font-mono text-xs text-fg">
            {spec.name} <span className="font-sans text-fg-faint">· {sourceText(source)}</span>
          </span>
          {spec.description !== "" && <p className="text-xs text-fg-muted">{spec.description}</p>}
          <JsonTree value={spec.schema} />
        </li>
      ))}
    </ul>
  );
}

function CallMessages({ session, call }: { session: string; call: string }) {
  const detail = useTraceCall(session, call);
  if (detail.data === undefined) return <Loading error={detail.error} />;
  const { messages, output } = detail.data;
  return (
    <>
      <Fields
        rows={[[t.details.outputLimits, `${output.maxTokens > 0 ? t.details.maxTokens(output.maxTokens) : ""} ${output.toolsAllowed ? t.details.toolsAllowed : t.details.toolsNotAllowed}`.trim()]]}
      />
      <JsonTree value={messages} />
    </>
  );
}

function UsageTable({ title, usage }: { title: string; usage: TokenUsage | undefined }) {
  if (usage === undefined) {
    return (
      <Section title={title}>
        <p className="text-xs text-fg-faint">{t.tokens.notReported}</p>
      </Section>
    );
  }
  const prompt = usage.input + usage.cacheRead + usage.cacheWrite;
  return (
    <Section title={title}>
      <Fields
        rows={[
          [t.tokens.input, formatTokens(usage.input)],
          [t.tokens.cached, formatTokens(usage.cacheRead)],
          [t.tokens.cacheCreated, formatTokens(usage.cacheWrite)],
          [t.tokens.output, formatTokens(usage.output)],
          [t.tokens.reasoning, t.details.reasoningNotReported],
          [t.tokens.hitRate, prompt === 0 ? undefined : `${((usage.cacheRead / prompt) * 100).toFixed(1)}%`],
        ]}
      />
    </Section>
  );
}

function Timing({ request }: { request: ModelRequest }) {
  const last = request.attempts.at(-1);
  const started = last?.startedAt ?? last?.issuedAt;
  const ttft = between(started, last?.firstTokenAt);
  const generation = between(last?.firstTokenAt, last?.endedAt);
  const total = between(request.at, request.end);
  const missing = t.timing.notRecorded;
  return (
    <Fields
      rows={[
        [t.timing.issued, <Timestamp iso={last?.issuedAt} />],
        [t.timing.started, <Timestamp iso={last?.startedAt} />],
        [t.timing.queued, between(last?.issuedAt, last?.startedAt) === undefined ? undefined : formatMs(between(last?.issuedAt, last?.startedAt) as number)],
        [t.timing.ttft, ttft === undefined ? missing : formatMs(ttft)],
        [t.timing.generation, generation === undefined ? missing : formatMs(generation)],
        [t.timing.throughput, request.usage === undefined ? missing : (formatSpeed(request.usage.output, generation) ?? t.timing.durationTooShort)],
        [t.timing.totalDuration, total === undefined ? t.running : formatMs(total)],
      ]}
    />
  );
}

export function RequestTabs({ session, request }: { session: string; request: ModelRequest }) {
  const call = request.attempts.at(-1)?.call ?? request.id.slice(2);
  return (
    <TabbedBody
      pages={[
        { id: "summary", label: t.tab.summary, render: () => <Summary request={request} /> },
        { id: "system", label: t.tab.systemPrompt, render: () => <CallSystem session={session} call={call} /> },
        { id: "tools", label: t.tab.tools, render: () => <CallTools session={session} call={call} /> },
        { id: "messages", label: t.tab.messages, render: () => <CallMessages session={session} call={call} /> },
        {
          id: "options",
          label: t.tab.options,
          render: () => (request.options === undefined ? <p className="text-xs text-fg-faint">{t.options.notRecorded}</p> : <JsonTree value={request.options} />),
        },
        {
          id: "usage",
          label: t.tab.usage,
          render: () => (
            <>
              <UsageTable title={t.tokens.thisRequest} usage={request.usage} />
              <UsageTable title={t.tokens.sessionCumulative} usage={request.total} />
            </>
          ),
        },
        { id: "timing", label: t.tab.timing, render: () => <Timing request={request} /> },
      ]}
    />
  );
}
