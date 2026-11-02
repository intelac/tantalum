import { keepPreviousData, useQuery } from "@tanstack/react-query";
import type { ReactNode } from "react";
import { getTurn } from "../api/client";
import type { ContentBlock, Turn } from "../api/types";
import { tokenCount } from "../composer/status";
import { zh } from "../text/zh";
import { cx } from "../ui/cx";

interface Props {
  run: string;
  turn: number;
  /** 运行还在进行时变化，结果到了要重新读。 */
  refreshKey: string;
}

function Section({ title, children, defaultOpen = true }: { title: string; children: ReactNode; defaultOpen?: boolean }) {
  return (
    <details open={defaultOpen} className="group rounded-md border border-line">
      <summary className="cursor-pointer px-3 py-2 text-sm font-medium text-fg select-none">{title}</summary>
      <div className="border-t border-line px-3 py-2">{children}</div>
    </details>
  );
}

function reasoningText(raw: unknown): string {
  if (typeof raw === "string") return raw;
  if (raw !== null && typeof raw === "object" && typeof (raw as { thinking?: unknown }).thinking === "string") {
    return (raw as { thinking: string }).thinking;
  }
  return zh.process.thinkingRedacted;
}

function Block({ block }: { block: ContentBlock }) {
  switch (block.kind) {
    case "text":
      return <pre className="font-sans text-sm whitespace-pre-wrap text-fg">{block.text}</pre>;
    case "reasoning":
      return <pre className="border-l-2 border-line pl-2 font-sans text-sm whitespace-pre-wrap text-fg-muted">{reasoningText(block.raw)}</pre>;
    case "toolCall":
      return (
        <pre className="rounded-sm bg-code p-2 font-mono text-xs whitespace-pre-wrap text-fg">
          {block.name} {block.rawArguments}
        </pre>
      );
    case "toolResult":
      return (
        <pre className={cx("rounded-sm p-2 font-mono text-xs whitespace-pre-wrap", block.error ? "bg-danger-soft text-danger" : "bg-code text-fg")}>
          {block.code !== undefined ? `[${block.code}] ` : ""}
          {block.text}
        </pre>
      );
  }
}

/** 一次模型调用：由 journal 回放得出的完整 system prompt、工具列表、消息，以及回复或失败、用量和实际模型。 */
export function ModelCallDetail({ run, turn, refreshKey }: Props) {
  const query = useQuery({
    queryKey: ["turn", run, turn, refreshKey],
    queryFn: () => getTurn(run, turn),
    placeholderData: keepPreviousData,
  });
  if (query.isPending) return <p className="p-3 text-sm text-fg-faint">{zh.common.loading}</p>;
  if (query.isError) {
    return (
      <p role="alert" className="p-3 text-sm text-danger">
        {query.error.message}
      </p>
    );
  }
  const detail: Turn = query.data;
  const usage = detail.reply?.usage;
  return (
    <div className="flex flex-col gap-2 p-3" aria-label={zh.trace.turn(turn)}>
      <h3 className="text-sm font-semibold text-fg">{zh.trace.turn(turn)}</h3>
      <Section title={zh.trace.system} defaultOpen={false}>
        <pre className="max-h-72 overflow-auto font-sans text-sm whitespace-pre-wrap text-fg">{detail.prompt.system}</pre>
      </Section>
      <Section title={`${zh.trace.tools}（${detail.prompt.tools.length}）`} defaultOpen={false}>
        {detail.prompt.tools.length === 0 ? (
          <p className="text-sm text-fg-faint">{zh.trace.noTools}</p>
        ) : (
          <ul className="flex flex-col gap-1">
            {detail.prompt.tools.map((tool) => (
              <li key={tool.name} className="text-sm">
                <span className="font-mono text-fg">{tool.name}</span>
                <span className="ml-2 text-fg-muted">{tool.description}</span>
              </li>
            ))}
          </ul>
        )}
      </Section>
      <Section title={`${zh.trace.messages}（${detail.prompt.messages.length}）`}>
        <ol className="flex flex-col gap-2">
          {detail.prompt.messages.map((message, index) => (
            <li key={index} className="flex gap-2">
              <span className="w-10 shrink-0 rounded-xs bg-muted px-1 py-0.5 text-center text-[11px] text-fg-muted">
                {zh.trace.roles[message.role] ?? message.role}
              </span>
              <div className="flex min-w-0 flex-1 flex-col gap-1">
                {message.content.map((block, blockIndex) => (
                  <Block key={blockIndex} block={block} />
                ))}
                {message.interrupted && <span className="text-xs text-fg-faint">{zh.conversation.interrupted}</span>}
              </div>
            </li>
          ))}
        </ol>
      </Section>
      {detail.reply !== undefined && (
        <Section title={zh.trace.reply}>
          <div className="flex flex-col gap-1">
            {detail.reply.content.map((block, index) => (
              <Block key={index} block={block} />
            ))}
          </div>
          <dl className="mt-2 grid grid-cols-[auto_1fr] gap-x-3 gap-y-1 text-xs text-fg-muted">
            <dt>{zh.trace.stop}</dt>
            <dd className="font-mono">{detail.reply.stop}</dd>
            <dt>{zh.trace.actualModel}</dt>
            <dd className="font-mono">{detail.reply.actualModel}</dd>
            {usage !== undefined && (
              <>
                <dt>{zh.trace.usage}</dt>
                <dd className="font-mono">
                  {zh.status.inOut(tokenCount(usage.input + usage.cacheRead + usage.cacheWrite), tokenCount(usage.output))}
                  {usage.cacheRead > 0 && ` · cache ${tokenCount(usage.cacheRead)}`}
                </dd>
              </>
            )}
          </dl>
        </Section>
      )}
      {detail.failure !== undefined && (
        <Section title={zh.trace.failure}>
          <p className="text-sm text-danger">{zh.failure[detail.failure.reason] ?? detail.failure.reason}</p>
          <pre className="mt-1 font-mono text-xs whitespace-pre-wrap text-fg-muted">{detail.failure.detail}</pre>
        </Section>
      )}
      {detail.reply === undefined && detail.failure === undefined && <p className="text-sm text-fg-faint">{zh.trace.pending}</p>}
    </div>
  );
}
