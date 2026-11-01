// 当前用户只问后端一次；任何接口返回 401 时把它清空，界面随之回到登录页。

import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useCallback, useEffect } from "react";
import { logout as logoutRequest, me, onUnauthorized } from "../api/client";
import type { User } from "../api/types";

export const ME_KEY = ["me"] as const;

export interface CurrentUser {
  user: User | null;
  loading: boolean;
  setUser: (user: User | null) => void;
  logout: () => Promise<void>;
}

export function useCurrentUser(): CurrentUser {
  const queries = useQueryClient();
  const query = useQuery({ queryKey: ME_KEY, queryFn: me, staleTime: Number.POSITIVE_INFINITY });

  const setUser = useCallback((user: User | null) => queries.setQueryData(ME_KEY, user), [queries]);

  // 退出或登录过期：除“我是谁”之外的缓存（会话列表、配置、轨迹）全部丢掉，不留给同一标签页里下一个登录的人。
  const forget = useCallback(() => {
    queries.removeQueries({ predicate: (query) => query.queryKey[0] !== ME_KEY[0] });
    setUser(null);
  }, [queries, setUser]);

  useEffect(() => {
    onUnauthorized(forget);
    return () => onUnauthorized(() => undefined);
  }, [forget]);

  const logout = useCallback(async () => {
    try {
      await logoutRequest();
    } finally {
      forget();
    }
  }, [forget]);

  return { user: query.data ?? null, loading: query.isPending, setUser, logout };
}
