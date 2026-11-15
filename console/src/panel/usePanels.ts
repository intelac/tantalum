// 插件面板的数据和状态（plugin-panel 需求 2）：能用的面板来自服务器；每个会话开着哪个、面板多宽、放没放大记在浏览器里。

import { useQuery } from "@tanstack/react-query";
import { useCallback, useState } from "react";
import { listPanels } from "../api/client";
import type { PanelInfo } from "../api/types";
import { localArea, readStored, removeStored, writeStored } from "../ui/storage";

export const PANELS_KEY = ["panels"] as const;
export const MIN_WIDTH = 320;
export const DEFAULT_WIDTH = 420;
const WIDTH_KEY = "tantalum.panel.width";

const openKey = (session: string) => `tantalum.panel.${session}`;

export function usePanels() {
  return useQuery({ queryKey: PANELS_KEY, queryFn: listPanels, select: (data) => data.panels });
}

/** 这个会话开着的面板名；面板不在能用的列表里（插件关了）时当作没开。 */
export function useOpenPanel(session: string, available: readonly PanelInfo[] | undefined) {
  const [stored, setStored] = useState<{ session: string; name: string | null }>(() => ({
    session,
    name: readStored(openKey(session), localArea()),
  }));
  const name = stored.session === session ? stored.name : readStored(openKey(session), localArea());
  const open = useCallback(
    (next: string | null) => {
      if (next === null) removeStored(openKey(session), localArea());
      else writeStored(openKey(session), next, localArea());
      setStored({ session, name: next });
    },
    [session],
  );
  const panel = name === null ? undefined : available?.find((item) => item.name === name);
  return { panel, name, open };
}

export function readWidth(): number {
  const stored = Number(readStored(WIDTH_KEY, localArea()));
  return Number.isFinite(stored) && stored >= MIN_WIDTH ? stored : DEFAULT_WIDTH;
}

export function saveWidth(width: number): void {
  writeStored(WIDTH_KEY, String(Math.round(width)), localArea());
}

/** 宽度夹在 320 和主区域的 70% 之间。 */
export function clampWidth(width: number, container: number): number {
  return Math.round(Math.max(MIN_WIDTH, Math.min(width, Math.max(MIN_WIDTH, container * 0.7))));
}
