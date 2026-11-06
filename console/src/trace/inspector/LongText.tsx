// 长文本：等宽、保留空白，可切换自动换行，可复制原文。换行状态记在本机，下次打开沿用（DSH string-wrapping-store）。

import { WrapText } from "lucide-react";
import { useState } from "react";
import { useText } from "../../text";
import { CopyButton } from "../../ui/CopyButton";
import { cx } from "../../ui/cx";
import { localArea, readStored, writeStored } from "../../ui/storage";

const WRAP_KEY = "tantalum.trace.wrap";

export function readWrap(): boolean {
  return readStored(WRAP_KEY, localArea()) !== "false";
}

interface Props {
  text: string | undefined;
  /** 没有文字时显示的说明。 */
  empty?: string;
  className?: string;
}

export function LongText({ text, empty, className }: Props) {
  const t = useText().trace.record;
  const [wrap, setWrap] = useState(readWrap);
  if (text === undefined || text === "") return <p className="text-xs text-fg-faint">{empty ?? t.noOutput}</p>;
  return (
    <div className={cx("relative rounded-md border border-line bg-code", className)}>
      <div className="absolute top-1 right-1 flex items-center gap-0.5">
        <button
          type="button"
          aria-pressed={wrap}
          aria-label={t.wrapLines}
          title={t.wrapLines}
          onClick={() => {
            setWrap(!wrap);
            writeStored(WRAP_KEY, String(!wrap), localArea());
          }}
          className={cx("rounded-full p-1.5 hover:bg-hover", wrap ? "text-fg" : "text-fg-faint")}
        >
          <WrapText size={14} aria-hidden />
        </button>
        <CopyButton text={text} />
      </div>
      <pre className={cx("overflow-x-auto p-3 pr-16 font-mono text-xs leading-5 text-fg", wrap ? "break-words whitespace-pre-wrap" : "whitespace-pre")}>{text}</pre>
    </div>
  );
}
