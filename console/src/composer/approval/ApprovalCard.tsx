import { useMutation } from "@tanstack/react-query";
import { ShieldQuestion } from "lucide-react";
import { useEffect, useId, useRef } from "react";
import { answerQuestion, ApiError } from "../../api/client";
import { useLanguage, useText } from "../../text";
import { errorLine } from "../../text/errors";
import { pick } from "../../text/localText";
import { formatMs } from "../../trace/format";
import { Button } from "../../ui/Button";
import { useCountdown } from "../../ui/useCountdown";
import { ApprovalDetail } from "./ApprovalDetail";
import type { PendingApproval } from "./pending";

interface Props {
  pending: PendingApproval;
  /** 问题来自一个任务（subagent）时，写明是哪个任务；不是时没有。 */
  from?: string;
  /** 回答交上以后调用（任务的会话不走这个页面的推送，调用方据此重读）。 */
  onAnswered?: () => void;
}

/** Esc 选的那个选项的 id：规则插件想让 Esc 能拒绝，就把拒绝的选项叫这个名字。 */
export const DENY = "deny";

/** 这次按键不归卡片管：焦点在按钮或输入上、带修饰键、按住重复、输入法组字中，或者有对话框开着。 */
function notForCard(event: KeyboardEvent): boolean {
  if (event.defaultPrevented || event.repeat || event.isComposing || event.ctrlKey || event.metaKey || event.altKey || event.shiftKey) return true;
  const target = event.target;
  if (target instanceof HTMLElement && (target.closest("button, a, input, textarea, select, [contenteditable=true]") !== null || target.closest("[role=dialog], [role=menu]") !== null)) return true;
  return document.querySelector("[role=dialog]") !== null;
}

/**
 * 批准卡片（input-area 需求 2；DSH `ApprovalCard.tsx`）：规则插件在工具调用前问你时，它顶替输入框。
 * 回车选第一个选项，Esc 选 id 是 deny 的选项（没有就不动）。点了以后全部禁用，等对话里的问题变成已回答；没交上就恢复并说明。
 */
export function ApprovalCard({ pending, from, onAnswered }: Props) {
  const { question, step } = pending;
  const t = useText();
  const text = t.conversation;
  const language = useLanguage();
  const titleId = useId();
  const card = useRef<HTMLDivElement>(null);
  const left = useCountdown(question.deadline);
  const answer = useMutation({
    mutationFn: (choice: string) => answerQuestion(question.session, question.questionId, { choice }),
    onSuccess: () => onAnswered?.(),
  });
  // 回答已经交上（200）但条目还没更新：保持禁用，不交第二次
  const busy = answer.isPending || answer.isSuccess;
  const failure = answer.error;
  const { mutate } = answer;

  useEffect(() => {
    card.current?.focus();
  }, [question.id]);

  useEffect(() => {
    if (busy) return;
    function onKeyDown(event: KeyboardEvent): void {
      if (event.key !== "Enter" && event.key !== "Escape") return;
      if (notForCard(event)) return;
      const choice = event.key === "Enter" ? question.choices[0] : question.choices.find((each) => each.id === DENY);
      if (choice === undefined) return;
      event.preventDefault();
      mutate(choice.id);
    }
    document.addEventListener("keydown", onKeyDown);
    return () => document.removeEventListener("keydown", onKeyDown);
  }, [busy, question.choices, mutate]);

  const detail = pick(question.detail, language);
  return (
    <div
      ref={card}
      role="group"
      tabIndex={-1}
      aria-labelledby={titleId}
      className="focus-plain rounded-2xl border border-warning/50 bg-raised px-4 py-3 shadow-sm"
    >
      {from !== undefined && <p className="mb-1 truncate text-xs text-warning">{from}</p>}
      <div className="flex items-center gap-1.5 text-xs text-warning">
        <ShieldQuestion size={14} aria-hidden="true" />
        <span>{text.approval.waiting}</span>
        <span className="flex-1" />
        {left !== null && <span className="text-fg-faint">{text.answerDeadline(formatMs(left))}</span>}
      </div>
      <p id={titleId} className="mt-1.5 text-sm font-medium text-fg">
        {pick(question.title, language)}
      </p>
      {step !== undefined ? (
        <div className="mt-2 flex flex-col gap-1">
          <p className="font-mono text-xs text-fg-muted">{step.call.name}</p>
          <ApprovalDetail step={step} />
        </div>
      ) : (
        detail !== "" && <pre className="mt-2 max-h-40 overflow-auto rounded-sm bg-code px-2 py-1.5 font-mono text-xs whitespace-pre-wrap break-all text-fg">{detail}</pre>
      )}
      <div className="mt-3 flex flex-wrap items-center gap-1.5">
        {question.choices.map((choice, index) => (
          <Button key={choice.id} size="sm" variant={index === 0 ? "primary" : "secondary"} disabled={busy} onClick={() => mutate(choice.id)}>
            {pick(choice.label, language)}
          </Button>
        ))}
        <span className="flex-1" />
        <span className="text-xs text-fg-faint">{question.choices.some((each) => each.id === DENY) ? text.approval.keys : text.approval.enterOnly}</span>
      </div>
      {failure !== null && (
        <p role="alert" className="mt-1.5 text-xs text-danger">
          {failure instanceof ApiError && failure.status === 404 ? text.questionGone : text.answerFailed(errorLine(failure))}
        </p>
      )}
    </div>
  );
}
