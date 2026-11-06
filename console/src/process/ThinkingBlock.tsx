import { ChevronDown, ChevronRight, Sparkles } from "lucide-react";
import { useEffect, useState } from "react";
import { useText } from "../text";
import { cx } from "../ui/cx";

interface Props {
  text: string;
  redacted?: boolean;
  /** 还在流式到达：默认展开，内容随分片增长。 */
  streaming?: boolean;
}

/** 一段思考。没有内容也不是被隐藏时什么都不显示。流式时展开，结算后折叠成一行，可再展开。 */
export function ThinkingBlock({ text, redacted = false, streaming = false }: Props) {
  const t = useText();
  const [open, setOpen] = useState(streaming);
  useEffect(() => {
    setOpen(streaming);
  }, [streaming]);

  if (text === "" && !redacted && !streaming) return null;

  const preview = text.replace(/\s+/g, " ").trim();
  return (
    <div className="py-1">
      <button
        type="button"
        aria-expanded={open}
        onClick={() => setOpen((current) => !current)}
        className="flex w-full items-center gap-1.5 rounded-xs py-1 text-left text-sm text-fg-muted hover:text-fg"
      >
        <Sparkles size={14} className={cx("shrink-0", streaming && "animate-pulse")} aria-hidden="true" />
        <span className="shrink-0">{t.process.thinking}</span>
        {!open && preview !== "" && <span className="min-w-0 flex-1 truncate text-fg-faint">{preview}</span>}
        {open ? <ChevronDown size={14} className="ml-auto shrink-0" /> : <ChevronRight size={14} className="ml-auto shrink-0" />}
      </button>
      {open && (
        <div className="ml-5 border-l-2 border-line py-1 pl-3 text-sm leading-relaxed whitespace-pre-wrap text-fg-muted">
          {redacted && text === "" ? <span className="italic">{t.process.thinkingRedacted}</span> : text}
          {streaming && <span className="ml-0.5 inline-block h-3.5 w-0.5 animate-pulse bg-fg-muted align-middle" aria-hidden="true" />}
        </div>
      )}
    </div>
  );
}
