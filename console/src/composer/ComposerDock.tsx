import type { Config } from "../api/types";
import type { ConversationStore } from "../store/ConversationStore";
import { useLatestRun, useRunVersion, useSessionModel } from "../store/hooks";
import { useText } from "../text";
import { Composer } from "./Composer";
import { ModelPicker } from "./ModelPicker";
import { QueueDock } from "./QueueDock";
import { StatusBar } from "./StatusBar";
import { findModel, findRoute, useModels, useSessionChoice } from "./useModels";

interface Props {
  store: ConversationStore;
  session: string;
  config: Config | undefined;
}

/**
 * 输入区：排队的提问、输入框和状态栏（问你的问题在对话里，waiting-state）。最近的运行和排队的提交都从 store 自己取。
 * 模型选择器显示这个会话的选择（model-picker）；状态栏的窗口和压缩点按这个会话的模型算（需求 8.3）。
 */
export function ComposerDock({ store, session, config }: Props) {
  const t = useText();
  const { run, running } = useLatestRun(store, session);
  const version = useRunVersion(store, run);
  const models = useModels();
  const current = useSessionModel(store, session);
  const choice = useSessionChoice(session, current.model, models.data);
  // 占比和压缩点按已经生效的模型；新会话还没有，就按选择器上的
  const info = findModel(models.data, choice.applied ?? choice.shown);
  const route = choice.shown === null ? undefined : findRoute(models.data, choice.shown.route);
  const point = info?.points.find((each) => each.percent === models.data?.percent)?.tokens;
  return (
    <div className="mx-auto w-full max-w-[var(--content-width)] shrink-0 px-4 pb-2">
      <QueueDock store={store} session={session} />
      <Composer
        session={session}
        activeRun={running ? run : null}
        modelName={config?.model ?? t.composer.model}
        cloud={route?.cloud ?? config?.cloud ?? false}
        limits={config?.attachments}
        vision={findModel(models.data, choice.shown)?.vision ?? config?.vision}
        modelPicker={
          models.data === undefined ? undefined : (
            <ModelPicker
              models={models.data}
              choice={choice.shown}
              pending={choice.pending}
              toolChangesInHistory={current.toolChangesInHistory}
              onSelect={choice.select}
            />
          )
        }
        choice={models.data === undefined ? null : choice.shown}
        onSubmitted={choice.submitted}
      />
      <StatusBar
        session={session}
        run={run}
        running={running}
        version={version}
        contextWindow={info !== undefined && info.window > 0 ? info.window : config?.contextWindow}
        point={point}
      />
    </div>
  );
}
