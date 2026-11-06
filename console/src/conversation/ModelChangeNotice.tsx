import { choiceLabel } from "../composer/ModelPicker";
import { useModels } from "../composer/useModels";
import type { ConversationStore } from "../store/ConversationStore";
import { useItem } from "../store/hooks";
import { useText } from "../text";

interface Props {
  store: ConversationStore;
  id: string;
}

/**
 * 这轮换了模型：一行灰色小字“换成 {显示名} · {档位}”，样式同工具变化那一行（model-picker 需求 8.4）。显示名按模型列表给，
 * 模型已经下线时写模型名。
 */
export function ModelChangeNotice({ store, id }: Props) {
  const t = useText();
  const item = useItem(store, id);
  const models = useModels();
  if (item === undefined || item.kind !== "modelChange") return null;
  const label = choiceLabel(models.data, { route: item.route, model: item.model, effort: item.effort ?? null });
  return (
    <p role="note" className="my-1 text-xs text-fg-faint" title={`${item.route}/${item.model}`}>
      {t.conversation.modelChanged(label)}
    </p>
  );
}
