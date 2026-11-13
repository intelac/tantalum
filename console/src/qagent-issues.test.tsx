// 对照 QAgent 的问题清单（2026-11-14 Yi 发来）在控制台上复现的问题；编号是清单里的编号，N 开头的是 11-14 实测 8091 时看到的。
import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { groupBlocks, settleReply } from "./conversation/blocks";
import { Markdown } from "./markdown/Markdown";
import { routeReason } from "./settings/routeReason";
import { en } from "./text/en";
import { ja } from "./text/ja";
import { zh } from "./text/zh";

describe("QAgent issues", () => {
  // 59：这轮结束时最后是一个工具调用（被停下、到了上限、钩子拦下）时，前面真正的回答整段被折进过程区
  it("59: when a finished run ends with a tool call, its last text is the reply", () => {
    const shapes: Record<string, { kind: "user" | "run" | "tool" | "text"; run?: string }> = {
      "0.0": { kind: "user", run: "r" },
      "run:r": { kind: "run", run: "r" },
      "1.0": { kind: "tool", run: "r" },
      "1.1": { kind: "text", run: "r" },
      "1.2": { kind: "tool", run: "r" },
    };
    const [, run] = groupBlocks(Object.keys(shapes), (id) => shapes[id]);
    if (run?.kind !== "run") throw new Error("no run block");
    expect(settleReply(run.process, run.reply, (id) => shapes[id]?.kind === "text")).toEqual({ process: ["1.0", "1.2"], reply: ["1.1"] });
    // 结尾已经是文本时不动；没有文本时也不动
    expect(settleReply(["1.0"], ["1.1"], () => true)).toEqual({ process: ["1.0"], reply: ["1.1"] });
    expect(settleReply(["1.0"], [], () => false)).toEqual({ process: ["1.0"], reply: [] });
  });

  // N1：中文里的网址后面紧跟全角标点，整句中文被做进了链接
  it("N1: a URL stops at full-width punctuation", () => {
    render(<Markdown text="我没能打开 https://example.com，所以没法告诉你页面标题。" />);
    expect(screen.getByRole("link").getAttribute("href")).toBe("https://example.com");
    expect(screen.getByRole("link").textContent).toBe("https://example.com");
  });

  // 52：回答里的代码围栏嵌套（四个反引号包着三个反引号）
  it("52: a fence inside a longer fence stays inside", () => {
    const { container } = render(<Markdown text={"````md\n```js\nlet a = 1;\n```\n````\nafter"} />);
    expect(container.querySelectorAll("pre").length).toBe(1);
    expect(container.querySelector("pre")?.textContent).toContain("```js");
    expect(container.textContent).toContain("after");
  });

  // 86：模型列表读不到的原因原来写死成中文，英文、日文页面也显示中文
  it("86: the reason a model list is unavailable follows the page language", () => {
    expect(routeReason(en, "timeout")).toBe("timed out");
    expect(routeReason(ja, "unreachable")).toBe("接続できません");
    expect(routeReason(zh, "not-a-list")).toBe("返回的不是模型列表");
    expect(routeReason(en, "401")).toBe("401");
  });
});
