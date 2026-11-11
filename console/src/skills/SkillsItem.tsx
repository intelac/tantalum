import { lookup, useText } from "../text";
import { Switch } from "../ui/Switch";
import { DirField } from "./DirField";
import { useLocalSkills, useSetSkillsDir, useToggleSkill } from "./useSkills";

/** pi 和 DSH 都认的用户级技能目录，只当提示：它多半不在连接程序允许的范围里，不当默认值。 */
const HINT = "~/.agents/skills";

/**
 * 插件页“技能”展开的那一块（skills-wiki 需求 8）：技能目录，目录里找到的每个技能（名字、描述、开关），
 * 没通过校验的置灰写明原因。`active` 是“本机工作区”和“技能”都开着；`signal` 变了就重新向电脑要。
 */
export function SkillsItem({ active, signal }: { active: boolean; signal: string }) {
  const text = useText().plugins.local;
  const skills = useLocalSkills(active, signal);
  const save = useSetSkillsDir(signal);
  const toggle = useToggleSkill(signal);
  const view = skills.data;
  return (
    <div className="flex flex-col gap-2 px-3 pb-3 pl-[3.75rem] max-sm:pl-3">
      <DirField
        label={text.dir.skills}
        placeholder={HINT}
        dir={view?.dir}
        state={view?.state}
        path={view?.path}
        disabled={!active}
        saving={save.isPending}
        error={save.error}
        failed={skills.isError}
        onSave={(dir) => save.mutate(dir)}
        onRefresh={() => void skills.refetch()}
        refreshing={skills.isFetching}
      />
      {view?.state === "ok" && (
        <>
          <p role="status" className="text-xs text-fg-muted">
            {view.items.length === 0 ? text.skills.none : text.skills.count(view.items.length)}
          </p>
          {(view.items.length > 0 || view.problems.length > 0) && (
            <ul aria-label={text.switches.skills[0]} className="flex flex-col divide-y divide-line rounded-md border border-line">
              {view.items.map((item) => (
                <li key={item.name} className="flex items-start gap-3 px-3 py-2">
                  <span className="pt-0.5">
                    <Switch
                      checked={item.enabled}
                      label={text.skills.toggle(item.name)}
                      onChange={(enabled) => toggle.mutate({ name: item.name, enabled })}
                    />
                  </span>
                  <div className="min-w-0 flex-1">
                    <div className="flex flex-wrap items-center gap-x-2 gap-y-0.5">
                      <span className="font-mono text-xs text-fg">{item.name}</span>
                      {!item.model && <span className="rounded-xs bg-muted px-1.5 py-0.5 text-[11px] text-fg-muted">{text.skills.manualOnly}</span>}
                      {!item.user && <span className="rounded-xs bg-muted px-1.5 py-0.5 text-[11px] text-fg-muted">{text.skills.modelOnly}</span>}
                    </div>
                    <p className="mt-0.5 text-xs break-words text-fg-muted">{item.description}</p>
                  </div>
                </li>
              ))}
              {view.problems.map((problem) => (
                <li key={`problem:${problem.folder}`} className="px-3 py-2 text-xs text-fg-faint">
                  <span className="font-mono">{problem.folder}</span>
                  <span className="ml-2 text-warning">{lookup(text.skills.problems, problem.code) ?? text.skills.unknownProblem(problem.code)}</span>
                </li>
              ))}
            </ul>
          )}
        </>
      )}
    </div>
  );
}
