import { useQuery } from "@tanstack/react-query";
import { getConfig } from "../api/client";

/** 供应商、模型名、是否云端、上下文窗口；登录后读一次。 */
export function useConfig() {
  return useQuery({ queryKey: ["config"], queryFn: getConfig, staleTime: Number.POSITIVE_INFINITY });
}
