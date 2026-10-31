import { Check, Copy } from "lucide-react";
import { useEffect, useState } from "react";
import { zh } from "../text/zh";
import { Button, type ButtonSize } from "./Button";

interface Props {
  text: string;
  size?: ButtonSize;
  className?: string;
}

/** 复制到剪贴板，成功后短暂显示对勾。 */
export function CopyButton({ text, size = "sm", className }: Props) {
  const [copied, setCopied] = useState(false);

  useEffect(() => {
    if (!copied) return;
    const timer = setTimeout(() => setCopied(false), 1500);
    return () => clearTimeout(timer);
  }, [copied]);

  async function copy(): Promise<void> {
    try {
      await navigator.clipboard.writeText(text);
      setCopied(true);
    } catch {
      // 没有剪贴板权限时什么也不做。
    }
  }

  return (
    <Button
      variant="icon"
      size={size}
      className={className}
      aria-label={copied ? zh.conversation.copied : zh.conversation.copy}
      title={copied ? zh.conversation.copied : zh.conversation.copy}
      onClick={() => void copy()}
    >
      {copied ? <Check size={14} className="text-success" /> : <Copy size={14} />}
    </Button>
  );
}
