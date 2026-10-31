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

  useEffect(() => {
    onUnauthorized(() => setUser(null));
    return () => onUnauthorized(() => undefined);
  }, [setUser]);

  const logout = useCallback(async () => {
    try {
      await logoutRequest();
    } finally {
      setUser(null);
    }
  }, [setUser]);

  return { user: query.data ?? null, loading: query.isPending, setUser, logout };
}
