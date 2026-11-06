import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { answerQuestion, ApiError, listQuestions } from "../api/client";
import type { Question } from "../api/types";
import { zh } from "../text/zh";
import { Button } from "../ui/Button";

const text = zh.conversation;

export function questionsKey(session: string) {
  return ["questions", session] as const;
}

/**
 * 这个会话里插件问用户、还没回答的问题（如工具审批）。SSE 的 `question` 和运行状态变化会让它重新取；有问题时每 3 秒再取一次，
 * 插件不再等的问题（服务端过一会儿撤下）就从页面上消失。
 */
export function useQuestions(session: string) {
  return useQuery({
    queryKey: questionsKey(session),
    queryFn: () => listQuestions(session).then((body) => body.questions),
    refetchInterval: (query) => ((query.state.data?.length ?? 0) > 0 ? 3000 : false),
  });
}

/** 输入框上方：每个问题一张卡片，标题、说明和选项按钮。第一个选项是主按钮。 */
export function QuestionCards({ session }: { session: string }) {
  const questions = useQuestions(session).data ?? [];
  if (questions.length === 0) return null;
  return (
    <section aria-label={text.questions} className="mb-2 flex flex-col gap-2">
      {questions.map((question) => (
        <QuestionCard key={question.id} session={session} question={question} />
      ))}
    </section>
  );
}

function QuestionCard({ session, question }: { session: string; question: Question }) {
  const queries = useQueryClient();
  const answer = useMutation({
    mutationFn: (choice: string) => answerQuestion(session, question.id, choice),
    onSettled: () => queries.invalidateQueries({ queryKey: questionsKey(session) }),
  });
  const failure = answer.error;
  return (
    <div role="group" aria-label={question.title} className="rounded-md border border-warning/50 bg-raised p-3 shadow-sm">
      <p className="text-sm font-medium text-fg">{question.title}</p>
      {question.detail !== "" && (
        <pre className="mt-1.5 max-h-32 overflow-auto rounded-xs bg-code px-2 py-1 font-mono text-[11px] break-all whitespace-pre-wrap text-fg-muted">
          {question.detail}
        </pre>
      )}
      <div className="mt-2 flex flex-wrap gap-1.5">
        {question.choices.map((choice, index) => (
          <Button
            key={choice.id}
            size="sm"
            variant={index === 0 ? "primary" : "secondary"}
            disabled={answer.isPending}
            onClick={() => answer.mutate(choice.id)}
          >
            {choice.label}
          </Button>
        ))}
      </div>
      {failure !== null && (
        <p role="alert" className="mt-1.5 text-xs text-danger">
          {failure instanceof ApiError && failure.status === 404 ? text.questionGone : text.answerFailed(failure.message)}
        </p>
      )}
    </div>
  );
}
