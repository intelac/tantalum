import { useMemo } from "react";
import { CopyButton } from "../ui/CopyButton";
import { highlight, normalizeLanguage } from "./highlight";

interface Props {
  code: string;
  language?: string;
}

/** 浅灰圆角卡片：左上语言名，右上复制，内容高亮。 */
export function CodeBlock({ code, language }: Props) {
  const trimmed = code.replace(/\n$/, "");
  const html = useMemo(() => highlight(trimmed, language), [trimmed, language]);
  const label = normalizeLanguage(language) ?? "text";
  return (
    <div className="md-code my-3 overflow-hidden rounded-md border border-line bg-code" data-language={label}>
      <div className="flex h-8 items-center justify-between pr-1 pl-4">
        <span className="font-mono text-xs text-fg-muted">{label}</span>
        <CopyButton text={trimmed} />
      </div>
      <pre className="overflow-x-auto px-4 pb-4 font-mono text-[13px] leading-relaxed text-fg">
        <code className={`hljs language-${label}`} dangerouslySetInnerHTML={{ __html: html }} />
      </pre>
    </div>
  );
}
