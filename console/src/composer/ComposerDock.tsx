import { useQueryClient } from "@tanstack/react-query";
import { useMemo, useState } from "react";
import type { Config, Project, UserItem } from "../api/types";
import { projectOf } from "../projects/group";
import { useProjects, useSetArchived } from "../projects/useProjects";
import { readLastProject, readPending, saveLastProject, savePending } from "../projects/viewState";
import { useSessions } from "../shell/useSessions";
import { Button } from "../ui/Button";
import { errorLine } from "../text/errors";
import type { ConversationStore } from "../store/ConversationStore";
import { useItems, useLatestRun, useOrder, useRunVersion, useSessionModel } from "../store/hooks";
import { useText } from "../text";
import { ApprovalCard } from "./approval/ApprovalCard";
import { pendingApproval } from "./approval/pending";
import { Composer } from "./Composer";
import { LinkChip } from "./LinkChip";
import { ModelPicker } from "./ModelPicker";
import { OffRecordChip } from "./OffRecordChip";
import { PendingStrip } from "./PendingStrip";
import { ProjectChip } from "./ProjectChip";
import { QueueDock } from "./queue/QueueDock";
import { useQueueActions } from "./queue/useQueueActions";
import { StatusBar } from "./StatusBar";
import { firstApproval, openTasks, pausedResults } from "../tasks/taskState";
import { TaskStrip } from "../tasks/TaskStrip";
import { taskSessionKey, useOpenTaskSessions } from "../tasks/useTaskSession";
import { useMenuSkills } from "../skills/useSkills";
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
 * 输入区：排队的提问、输入框和状态栏（ask_user 问你的问题在对话里，waiting-state）。最近的运行和排队的提交都从 store 自己取。
 * 规则插件在工具调用前问你时，批准卡片顶替输入框；输入框只是藏起来，草稿还在（input-area 需求 2）。
 * 模型选择器显示这个会话的选择（model-picker）；状态栏的窗口和压缩点按这个会话的模型算（需求 8.3）。
 */
export function ComposerDock({ store, session, config, onOpenPlugins, onAddProject }: Props) {
  const t = useText();
  const sessions = useSessions();
  const projects = useProjects().data ?? [];
  const unarchive = useSetArchived();
  // 选了别的工作区后重新从存储读一次
  const [, setPicked] = useState(0);
  // 还没发第一句的会话里拨的“不进记忆”，按会话记（只在这个页面里）
  const [offDrafts, setOffDrafts] = useState<Record<string, boolean>>({});
  const summary = sessions.data?.find((each) => each.id === session);
  // 服务器知道这个会话了，就是发过第一句：工作区按它的目录算，不能再换（W12）
  const started = summary !== undefined;
  const stored = readPending(session);
  const wanted = stored === null ? readLastProject() : stored === "" ? null : stored;
  const chosen: Project | null = started ? projectOf(summary, projects) : (projects.find((project) => project.id === wanted) ?? null);
  const dir = started ? (summary.dir ?? null) : (chosen?.path ?? null);
  const offRecord = started ? summary.offRecord === true : (offDrafts[session] ?? false);
  const { run, running } = useLatestRun(store, session);
  const version = useRunVersion(store, run);
  const items = useItems(store, useOrder(store, session));
  const own = useMemo(() => pendingApproval(items), [items]);
  // 任务（subagent）：还没回来的、自动继续停住后没处理的结果、任务里等着批准的问题
  const tasksOut = useMemo(() => openTasks(items), [items]);
  const pausedOnes = useMemo(() => pausedResults(items), [items]);
  const taskItems = useOpenTaskSessions(tasksOut);
  const queries = useQueryClient();
  // 这个对话自己的批准在先；没有时轮到任务里的，一次一个
  const fromTask = own === null ? firstApproval(tasksOut, taskItems) : null;
  const pending = own ?? fromTask?.pending ?? null;
  // 排队的消息就是对话里 queued 的用户条目，顺序和内核的队列相同
  const queued = useMemo(() => items.filter((item): item is UserItem => item?.kind === "user" && item.queued), [items]);
  const queueActions = useQueueActions(session);
  const models = useModels();
  // 技能菜单的清单到开始打 / 时才取：没用技能的人不多一次请求
  const menu = useMenuSkills(false);
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
      <PendingStrip session={session} running={running} />
      <QueueDock queued={queued} running={running} actions={queueActions} />
      <TaskStrip session={session} open={tasksOut} paused={running ? [] : pausedOnes} />
      {pending !== null && (
        <ApprovalCard
          key={pending.question.id}
          pending={pending}
          from={fromTask == null ? undefined : t.tasks.from(fromTask.task.task, fromTask.task.brief ?? "")}
          onAnswered={fromTask == null ? undefined : () => void queries.invalidateQueries({ queryKey: taskSessionKey(fromTask.task.taskSession) })}
        />
      )}
      <div hidden={pending !== null}>
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
          queued={queued.length}
          onSteerQueue={() => void queueActions.steerAll(queued)}
          skills={menu.data?.skills}
          onSkillMenu={() => void menu.refetch()}
          onSubmitted={(sent) => {
            choice.submitted(sent);
            if (!started) saveLastProject(chosen?.id ?? null);
          }}
          project={started ? null : (chosen?.id ?? null)}
          offRecord={!started && offRecord}
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
              <OffRecordChip value={offRecord} fixed={started} onChange={(value) => setOffDrafts((all) => ({ ...all, [session]: value }))} />
              <LinkChip dir={dir} onOpen={() => onOpenPlugins?.()} />
            </>
          }
        />
      </div>
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
