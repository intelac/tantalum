import type { ToolStepItem } from "../../api/types";
import { TodoLines, useTodoSummary } from "../../conversation/PlanBar";
import { parseTodos, TODO_TOOL, type TodoItem } from "../../conversation/todos";

/** 成功写下的清单才画成清单；还在执行、出错、被拒、参数不像清单的照普通方式显示。 */
export function todoList(step: ToolStepItem): TodoItem[] | null {
  if (step.call.name !== TODO_TOOL || step.result === undefined || step.result.error || step.result.code !== undefined) return null;
  return parseTodos(step.call.arguments);
}

export function TodoSummary({ todos }: { todos: readonly TodoItem[] }) {
  return <span className="font-sans">{useTodoSummary(todos)}</span>;
}

export function TodoCard({ todos }: { todos: readonly TodoItem[] }) {
  return (
    <section className="rounded-sm border border-line bg-code p-2 text-xs">
      <TodoLines todos={todos} />
    </section>
  );
}
