// 插件页“技能”“知识库”两项和输入框技能菜单的数据。清单当场向电脑要：打开时取一次，电脑连上或断开、开关变了、点“刷新”时再取。

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { getLocalSkills, getLocalWiki, getMenuSkills, setLocalSkill, setSkillsDir, setWikiDir } from "../api/client";
import type { LocalSkills } from "../api/types";

export const SKILLS_KEY = ["local-skills"] as const;
export const WIKI_KEY = ["local-wiki"] as const;
export const MENU_KEY = ["skill-menu"] as const;

/** `signal` 变了就重新取：电脑连没连着、开关开没开。 */
export function useLocalSkills(enabled: boolean, signal: string) {
  return useQuery({ queryKey: [...SKILLS_KEY, signal], queryFn: getLocalSkills, enabled });
}

export function useLocalWiki(enabled: boolean, signal: string) {
  return useQuery({ queryKey: [...WIKI_KEY, signal], queryFn: getLocalWiki, enabled });
}

/** 保存目录：回来的就是新清单，直接放进缓存。 */
export function useSetSkillsDir(signal: string) {
  const queries = useQueryClient();
  return useMutation({
    mutationFn: setSkillsDir,
    onSuccess: (view) => {
      queries.setQueryData([...SKILLS_KEY, signal], view);
      void queries.invalidateQueries({ queryKey: MENU_KEY });
    },
  });
}

export function useSetWikiDir(signal: string) {
  const queries = useQueryClient();
  return useMutation({ mutationFn: setWikiDir, onSuccess: (view) => queries.setQueryData([...WIKI_KEY, signal], view) });
}

/** 开关一个技能：先在界面上改，失败时退回。 */
export function useToggleSkill(signal: string) {
  const queries = useQueryClient();
  const key = [...SKILLS_KEY, signal];
  return useMutation({
    mutationFn: ({ name, enabled }: { name: string; enabled: boolean }) => setLocalSkill(name, enabled),
    onMutate: async ({ name, enabled }) => {
      await queries.cancelQueries({ queryKey: key });
      const before = queries.getQueryData<LocalSkills>(key);
      if (before !== undefined) {
        queries.setQueryData(key, { ...before, items: before.items.map((item) => (item.name === name ? { ...item, enabled } : item)) });
      }
      return { before };
    },
    onError: (_error, _toggle, context) => {
      if (context?.before !== undefined) queries.setQueryData(key, context.before);
    },
    onSettled: () => queries.invalidateQueries({ queryKey: MENU_KEY }),
  });
}

/** 输入框的菜单：输入框在的时候取，之后每次打开菜单再取一次（见 SkillMenu）。 */
export function useMenuSkills(enabled: boolean) {
  return useQuery({ queryKey: MENU_KEY, queryFn: getMenuSkills, enabled, staleTime: 5_000 });
}
