import { useState } from "react";
import type { Config, Project } from "../api/types";
import { projectOf } from "../projects/group";
import { useProjects, useSetArchived } from "../projects/useProjects";
import { readLastProject, readPending, saveLastProject, savePending } from "../projects/viewState";
import { useSessions } from "../shell/useSessions";
import { Button } from "../ui/Button";
import { errorLine } from "../text/errors";
import type { ConversationStore } from "../store/ConversationStore";
import { useLatestRun, useRunVersion, useSessionModel } from "../store/hooks";
import { useText } from "../text";
import { Composer } from "./Composer";
import { LinkChip } from "./LinkChip";
import { ModelPicker } from "./ModelPicker";
import { ProjectChip } from "./ProjectChip";
import { QueueDock } from "./QueueDock";
import { StatusBar } from "./StatusBar";
import { findModel, findRoute, useModels, useSessionChoice } from "./useModels";

interface Props {
  store: ConversationStore;
  session: string;
  config: Config | undefined;
  /** 点电脑连接的提示：打开插件页。 */
  onOpenPlugins?: () => void;
  /** 工作区菜单里的“添加工作区…”。 */
  onAddProject?: () => void;
}

/**
 * 输入区：排队的提问、输入框和状态栏（问你的问题在对话里，waiting-state）。最近的运行和排队的提交都从 store 自己取。
 * 模型选择器显示这个会话的选择（model-picker）；状态栏的窗口和压缩点按这个会话的模型算（需求 8.3）。
 */
export function ComposerDock({ store, session, config, onOpenPlugins, onAddProject }: Props) {
  const t = useText();
  const sessions = useSessions();
  const projects = useProjects().data ?? [];
  const unarchive = useSetArchived();
  // 选了别的工作区后重新从存储读一次
  const [, setPicked] = useState(0);
  const summary = sessions.data?.find((each) => each.id === session);
  // 服务器知道这个会话了，就是发过第一句：工作区按它的目录算，不能再换（W12）
  const started = summary !== undefined;
  const stored = readPending(session);
  const wanted = stored === null ? readLastProject() : stored === "" ? null : stored;
  const chosen: Project | null = started ? projectOf(summary, projects) : (projects.find((project) => project.id === wanted) ?? null);
  const dir = started ? (summary.dir ?? null) : (chosen?.path ?? null);
  const { run, running } = useLatestRun(store, session);
  const version = useRunVersion(store, run);
  const models = useModels();
  const current = useSessionModel(store, session);
  const choice = useSessionChoice(session, current.model, models.data);
  // 占比和压缩点按已经生效的模型；新会话还没有，就按选择器上的
  const info = findModel(models.data, choice.applied ?? choice.shown);
  const route = choice.shown === null ? undefined : findRoute(models.data, choice.shown.route);
  const point = info?.points.find((each) => each.percent === models.data?.percent)?.tokens;
  if (summary?.archived === true) {
    return (
      <div className="mx-auto w-full max-w-[var(--content-width)] shrink-0 px-4 pb-4">
        <div role="status" className="flex items-center gap-3 rounded-2xl border border-line bg-raised px-4 py-3 text-sm text-fg-muted">
          <span className="min-w-0 flex-1">{t.projects.archivedBar}</span>
          <Button variant="secondary" size="sm" disabled={unarchive.isPending} onClick={() => unarchive.mutate({ id: session, archived: false })}>
            {t.projects.unarchive}
          </Button>
        </div>
        {unarchive.error !== null && (
          <p role="alert" className="px-3 pt-1 text-xs text-danger">
            {errorLine(unarchive.error)}
          </p>
        )}
      </div>
    );
  }
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
        onSubmitted={(sent) => {
          choice.submitted(sent);
          if (!started) saveLastProject(chosen?.id ?? null);
        }}
        project={started ? null : (chosen?.id ?? null)}
        accessory={
          <>
            <ProjectChip
              projects={projects}
              value={chosen}
              fixed={started}
              onChange={(project) => {
                savePending(session, project?.id ?? null);
                setPicked((count) => count + 1);
              }}
              onAdd={() => onAddProject?.()}
            />
            <LinkChip dir={dir} onOpen={() => onOpenPlugins?.()} />
          </>
        }
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
