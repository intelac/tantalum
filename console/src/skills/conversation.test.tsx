import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import wire from "../../../tantalum-shell/src/test/resources/wire/skills.txt?raw";
import type { Item, ToolStepItem, TracePage, TraceRecord, UserInput, UserItem } from "../api/types";
import { QueueDock } from "../composer/queue/QueueDock";
import { useQueueActions } from "../composer/queue/useQueueActions";
import { UserBubble } from "../conversation/UserBubble";
import { doneKinds, kindOf, runningDetail } from "../process/activity";
import { ToolRow } from "../process/ToolRow";
import { ConversationStore } from "../store/ConversationStore";
import { Inspector } from "../trace/inspector/Inspector";

// 后端 WireFormatTest 的技能场景：提问点名两个技能（一个读到、一个超时），排队的提问带一个。前后端用同一份样本。
const ITEMS = wire
  .slice(wire.indexOf("# items\n") + "# items\n".length, wire.indexOf("# item pushes\n"))
  .trim()
  .split("\n")
  .map((line) => JSON.parse(line) as Item);
const USERS = ITEMS.filter((item): item is UserItem => item.kind === "user");
const PAGE = JSON.parse(wire.slice(wire.indexOf("# trace\n") + "# trace\n".length)) as TracePage;
const byId = (id: string) => PAGE.records.find((record) => record.id === id) as TraceRecord;

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

function bubble(item: UserItem) {
  const store = new ConversationStore();
  store.applyItem(item);
  render(<UserBubble store={store} id={item.id} />);
}

function skillStep(extra: Partial<ToolStepItem> = {}): ToolStepItem {
  return {
    kind: "tool",
    id: "7.0",
    session: "s",
    run: "run-0",
    at: "2026-10-31T00:00:02Z",
    call: { kind: "toolCall", id: "c7", name: "skill", rawArguments: '{"name":"pdf-tools"}', arguments: { name: "pdf-tools" } },
    status: "DONE",
    group: "g2",
    startedAt: "2026-10-31T00:00:02Z",
    endedAt: "2026-10-31T00:00:03Z",
    result: { kind: "toolResult", toolCallId: "c7", text: '<skill_content name="pdf-tools">\nUse pdftotext.\n</skill_content>', error: false },
    ...extra,
  };
}

/** skills-wiki 任务 23（需求 4.6、5.7、5.8、7.5）。 */
describe("named skills under the user's message", () => {
  it("shows one line per skill: one that opens to the full text, one that says why it was not read", async () => {
    const first = USERS[0] as UserItem;
    expect(first.skills?.map((skill) => skill.name)).toEqual(["pdf-tools", "slow"]);
    bubble(first);
    expect(screen.getByText("/pdf-tools and /slow please")).toBeTruthy();
    const used = screen.getByRole("button", { name: "用了技能 pdf-tools" });
    expect(used.getAttribute("aria-expanded")).toBe("false");
    expect(screen.queryByText(/Use pdftotext/)).toBeNull();
    expect(screen.getByRole("status").textContent).toBe("技能 slow 没读到：电脑没有按时回应");

    await userEvent.click(used);
    expect(used.getAttribute("aria-expanded")).toBe("true");
    expect(screen.getByLabelText("技能 pdf-tools 的全文").textContent).toBe('<skill_content name="pdf-tools">\nUse pdftotext.\n</skill_content>');
  });

  /** 技能全文是文件内容：按纯文字显示，里面的标签不变成页面元素。 */
  it("shows the skill text as plain text, never as markup", async () => {
    bubble({ ...(USERS[0] as UserItem), skills: [{ name: "evil", content: '<img src=x onerror="alert(1)"><b>bold</b>' }] });
    await userEvent.click(screen.getByRole("button", { name: "用了技能 evil" }));
    const shown = screen.getByLabelText("技能 evil 的全文");
    expect(shown.textContent).toBe('<img src=x onerror="alert(1)"><b>bold</b>');
    expect(shown.querySelector("img, b")).toBeNull();
  });

  it("explains a skill whose file was broken with the same words as the plugins page", () => {
    bubble({ ...(USERS[0] as UserItem), skills: [{ name: "broken", error: "description-missing" }] });
    expect(screen.getByRole("status").textContent).toBe("技能 broken 没读到：没有写描述");
  });

  it("a message without skills looks as before", () => {
    bubble({ ...(USERS[0] as UserItem), skills: undefined });
    expect(screen.queryByRole("button", { name: /用了技能/ })).toBeNull();
    expect(screen.queryByRole("status")).toBeNull();
  });
});

describe("the skill tool in the process area", () => {
  it("counts as its own kind of activity and names the skill while it loads", () => {
    expect(kindOf("skill")).toBe("skill");
    expect(doneKinds([skillStep(), skillStep()]).kinds).toEqual(["skill"]);
    expect(runningDetail(skillStep({ status: "RUNNING", result: undefined }))).toBe("pdf-tools");
  });

  it("reads 'loaded skill name' and opens to the full text", async () => {
    render(<ToolRow step={skillStep()} />);
    const row = screen.getByRole("button", { name: /加载了技能 pdf-tools/ });
    expect(row.textContent).not.toContain('{"name"');
    await userEvent.click(row);
    expect(screen.getByText(/Use pdftotext\./)).toBeTruthy();
  });

  it("says so when the skill was not loaded", () => {
    render(
      <ToolRow
        step={skillStep({
          status: "FAILED",
          result: { kind: "toolResult", toolCallId: "c7", text: 'Error: skill "pdf-tools" is unknown or no longer available', error: true },
        })}
      />,
    );
    expect(screen.getByRole("button", { name: /没有加载技能 pdf-tools/ })).toBeTruthy();
  });
});

function Dock({ queued }: { queued: UserItem[] }) {
  const actions = useQueueActions("s");
  return <QueueDock queued={queued} running actions={actions} />;
}

describe("a queued message that names a skill", () => {
  it("shows the skill and cannot be edited, but can still be removed or sent as a steer", () => {
    const queued = { ...(USERS[1] as UserItem), queued: true, run: undefined };
    render(<Dock queued={[queued]} />);
    expect(screen.getByText(/· 技能：pdf-tools/)).toBeTruthy();
    const edit = screen.getByRole("button", { name: "编辑" }) as HTMLButtonElement;
    expect(edit.disabled).toBe(true);
    expect(edit.title).toBe("点名了技能的消息不能改文字，可以移除后重新发");
    for (const name of ["移除", "插话：现在就交给正在运行的这一轮"]) {
      expect((screen.getByRole("button", { name }) as HTMLButtonElement).disabled).toBe(false);
    }
  });
});

describe("named skills on the trace page", () => {
  it("the user's record lists each named skill with what was attached or why not", () => {
    const user = PAGE.records.find((record): record is UserInput => record.kind === "user") as UserInput;
    expect(user.skills?.map((skill) => skill.name)).toEqual(["pdf-tools", "slow"]);
    vi.stubGlobal("fetch", vi.fn(() => new Promise(() => undefined)));
    render(
      <QueryClientProvider client={new QueryClient()}>
        <Inspector session="s" record={user} lookup={byId} containerWidth={1280} overlay={false} onClose={vi.fn()} onOpen={vi.fn()} />
      </QueryClientProvider>,
    );
    const section = screen.getByText("点名的技能").closest("section") as HTMLElement;
    expect(section.textContent).toContain("/pdf-tools");
    expect(section.textContent).toContain("Use pdftotext.");
    expect(section.textContent).toContain("技能 slow 没读到：电脑没有按时回应");
  });
});
