import { useQuery } from "@tanstack/react-query";
import { ChevronRight, CornerLeftUp, Folder } from "lucide-react";
import { type FormEvent, useEffect, useState } from "react";
import { browseFolders } from "../api/client";
import type { Project } from "../api/types";
import { useText } from "../text";
import { errorLine } from "../text/errors";
import { Button } from "../ui/Button";
import { cx } from "../ui/cx";
import { Dialog } from "../ui/Dialog";
import { folderName, isAbsolute, within } from "./pathKey";
import { useAddProject } from "./useProjects";

interface Props {
  open: boolean;
  onClose: () => void;
  onAdded?: (project: Project) => void;
}

const FIELD = "focus-plain w-full rounded-md border border-line bg-raised px-3 py-1.5 text-sm text-fg focus:border-line-strong";

/**
 * 添加工作区（需求 1.1–1.4）：左边浏览电脑允许的目录，点进子目录，“选这个目录”填进路径；也可以直接输入。
 * 电脑没连上、连接程序太旧时浏览那块说明原因，照样能手动输入。名字不填就用目录名。
 */
export function AddProjectDialog({ open, onClose, onAdded }: Props) {
  const t = useText();
  const [folder, setFolder] = useState<string | undefined>(undefined);
  const [path, setPath] = useState("");
  const [title, setTitle] = useState("");
  const add = useAddProject();
  const listing = useQuery({
    queryKey: ["folders", folder ?? ""],
    queryFn: () => browseFolders(folder),
    enabled: open,
    retry: false,
    staleTime: 10_000,
  });

  useEffect(() => {
    if (!open) return;
    setFolder(undefined);
    setPath("");
    setTitle("");
    add.reset();
    // add 每次渲染都是新对象；只在打开时清一次
  }, [open]);

  const roots = useQuery({ queryKey: ["folders", ""], queryFn: () => browseFolders(), enabled: open, retry: false, staleTime: 10_000 });
  const allowed = roots.data?.dirs ?? [];
  /** 上一级：还在某个允许的目录下面就退一层，到了允许的目录本身就回到列表。 */
  function up(): void {
    if (folder === undefined) return;
    if (allowed.some((root) => within(root, folder) && within(folder, root))) {
      setFolder(undefined);
      return;
    }
    const parent = folder.replace(/[\\/][^\\/]*[\\/]?$/, "");
    setFolder(allowed.some((root) => within(parent, root)) && parent !== "" ? parent : undefined);
  }

  async function save(event: FormEvent): Promise<void> {
    event.preventDefault();
    try {
      const added = await add.mutateAsync({ path: path.trim(), title: title.trim() === "" ? undefined : title.trim() });
      onAdded?.(added);
      onClose();
    } catch {
      // 原因在 add.error 里显示
    }
  }

  const offline = listing.isError;
  return (
    <Dialog open={open} onClose={onClose} title={t.projects.addTitle} className="max-w-xl">
      <form className="flex flex-col gap-4 px-4 pt-2 pb-6 sm:px-6" onSubmit={(event) => void save(event)}>
        <section aria-label={t.projects.allowed} className="flex flex-col gap-1.5">
          <div className="flex items-center gap-2 text-xs text-fg-muted">
            <span className="min-w-0 flex-1 truncate">{folder ?? t.projects.allowed}</span>
            {folder !== undefined && (
              <>
                <Button variant="ghost" size="sm" onClick={up}>
                  <CornerLeftUp size={14} />
                  {t.projects.up}
                </Button>
                <Button variant="secondary" size="sm" onClick={() => setPath(folder)}>
                  {t.projects.choose}
                </Button>
              </>
            )}
          </div>
          <div className="h-52 overflow-y-auto rounded-md border border-line bg-canvas">
            {listing.isPending && <p className="px-3 py-2 text-xs text-fg-faint">{t.common.loading}</p>}
            {offline && (
              <p role="status" className="px-3 py-2 text-xs text-fg-muted">
                {errorLine(listing.error)}
              </p>
            )}
            {listing.data !== undefined && listing.data.dirs.length === 0 && <p className="px-3 py-2 text-xs text-fg-faint">{t.projects.noFolders}</p>}
            <ul>
              {listing.data?.dirs.map((dir) => (
                <li key={dir}>
                  <button
                    type="button"
                    onClick={() => setFolder(dir)}
                    onDoubleClick={() => setPath(dir)}
                    className={cx("flex w-full items-center gap-2 px-3 py-1.5 text-left text-sm hover:bg-hover", path === dir && "bg-active")}
                    title={dir}
                  >
                    <Folder size={14} className="shrink-0 text-fg-faint" aria-hidden="true" />
                    <span className="min-w-0 flex-1 truncate">{folder === undefined ? dir : folderName(dir)}</span>
                    <ChevronRight size={14} className="shrink-0 text-fg-faint" aria-hidden="true" />
                  </button>
                </li>
              ))}
            </ul>
            {listing.data?.truncated === true && <p className="px-3 py-2 text-xs text-fg-faint">{t.projects.truncated}</p>}
          </div>
        </section>
        <label className="flex flex-col gap-1 text-xs text-fg-muted">
          {t.projects.path}
          <input className={FIELD} value={path} onChange={(event) => setPath(event.target.value)} placeholder={t.projects.pathHint} />
        </label>
        <label className="flex flex-col gap-1 text-xs text-fg-muted">
          {t.projects.name}
          <input
            className={FIELD}
            value={title}
            maxLength={80}
            onChange={(event) => setTitle(event.target.value)}
            placeholder={isAbsolute(path) ? folderName(path) : t.projects.namePlaceholder}
          />
        </label>
        {offline && isAbsolute(path) && <p className="text-xs text-fg-faint">{t.projects.unchecked}</p>}
        {add.error !== null && (
          <p role="alert" className="text-xs text-danger">
            {errorLine(add.error)}
          </p>
        )}
        <div className="flex justify-end gap-2">
          <Button variant="ghost" onClick={onClose}>
            {t.common.cancel}
          </Button>
          <Button variant="primary" type="submit" disabled={!isAbsolute(path) || add.isPending}>
            {t.projects.add}
          </Button>
        </div>
      </form>
    </Dialog>
  );
}
