import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { CodeBlock } from "./CodeBlock";
import { highlight } from "./highlight";
import { Markdown, safeUrl } from "./Markdown";

afterEach(cleanup);

describe("Markdown", () => {
  it("renders headings, lists, tables and inline code", () => {
    const { container } = render(<Markdown text={"# 标题\n\n- 一\n- 二\n\n| a | b |\n|---|---|\n| 1 | 2 |\n\n用 `ls -la` 列出"} />);
    expect(screen.getByRole("heading", { level: 1 }).textContent).toBe("标题");
    expect(container.querySelectorAll("li")).toHaveLength(2);
    expect(container.querySelector("table")).not.toBeNull();
    expect(container.querySelector("code")?.textContent).toBe("ls -la");
  });

  it("never renders raw HTML from the model", () => {
    const { container } = render(<Markdown text={'<img src=x onerror="alert(1)"><script>alert(1)</script>**bold**'} />);
    expect(container.querySelector("img")).toBeNull();
    expect(container.querySelector("script")).toBeNull();
    expect(container.querySelector("strong")?.textContent).toBe("bold");
  });

  it("drops javascript: and data: links but keeps https links in a new tab", () => {
    const { container } = render(
      <Markdown text={"[bad](javascript:alert(1)) [data](data:text/html,hi) [ok](https://example.com) [rel](/docs)"} />,
    );
    const links = Array.from(container.querySelectorAll("a"));
    expect(links.map((link) => link.getAttribute("href"))).toEqual(["https://example.com", "/docs"]);
    expect(links[0]?.getAttribute("target")).toBe("_blank");
    expect(links[0]?.getAttribute("rel")).toBe("noopener noreferrer");
    expect(screen.getByText("bad").tagName).toBe("SPAN");
    expect(safeUrl("JavaScript:alert(1)")).toBe("");
    expect(safeUrl("mailto:a@b.c")).toBe("mailto:a@b.c");
  });

  it("turns fenced code into a CodeBlock with language label", () => {
    render(<Markdown text={"```python\nprint('hi')\n```"} />);
    expect(screen.getByText("python")).not.toBeNull();
    expect(screen.getByRole("button", { name: "复制" })).not.toBeNull();
  });
});

describe("CodeBlock", () => {
  it("highlights known languages and escapes unknown ones", () => {
    expect(highlight("const a = 1", "ts")).toContain("hljs-keyword");
    expect(highlight("<b>", "nosuchlang")).toBe("&lt;b&gt;");
    expect(highlight('x = "<"', "python")).not.toContain("<\"");
    // 原型上的名字不是语言。
    expect(highlight("<b>", "constructor")).toBe("&lt;b&gt;");
    expect(highlight("<b>", "__proto__")).toBe("&lt;b&gt;");
    expect(highlight("<b>", "toString")).toBe("&lt;b&gt;");
  });

  it("copies the code and shows feedback", async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    vi.stubGlobal("navigator", { ...navigator, clipboard: { writeText } });
    render(<CodeBlock code={"echo hi\n"} language="sh" />);
    expect(screen.getByText("bash")).not.toBeNull();
    await userEvent.click(screen.getByRole("button", { name: "复制" }));
    expect(writeText).toHaveBeenCalledWith("echo hi");
    expect(await screen.findByRole("button", { name: "已复制" })).not.toBeNull();
    vi.unstubAllGlobals();
  });
});
