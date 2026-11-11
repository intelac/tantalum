import { cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { MenuSkill } from "../api/types";
import { Composer } from "../composer/Composer";

vi.mock("../api/client", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../api/client")>();
  return { ...actual, submit: vi.fn(), cancelRun: vi.fn() };
});

const client = await import("../api/client");
const submit = vi.mocked(client.submit);

const SKILLS: MenuSkill[] = [
  { name: "by-hand", description: "Only when asked.", manualOnly: true },
  { name: "deploy", description: "Ship the service.", manualOnly: false },
  { name: "pdf-tools", description: "Convert PDF files to text.", manualOnly: false },
];

function mount(skills: MenuSkill[] = SKILLS, onSkillMenu = vi.fn()) {
  render(<Composer session="s1" activeRun={null} modelName="qwen" cloud={false} skills={skills} onSkillMenu={onSkillMenu} />);
  return { box: screen.getByRole("textbox") as HTMLTextAreaElement, onSkillMenu };
}

const options = () => within(screen.getByRole("listbox", { name: "技能" })).getAllByRole("option");
const selected = () => options().find((option) => option.getAttribute("aria-selected") === "true")?.textContent;

beforeEach(() => {
  submit.mockReset();
  submit.mockResolvedValue({ submissionId: "x", duplicate: false });
});

afterEach(() => {
  cleanup();
  sessionStorage.clear();
});

/** skills-wiki 任务 22（需求 5.1、5.5、5.9）：输入框的 `/` 技能菜单。 */
describe("the skill menu in the composer", () => {
  it("opens on a slash at the start, lists names and descriptions, and marks manual-only skills", async () => {
    const { box, onSkillMenu } = mount();
    expect(screen.queryByRole("listbox")).toBeNull();
    await userEvent.type(box, "/");
    expect(options().map((option) => option.textContent)).toEqual([
      "/by-hand只能手动使用Only when asked.",
      "/deployShip the service.",
      "/pdf-toolsConvert PDF files to text.",
    ]);
    expect(selected()).toContain("/by-hand");
    expect(onSkillMenu).toHaveBeenCalledTimes(1);
    expect(box.getAttribute("aria-activedescendant")).toBe(options()[0]!.id);
  });

  it("filters as you type and closes when nothing matches", async () => {
    const { box } = mount();
    await userEvent.type(box, "please /pd");
    expect(options().map((option) => option.textContent)).toEqual(["/pdf-toolsConvert PDF files to text."]);
    await userEvent.type(box, "x");
    expect(screen.queryByRole("listbox")).toBeNull();
  });

  it("moves with the arrow keys and fills in the name on Enter without sending", async () => {
    const { box } = mount();
    await userEvent.type(box, "/");
    await userEvent.keyboard("{ArrowDown}{ArrowDown}");
    expect(selected()).toContain("/pdf-tools");
    await userEvent.keyboard("{ArrowDown}");
    expect(selected()).toContain("/by-hand");
    await userEvent.keyboard("{ArrowUp}");
    expect(selected()).toContain("/pdf-tools");
    await userEvent.keyboard("{Enter}");
    expect(box.value).toBe("/pdf-tools ");
    expect(submit).not.toHaveBeenCalled();
    expect(screen.queryByRole("listbox")).toBeNull();

    // 菜单关了，回车照常发送，文字原样
    await userEvent.type(box, "convert it{Enter}");
    expect(submit).toHaveBeenCalledTimes(1);
    expect(submit.mock.calls[0]![2]).toBe("/pdf-tools convert it");
  });

  it("fills in the name on Tab and on a click", async () => {
    const { box } = mount();
    await userEvent.type(box, "use /de");
    await userEvent.keyboard("{Tab}");
    expect(box.value).toBe("use /deploy ");
    await userEvent.type(box, "and /");
    await userEvent.click(options()[2]!);
    expect(box.value).toBe("use /deploy and /pdf-tools ");
    expect(submit).not.toHaveBeenCalled();
  });

  it("closes on Escape and stays closed for that slash", async () => {
    const { box } = mount();
    await userEvent.type(box, "/d");
    await userEvent.keyboard("{Escape}");
    expect(screen.queryByRole("listbox")).toBeNull();
    await userEvent.type(box, "e");
    expect(screen.queryByRole("listbox")).toBeNull();
    expect(box.value).toBe("/de");
    // 换个地方再打 / 又弹出来
    await userEvent.type(box, " /");
    expect(options()).toHaveLength(3);
  });

  it("does not open inside a path or a URL, or while the input method is composing", async () => {
    const { box } = mount();
    await userEvent.type(box, "read src/");
    expect(screen.queryByRole("listbox")).toBeNull();
    await userEvent.clear(box);
    fireEvent.compositionStart(box);
    fireEvent.change(box, { target: { value: "/", selectionStart: 1 } });
    expect(screen.queryByRole("listbox")).toBeNull();
    fireEvent.compositionEnd(box);
    expect(options()).toHaveLength(3);
  });

  it("has no menu when there are no skills, and Enter sends as usual", async () => {
    const { box } = mount([]);
    await userEvent.type(box, "/tmp is full{Enter}");
    expect(screen.queryByRole("listbox")).toBeNull();
    expect(submit).toHaveBeenCalledTimes(1);
  });
});
