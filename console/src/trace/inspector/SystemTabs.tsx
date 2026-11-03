// system 变化的页签：差异（和上一次请求比）、系统提示词（分段标来源）、工具。

import type { SystemPrompt } from "../../api/types";
import { zh } from "../../text/zh";
import { useTraceCall } from "../hooks";
import { LineDiff } from "./LineDiff";
import { Fields, Loading, Section, sourceText, TabbedBody } from "./parts";
import { CallTools } from "./RequestTabs";
import { LongText } from "./LongText";

const t = zh.trace;

function Diff({ session, system }: { session: string; system: SystemPrompt }) {
  const detail = useTraceCall(session, system.call);
  if (detail.data === undefined) return <Loading error={detail.error} />;
  const previous = detail.data.previous;
  return (
    <>
      {(system.added.length > 0 || system.removed.length > 0) && (
        <Section title={t.details.toolsChanged}>
          <Fields
            rows={[
              [t.details.added, system.added.join(", ")],
              [t.details.removed, system.removed.join(", ")],
            ]}
          />
        </Section>
      )}
      {previous === undefined ? (
        <p className="text-xs text-fg-faint">{t.details.noPrevious}</p>
      ) : (
        <LineDiff before={previous.system} after={detail.data.system} />
      )}
    </>
  );
}

function Prompt({ session, system }: { session: string; system: SystemPrompt }) {
  const detail = useTraceCall(session, system.call);
  return (
    <>
      <Section title={t.details.segments}>
        <ul className="flex flex-col gap-0.5 text-xs text-fg-muted">
          {system.segments.map((segment, index) => (
            <li key={index}>
              {sourceText(segment.source)} · {t.details.chars(segment.chars)}
            </li>
          ))}
        </ul>
      </Section>
      {detail.data === undefined ? <Loading error={detail.error} /> : <LongText text={detail.data.system} empty={t.record.systemPromptMissing} />}
    </>
  );
}

export function SystemTabs({ session, system }: { session: string; system: SystemPrompt }) {
  return (
    <TabbedBody
      pages={[
        { id: "diff", label: t.tab.diff, render: () => <Diff session={session} system={system} /> },
        { id: "system", label: t.tab.systemPrompt, render: () => <Prompt session={session} system={system} /> },
        { id: "tools", label: t.tab.tools, render: () => <CallTools session={session} call={system.call} /> },
      ]}
    />
  );
}
