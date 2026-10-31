import { ArrowUp, Square } from "lucide-react";
import type { ChangeEvent, KeyboardEvent } from "react";
import { useEffect, useLayoutEffect, useRef, useState } from "react";
import { cancelRun, submit } from "../api/client";
import { zh } from "../text/zh";
import { Button } from "../ui/Button";
import { cx } from "../ui/cx";
import { clearDraft, readDraft, saveDraft } from "./drafts";

export interface ComposerProps {
  session: string;
  /** 当前运行；没有或已结束时为空。 */
  activeRun: string | null;
  modelName: string;
  cloud: boolean;
  /** 窄屏时隐藏模型名。 */
  compact?: boolean;
  onSubmitted?: () => void;
}

const MAX_HEIGHT = 200;

/**
 * 大圆角输入卡片。Enter 发送、Shift+Enter 换行、输入法组字时 Enter 不发送。
 * 运行中提交进入排队，并多一个停止按钮。被拒绝时保留内容，重试用同一个 requestId。
 */
export function Composer({ session, activeRun, modelName, cloud, compact = false, onSubmitted }: ComposerProps) {
  const [text, setText] = useState(() => readDraft(session));
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const requestId = useRef<string | null>(null);
  const area = useRef<HTMLTextAreaElement>(null);
  const running = activeRun !== null;

  // 切换会话：存当前草稿，读目标会话的草稿，忘掉上一次失败的 requestId。
  useEffect(() => {
    setText(readDraft(session));
    setError(null);
    requestId.current = null;
  }, [session]);

  useLayoutEffect(() => {
    const element = area.current;
    if (element === null) return;
    element.style.height = "auto";
    const next = Math.min(element.scrollHeight, MAX_HEIGHT);
    element.style.height = `${next}px`;
    element.style.overflowY = element.scrollHeight > MAX_HEIGHT ? "auto" : "hidden";
  }, [text]);

  function onChange(event: ChangeEvent<HTMLTextAreaElement>): void {
    setText(event.target.value);
    saveDraft(session, event.target.value);
  }

  async function send(): Promise<void> {
    const content = text.trim();
    if (content === "" || busy) return;
    requestId.current ??= crypto.randomUUID();
    setBusy(true);
    setError(null);
    try {
      await submit(session, running ? "follow-up" : "prompt", content, requestId.current);
      requestId.current = null;
      setText("");
      clearDraft(session);
      onSubmitted?.();
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : String(failure));
    } finally {
      setBusy(false);
      area.current?.focus();
    }
  }

  function onKeyDown(event: KeyboardEvent<HTMLTextAreaElement>): void {
    if (event.key !== "Enter" || event.shiftKey) return;
    // 输入法还在组字时的 Enter 是选字，不是发送。
    if (event.nativeEvent.isComposing || event.keyCode === 229) return;
    event.preventDefault();
    void send();
  }

  async function stop(): Promise<void> {
    if (activeRun === null) return;
    try {
      await cancelRun(activeRun);
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : String(failure));
    }
  }

  const canSend = text.trim() !== "" && !busy;

  return (
    <div className="flex flex-col gap-1.5">
      {error !== null && (
        <p role="alert" className="px-3 text-xs text-danger">
          {error}
        </p>
      )}
      <div className="rounded-2xl border border-line bg-raised shadow-sm focus-within:border-line-strong">
        <textarea
          ref={area}
          rows={1}
          value={text}
          onChange={onChange}
          onKeyDown={onKeyDown}
          placeholder={zh.composer.placeholder}
          aria-label={zh.composer.placeholder}
          className="focus-plain block w-full resize-none bg-transparent px-4 pt-3.5 pb-1 text-[15px] leading-relaxed text-fg placeholder:text-fg-faint"
        />
        <div className="flex h-12 items-center gap-2 pr-2 pl-3">
          {/* 左侧加号的位置留空，按 spec 不做附件。 */}
          <span className="w-8" aria-hidden="true" />
          <span className="flex-1" />
          {!compact && (
            <span className="max-w-56 truncate text-xs text-fg-muted" title={modelName}>
              {modelName}
              <span className="ml-1 text-fg-faint">{cloud ? zh.composer.cloud : zh.composer.local}</span>
            </span>
          )}
          {running && (
            <Button
              variant="icon"
              aria-label={zh.composer.stop}
              title={zh.composer.stopHint}
              onClick={() => void stop()}
              className="border border-line text-danger hover:bg-danger-soft"
            >
              <Square size={14} fill="currentColor" />
            </Button>
          )}
          <Button
            variant="icon"
            aria-label={running ? zh.composer.followUp : zh.composer.send}
            title={running ? zh.composer.queueHint : zh.composer.hint}
            disabled={!canSend}
            onClick={() => void send()}
            className={cx("bg-primary text-primary-fg hover:bg-primary hover:text-primary-fg hover:opacity-90", !canSend && "opacity-40")}
          >
            <ArrowUp size={18} />
          </Button>
        </div>
      </div>
    </div>
  );
}
