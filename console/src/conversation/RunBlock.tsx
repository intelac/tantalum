import { ProcessGroup } from "../process/ProcessGroup";
import type { ConversationStore } from "../store/ConversationStore";
import { AssistantText } from "./AssistantText";
import { LiveReply } from "./LiveReply";
import { RunStatusRow } from "./RunStatusRow";

interface Props {
  store: ConversationStore;
  run: string;
  /** 思考、工具步骤和中间文本。 */
  process: readonly string[];
  /** 结尾的回复文本。 */
  reply: readonly string[];
}

/** 一次运行：过程区、回复、还在流式输出的部分，以及没有正常完成时的说明。 */
export function RunBlock({ store, run, process, reply }: Props) {
  return (
    <div className="py-1">
      <ProcessGroup store={store} run={run} ids={process} />
      {reply.map((id, index) => (
        <AssistantText key={id} store={store} id={id} last={index === reply.length - 1} />
      ))}
      <LiveReply store={store} run={run} />
      <RunStatusRow store={store} run={run} />
    </div>
  );
}
