import { describe, expect, it } from "vitest";
import type { Item, QuestionItem, TaskItem, TaskResultItem, ToolStepItem, UserItem } from "../api/types";
import { firstApproval, openTasks, pausedResults, recentSteps, waitingForUser } from "./taskState";

const at = "2026-11-18T01:00:00Z";

function task(id: string, outcome?: TaskItem["outcome"]): TaskItem {
  return { kind: "task", id: `card-${id}`, session: "s1", run: "s1_1", at, task: id, taskSession: `s1-${id}`, brief: `check ${id}`, outcome };
}

function result(id: string, paused: boolean): TaskResultItem {
  return { kind: "taskResult", id: `result-${id}`, session: "s1", run: "s1_1", at, task: id, outcome: "COMPLETED", text: "ok", paused };
}

function step(id: string, session: string): ToolStepItem {
  return { kind: "tool", id, session, run: `${session}_1`, at, call: { id: `call-${id}`, name: "write_file", arguments: {} }, status: "WAITING" } as ToolStepItem;
}

function question(id: string, session: string, status: QuestionItem["status"], toolCallId?: string): QuestionItem {
  return {
    kind: "question", id, session, run: `${session}_1`, at, askedBy: "toolApproval", questionId: id, title: "Allow?", detail: "",
    choices: [{ id: "once", label: "Once" }], freeText: false, status, toolCallId,
  };
}

const user: UserItem = { kind: "user", id: "u1", session: "s1", at, text: "go on", queued: false } as UserItem;

describe("任务在对话页上的几个计算", () => {
  it("还没回来的任务按放出的先后，回来了的不算", () => {
    expect(openTasks([task("t1-0", "COMPLETED"), undefined, task("t1-1"), task("t1-2")]).map((each) => each.task)).toEqual(["t1-1", "t1-2"]);
  });

  it("自动继续停住后没处理的结果：只数结尾连续的那几个，人说了话就不再提示", () => {
    const items: Item[] = [result("t1-0", false), result("t1-1", true), result("t1-2", true)];
    expect(pausedResults(items).map((each) => each.task)).toEqual(["t1-1", "t1-2"]);
    expect(pausedResults([...items, user])).toEqual([]);
    expect(pausedResults([result("t1-0", false)])).toEqual([]);
    expect(pausedResults([])).toEqual([]);
  });

  it("卡片展开时只取任务最近的几步工具调用", () => {
    const items: Item[] = [step("a", "s1-t1-0"), question("q", "s1-t1-0", "ANSWERED"), step("b", "s1-t1-0"), step("c", "s1-t1-0")];
    expect(recentSteps(items, 2).map((each) => each.id)).toEqual(["b", "c"]);
  });

  it("任务会话里有没答的问题，就是在等你", () => {
    expect(waitingForUser([question("q", "s1-t1-0", "OPEN")])).toBe(true);
    expect(waitingForUser([question("q", "s1-t1-0", "ANSWERED"), step("a", "s1-t1-0")])).toBe(false);
  });

  it("几个任务同时要批准：按放出的先后一次给一个，带着是哪个任务", () => {
    const tasks = [task("t1-0"), task("t1-1"), task("t1-2")];
    const sessions: Record<string, Item[]> = {
      // 第一个任务问的是 ask_user（不带调用），不算批准
      "s1-t1-0": [question("q0", "s1-t1-0", "OPEN")],
      "s1-t1-1": [step("w1", "s1-t1-1"), question("q1", "s1-t1-1", "OPEN", "call-w1")],
      "s1-t1-2": [step("w2", "s1-t1-2"), question("q2", "s1-t1-2", "OPEN", "call-w2")],
    };
    const found = firstApproval(tasks, (session) => sessions[session]);
    expect(found?.task.task).toBe("t1-1");
    expect(found?.pending.question.id).toBe("q1");
    expect(found?.pending.step?.id).toBe("w1");
    // 还没读到任务的会话时不算
    expect(firstApproval(tasks, () => undefined)).toBeNull();
    expect(firstApproval([], (session) => sessions[session])).toBeNull();
  });
});
