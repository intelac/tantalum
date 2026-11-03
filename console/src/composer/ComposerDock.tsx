import type { Config } from "../api/types";
import type { ConversationStore } from "../store/ConversationStore";
import { useLatestRun, useRunVersion } from "../store/hooks";
import { zh } from "../text/zh";
import { Composer } from "./Composer";
import { QueueDock } from "./QueueDock";
import { StatusBar } from "./StatusBar";

interface Props {
  store: ConversationStore;
  session: string;
  config: Config | undefined;
}

/** 输入区：排队的提问、输入框和状态栏。最近的运行和排队的提交都从 store 自己取。 */
export function ComposerDock({ store, session, config }: Props) {
  const { run, running } = useLatestRun(store, session);
  const version = useRunVersion(store, run);
  return (
    <div className="mx-auto w-full max-w-[var(--content-width)] shrink-0 px-4 pb-2">
      <QueueDock store={store} session={session} />
      <Composer
        session={session}
        activeRun={running ? run : null}
        modelName={config?.model ?? zh.composer.model}
        cloud={config?.cloud ?? false}
        limits={config?.attachments}
        vision={config?.vision}
      />
      <StatusBar session={session} run={run} running={running} version={version} contextWindow={config?.contextWindow} />
    </div>
  );
}
