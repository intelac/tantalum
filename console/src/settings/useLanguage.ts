// 设置页的“语言”（console-i18n I3、I4）：选了先当场换，再存到服务器；存不上时换回原来的。
// 存好后写回“我是谁”，useCurrentUser 按它更新浏览器里的缓存。

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { me, saveLanguage } from "../api/client";
import type { Language, User } from "../api/types";
import { ME_KEY } from "../auth/useCurrentUser";
import { browserLanguage, currentLanguage, setLanguage } from "../text";
import { cache, clearCache, readCached } from "../text/language";
import { SETTINGS_KEY } from "./useCompaction";

/** 现在存的选择（null 是跟随浏览器）和改它的办法。 */
export function useLanguageChoice() {
  const queries = useQueryClient();
  const user = useQuery({ queryKey: ME_KEY, queryFn: me, staleTime: Number.POSITIVE_INFINITY });
  const save = useMutation({
    mutationFn: saveLanguage,
    // 当场生效，不等服务器，不刷新页面。浏览器里的缓存也一起写：存好之前刷新，第一帧也是刚选的语言（windows-latest 上的
    // Playwright 实测：等服务器回了才写缓存时，选完马上刷新还是原来的语言）
    onMutate: (next: Language | null) => {
      const before = currentLanguage();
      const cachedBefore = readCached();
      setLanguage(next ?? browserLanguage());
      if (next === null) clearCache();
      else cache(next);
      return { before, cachedBefore };
    },
    onError: (_error, _next, context) => {
      if (context === undefined) return;
      setLanguage(context.before);
      if (context.cachedBefore === null) clearCache();
      else cache(context.cachedBefore);
    },
    onSuccess: (_settings, next) => {
      queries.setQueryData<User | null>(ME_KEY, (current) => (current == null ? current : { ...current, language: next }));
      void queries.invalidateQueries({ queryKey: SETTINGS_KEY });
    },
  });
  return { choice: user.data?.language ?? null, save };
}
