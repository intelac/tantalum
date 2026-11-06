// 选模型的数据层（model-picker 任务 13）：模型列表一分钟内不重读；每个会话的选择按“没发出的 → 刚发出的 → 会话现在的 →
// 上次的选择 → 管理员的默认”取（设计“useSessionChoice”）。选了就存成这个用户上次的选择（M7），并留作这个会话没发出的选择，
// 随下一次开始新运行的提交发出去，发出后清掉（M6、M8）。

import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useCallback, useEffect, useState } from "react";
import { getModels, setModelChoice } from "../api/client";
import type { ModelChoice, ModelInfo, ModelsResponse, RouteView } from "../api/types";
import { clearChoice, readChoice, saveChoice } from "./drafts";

export const MODELS_KEY = ["models"] as const;

/** `/api/models`：服务器从内存返回，这里一分钟内不重读，也不轮询。 */
export function useModels() {
  return useQuery({ queryKey: MODELS_KEY, queryFn: getModels, staleTime: 60_000 });
}

export function sameChoice(a: ModelChoice | null | undefined, b: ModelChoice | null | undefined): boolean {
  if (a == null || b == null) return a == b;
  return a.route === b.route && a.model === b.model && (a.effort ?? null) === (b.effort ?? null);
}

export function findRoute(models: ModelsResponse | undefined, route: string): RouteView | undefined {
  return models?.routes.find((each) => each.name === route);
}

export function findModel(models: ModelsResponse | undefined, choice: ModelChoice | null | undefined): ModelInfo | undefined {
  if (choice == null) return undefined;
  return findRoute(models, choice.route)?.models.find((model) => model.id === choice.model);
}

/** 换到 {@code next} 时的档位（M5）：新模型也有当前档位就留着，没有就用它的默认；没有档位的模型为 null。 */
export function levelFor(next: ModelInfo, current: string | null | undefined): string | null {
  if (next.levels.length === 0) return null;
  return current != null && next.levels.includes(current) ? current : next.defaultLevel;
}

/** 四级取值，前面的有就用前面的。 */
export function pickChoice(
  draft: ModelChoice | null,
  current: ModelChoice | null | undefined,
  models: ModelsResponse | undefined,
): ModelChoice | null {
  return draft ?? current ?? models?.mine ?? models?.default ?? null;
}

/** 这一页里刚发出去的选择：服务器的会话视图还没更新时，按它算“已经生效的”。刷新页面就忘掉，那时会话视图已经是新的。 */
const sent = new Map<string, ModelChoice>();

export function sentChoice(session: string): ModelChoice | undefined {
  return sent.get(session);
}

/**
 * 这个会话的选择。{@code current} 是会话视图里的当前选择（本 spec 之前的会话为 null）。{@code pending} 为真时按钮旁写
 * “下一次提问生效”：选的和已经生效的不一样。
 */
export function useSessionChoice(session: string, current: ModelChoice | null | undefined, models: ModelsResponse | undefined) {
  const queries = useQueryClient();
  const [draft, setDraft] = useState<ModelChoice | null>(() => readChoice(session));
  useEffect(() => setDraft(readChoice(session)), [session]);
  const applied = sent.get(session) ?? current ?? null;
  const shown = pickChoice(draft, applied, models);
  const select = useCallback(
    (choice: ModelChoice) => {
      saveChoice(session, choice);
      setDraft(choice);
      queries.setQueryData<ModelsResponse>(MODELS_KEY, (before) => (before === undefined ? before : { ...before, mine: choice }));
      // 存“上次的选择”失败只影响新会话的默认，不挡这次提问
      setModelChoice(choice).catch(() => undefined);
    },
    [queries, session],
  );
  /** 提交成功：发出去的就是现在生效的，清掉没发出的。 */
  const submitted = useCallback(
    (choice: ModelChoice | null) => {
      if (choice !== null) sent.set(session, choice);
      clearChoice(session);
      setDraft(null);
    },
    [session],
  );
  return { shown, applied, pending: draft !== null && applied !== null && !sameChoice(draft, applied), select, submitted };
}
