import { useText } from "../text";
import { DirField } from "./DirField";
import { useLocalWiki, useSetWikiDir } from "./useSkills";

const HINT = "~/wiki";

/**
 * 插件页“知识库”展开的那一块（skills-wiki 需求 8.5）：wiki 目录，找到多少页、有没有 index.md、是不是只列了一部分。
 * 页的内容不在这里看：模型用读文件的工具按需去读。
 */
export function WikiItem({ active, signal }: { active: boolean; signal: string }) {
  const text = useText().plugins.local;
  const wiki = useLocalWiki(active, signal);
  const save = useSetWikiDir(signal);
  const view = wiki.data;
  return (
    <div className="flex flex-col gap-2 px-3 pb-3 pl-[3.75rem] max-sm:pl-3">
      <DirField
        label={text.dir.wiki}
        placeholder={HINT}
        dir={view?.dir}
        state={view?.state}
        path={view?.path}
        disabled={!active}
        saving={save.isPending}
        error={save.error}
        failed={wiki.isError}
        onSave={(dir) => save.mutate(dir)}
        onRefresh={() => void wiki.refetch()}
        refreshing={wiki.isFetching}
      />
      {view?.state === "ok" && (
        <p role="status" className="text-xs text-fg-muted">
          {view.total === 0 && !view.index
            ? text.wiki.none
            : `${view.truncated ? text.wiki.truncated(view.total, view.pages) : text.wiki.pages(view.total)} · ${text.wiki.index(view.index)}`}
        </p>
      )}
    </div>
  );
}
