import { ChevronDown, ChevronUp, Circle, CircleCheck, CircleDot, ListTodo } from "lucide-react";
import { useCallback, useMemo, useState } from "react";
import type { ConversationStore } from "../store/ConversationStore";
import { useItems, useLatestRun, useOrder } from "../store/hooks";
import { useText } from "../text";
import { cx } from "../ui/cx";
import { localArea, readStored, writeStored } from "../ui/storage";
import { currentTodos, summaryOf, type TodoItem, type TodoStatus } from "./todos";

const COLLAPSED = "tantalum.plan.collapsed";

export function TodoIcon({ status }: { status: TodoStatus }) {
  switch (status) {
    case "completed":
      return <CircleCheck size={14} className="shrink-0 text-success" aria-hidden="true" />;
    case "in_progress":
      return <CircleDot size={14} className="shrink-0 text-accent" aria-hidden="true" />;
    case "pending":
      return <Circle size={14} className="shrink-0 text-fg-faint" aria-hidden="true" />;
  }
}

/** 一张清单，每项一行：完成的变灰划线，正在做的加粗。工具行展开后也用它。 */
export function TodoLines({ todos }: { todos: readonly TodoItem[] }) {
  const t = useText();
  const label = { completed: t.plan.completed, in_progress: t.plan.inProgress, pending: t.plan.pending } as const;
  return (
    <ul className="flex flex-col gap-1">
      {todos.map((todo) => (
        <li key={todo.content} className="flex min-w-0 items-start gap-2">
          <span className="mt-0.5" title={label[todo.status]}>
            <TodoIcon status={todo.status} />
          </span>
          <span className="sr-only">{label[todo.status]}: </span>
          <span
            className={cx(
              "min-w-0 break-words",
              todo.status === "completed" && "text-fg-faint line-through",
              todo.status === "in_progress" && "font-medium text-fg",
              todo.status === "pending" && "text-fg-muted",
            )}
          >
            {todo.content}
          </span>
        </li>
      ))}
    </ul>
  );
}

/** “2/5 已完成 · 正在：某某”。 */
export function useTodoSummary(todos: readonly TodoItem[]): string {
  return summaryOf(todos, useText().plan);
}

function useCollapsed(): [boolean, () => void] {
  const [collapsed, setCollapsed] = useState(() => readStored(COLLAPSED, localArea()) === "1");
  const toggle = useCallback(() => {
    setCollapsed((current) => {
      writeStored(COLLAPSED, current ? "0" : "1", localArea());
      return !current;
    });
  }, []);
  return [collapsed, toggle];
}

/** 计划横条本身：标题和进度一行，展开是整张清单。收起与否每个浏览器记一份。 */
export function PlanBar({ todos }: { todos: readonly TodoItem[] }) {
  const t = useText();
  const [collapsed, toggle] = useCollapsed();
  const summary = useTodoSummary(todos);
  return (
    <section aria-label={t.plan.title} className="mb-2 rounded-xl border border-line bg-raised px-3 py-2 text-sm">
      <button
        type="button"
        aria-expanded={!collapsed}
        aria-label={collapsed ? t.plan.expand : t.plan.collapse}
        onClick={toggle}
        className="flex w-full min-w-0 items-center gap-2 text-left text-fg-muted"
      >
        <ListTodo size={14} className="shrink-0 text-fg-faint" aria-hidden="true" />
        <span className="shrink-0 font-medium text-fg">{t.plan.title}</span>
        <span className="min-w-0 flex-1 truncate text-xs">{summary}</span>
        {collapsed ? <ChevronUp size={14} className="shrink-0" aria-hidden="true" /> : <ChevronDown size={14} className="shrink-0" aria-hidden="true" />}
      </button>
      {!collapsed && (
        <div className="mt-2 max-h-48 overflow-y-auto pl-1">
          <TodoLines todos={todos} />
        </div>
      )}
    </section>
  );
}

/** 这个会话的计划横条：最近一轮最后一次成功写下的清单；没有就不占地方。 */
export function SessionPlan({ store, session }: { store: ConversationStore; session: string }) {
  const { run } = useLatestRun(store, session);
  const items = useItems(store, useOrder(store, session));
  const todos = useMemo(() => currentTodos(items, run), [items, run]);
  if (todos === null) return null;
  return (
    <div className="mx-auto w-full max-w-[var(--content-width)] shrink-0 px-4">
      <PlanBar todos={todos} />
    </div>
  );
}
