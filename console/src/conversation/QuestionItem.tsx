import { useMutation } from "@tanstack/react-query";
import { CircleHelp } from "lucide-react";
import { useEffect, useState } from "react";
import { answerQuestion, ApiError } from "../api/client";
import type { QuestionItem as QuestionItemData } from "../api/types";
import type { ConversationStore } from "../store/ConversationStore";
import { useItem } from "../store/hooks";
import { useLanguage, useText } from "../text";
import { errorLine } from "../text/errors";
import { formatMs } from "../trace/format";
import { pick } from "../text/localText";
import { Button } from "../ui/Button";

interface Props {
  store: ConversationStore;
  id: string;
}

/** 期限倒计时：每秒重算，到了显示 0。 */
function useCountdown(deadline: string | undefined): number | null {
  const [left, setLeft] = useState<number | null>(null);
  useEffect(() => {
    if (deadline === undefined) {
      setLeft(null);
      return;
    }
    const end = Date.parse(deadline);
    const tick = () => setLeft(Math.max(0, end - Date.now()));
    tick();
    const timer = setInterval(tick, 1000);
    return () => clearInterval(timer);
  }, [deadline]);
  return left;
}

/**
 * 运行停下来问你的问题（waiting-state 需求 7.1）。等回答时：标题、说明、选项按钮，允许时还有文本框和期限倒计时；
 * 点了以后禁用、等条目变成已回答（不本地假设结果，以 journal 里结算的条目为准）。答过后只读显示问题、你的选择和时间。
 */
export function QuestionItem({ store, id }: Props) {
  const item = useItem(store, id);
  if (item?.kind !== "question") return null;
  return <QuestionView item={item} />;
}

function QuestionView({ item }: { item: QuestionItemData }) {
  const t = useText();
  const text = t.conversation;
  const language = useLanguage();
  const [draft, setDraft] = useState("");
  const left = useCountdown(item.status === "OPEN" ? item.deadline : undefined);
  const answer = useMutation({
    mutationFn: (payload: { choice?: string; text?: string }) => answerQuestion(item.session, item.questionId, payload),
  });
  // 问题是插件作者写的（按看的人的语言选）或模型写的原文（console-i18n I10）
  const title = pick(item.title, language);
  const detail = pick(item.detail, language);
  const labelOf = (choice: string) => {
    const found = item.choices.find((option) => option.id === choice);
    return found === undefined ? choice : pick(found.label, language);
  };
  // 回答已经交上（200）但条目还没更新：按钮保持禁用，不再提交第二次
  const busy = answer.isPending || answer.isSuccess;
  const failure = answer.error;

  if (item.status !== "OPEN") {
    const outcome =
      item.status === "ANSWERED"
        ? text.questionAnswered([item.choice === undefined ? null : labelOf(item.choice), item.text ?? null].filter((part) => part !== null).join(" · "))
        : item.status === "EXPIRED"
          ? text.questionExpired
          : text.questionCancelled;
    return (
      <div role="note" aria-label={title} className="my-2 flex items-start gap-2 rounded-md border border-line bg-muted px-3 py-2 text-sm text-fg-muted">
        <CircleHelp size={16} className="mt-0.5 shrink-0" aria-hidden="true" />
        <div className="min-w-0">
          <p className="text-fg">{title}</p>
          <p className="mt-0.5">{outcome}</p>
        </div>
      </div>
    );
  }

  const canSendText = item.freeText && draft.trim() !== "";
  return (
    <div role="group" aria-label={title} className="my-2 rounded-md border border-warning/50 bg-raised p-3 shadow-sm">
      <div className="flex items-start justify-between gap-2">
        <p className="text-sm font-medium text-fg">{title}</p>
        {left !== null && (
          <span className="shrink-0 text-xs text-fg-faint" aria-live="off">
            {text.answerDeadline(formatMs(left))}
          </span>
        )}
      </div>
      {detail !== "" && (
        <pre className="mt-1.5 max-h-32 overflow-auto rounded-xs bg-code px-2 py-1 font-mono text-[11px] break-all whitespace-pre-wrap text-fg-muted">
          {detail}
        </pre>
      )}
      {item.choices.length > 0 && (
        <div className="mt-2 flex flex-wrap gap-1.5">
          {item.choices.map((choice, index) => (
            <Button
              key={choice.id}
              size="sm"
              variant={index === 0 ? "primary" : "secondary"}
              disabled={busy}
              onClick={() => answer.mutate(item.freeText && draft.trim() !== "" ? { choice: choice.id, text: draft.trim() } : { choice: choice.id })}
            >
              {pick(choice.label, language)}
            </Button>
          ))}
        </div>
      )}
      {item.freeText && (
        <div className="mt-2 flex items-end gap-1.5">
          <textarea
            aria-label={text.answerText}
            value={draft}
            maxLength={4000}
            rows={1}
            disabled={busy}
            placeholder={item.choices.length > 0 ? text.answerTextOptional : text.answerText}
            onChange={(event) => setDraft(event.target.value)}
            className="min-h-8 flex-1 resize-y rounded-md border border-line bg-raised px-2 py-1 text-sm text-fg outline-none focus:border-fg"
          />
          {item.choices.length === 0 && (
            <Button size="sm" variant="primary" disabled={busy || !canSendText} onClick={() => answer.mutate({ text: draft.trim() })}>
              {text.answerSend}
            </Button>
          )}
        </div>
      )}
      {failure !== null && (
        <p role="alert" className="mt-1.5 text-xs text-danger">
          {failure instanceof ApiError && failure.status === 404 ? text.questionGone : text.answerFailed(errorLine(failure))}
        </p>
      )}
    </div>
  );
}
