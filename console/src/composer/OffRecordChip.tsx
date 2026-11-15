import { EyeOff } from "lucide-react";
import { useText } from "../text";
import { Button } from "../ui/Button";

interface Props {
  /** 这个会话不进记忆。 */
  value: boolean;
  /** 会话已经发出第一句：只能看，不能改（服务器只认第一次提问带的）。 */
  fixed: boolean;
  onChange: (value: boolean) => void;
}

/** 输入框下面的“不进记忆”开关（memory-final-plan 第 11 节）。开了以后这个会话不记笔记、不留记忆；发出第一句后就定了。 */
export function OffRecordChip({ value, fixed, onChange }: Props) {
  const t = useText().composer;
  if (fixed) {
    if (!value) return null;
    return (
      <span className="flex items-center gap-1 text-xs text-fg-muted" title={t.offRecordHint}>
        <EyeOff size={14} className="shrink-0" aria-hidden="true" />
        <span className="max-sm:sr-only">{t.offRecord}</span>
      </span>
    );
  }
  return (
    <Button
      variant="ghost"
      size="sm"
      className={`gap-1 px-2 text-xs ${value ? "text-accent" : "text-fg-muted"}`}
      aria-pressed={value}
      title={t.offRecordHint}
      onClick={() => onChange(!value)}
    >
      <EyeOff size={14} className="shrink-0" aria-hidden="true" />
      <span className="max-sm:sr-only">{t.offRecord}</span>
    </Button>
  );
}
