import { Markdown } from "../markdown/Markdown";
import type { ConversationStore } from "../store/ConversationStore";
import { useLive } from "../store/hooks";

interface Props {
  store: ConversationStore;
  run: string;
}

/** 正在流式到达的回复文本。结算后的条目到达时由外层清掉，画面不跳动。 */
export function LiveReply({ store, run }: Props) {
  const live = useLive(store, run);
  if (live.text === "") return null;
  return (
    <div className="py-1" aria-live="polite">
      <Markdown text={live.text} />
    </div>
  );
}
