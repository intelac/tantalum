import { ProcessGroup } from "../process/ProcessGroup";
import type { ConversationStore } from "../store/ConversationStore";
import { AssistantText } from "./AssistantText";
import { LiveReply } from "./LiveReply";
import { ModelChangeNotice } from "./ModelChangeNotice";
import { QuestionItem } from "./QuestionItem";
import { RunStatusRow } from "./RunStatusRow";
import { TurnUsage } from "./TurnUsage";
import { useRunStatus } from "../store/hooks";
import { settleReply } from "./blocks";
import { ToolChangeNotice } from "./ToolChangeNotice";

interface Props {
  store: ConversationStore;
  run: string;
  /** 思考、工具步骤和中间文本。 */
  process: readonly string[];
  /** 结尾的回复文本。 */
  reply: readonly string[];
  /** 这轮开始时换了模型、工具变了。 */
  notices?: readonly string[];
  /** 这轮问用户的问题（waiting-state）。 */
  questions?: readonly string[];
}

/** 一次运行：工具变化、过程区、问用户的问题、回复、还在流式输出的部分，以及没有正常完成时的说明。 */
export function RunBlock({ store, run, process: grouped, reply: trailing, notices = [], questions = [] }: Props) {
  const status = useRunStatus(store, run);
  const { process, reply } =
    status?.endReason === undefined
      ? { process: grouped, reply: trailing }
      : settleReply(grouped, trailing, (id) => store.getItem(id)?.kind === "text");
  return (
    <div className="py-1">
      {notices.map((id) =>
        store.getItem(id)?.kind === "modelChange" ? (
          <ModelChangeNotice key={id} store={store} id={id} />
        ) : (
          <ToolChangeNotice key={id} store={store} id={id} />
        ),
      )}
      <ProcessGroup store={store} run={run} ids={process} />
      {questions.map((id) => (
        <QuestionItem key={id} store={store} id={id} />
      ))}
      {reply.map((id, index) => (
        <AssistantText key={id} store={store} id={id} last={index === reply.length - 1} />
      ))}
      <LiveReply store={store} run={run} />
      <RunStatusRow store={store} run={run} />
      {/* 有回答时用量在回答的页脚里；没有回答（比如失败了）就单独一行 */}
      {reply.length === 0 && status?.endReason !== undefined && status.usage !== undefined && (
        <div className="flex h-7 items-center text-xs text-fg-faint">
          <TurnUsage usage={status.usage} />
        </div>
      )}
    </div>
  );
}
