import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, cleanup, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import type { Config } from "../api/types";
import { setLanguage } from "../text";
import { EmptyState, welcomeText } from "./EmptyState";

afterEach(() => {
  cleanup();
  act(() => setLanguage("zh"));
});

const BUILT_IN = "输入问题开始。要让模型读写你电脑上的文件，先在插件页连上本机工作区。";

function show(welcome?: Config["welcome"]) {
  const queries = new QueryClient();
  queries.setQueryData(["config"], { welcome });
  return render(
    <QueryClientProvider client={queries}>
      <EmptyState />
    </QueryClientProvider>,
  );
}

describe("EmptyState", () => {
  it("uses the built-in sentence when nothing is configured", () => {
    show();
    expect(screen.getByRole("heading", { name: "有什么可以帮你？" })).not.toBeNull();
    expect(screen.getByText(BUILT_IN)).not.toBeNull();
  });

  it("shows the configured sentence under the fixed title", () => {
    show({ text: "这里是研发部的助手，代码问题直接问。" });
    expect(screen.getByRole("heading", { name: "有什么可以帮你？" })).not.toBeNull();
    expect(screen.getByText("这里是研发部的助手，代码问题直接问。")).not.toBeNull();
    expect(screen.queryByText(BUILT_IN)).toBeNull();
  });

  it("prefers the sentence for the current language and follows a switch", () => {
    show({ text: "默认的一句", byLanguage: { en: "Ask the R&D assistant." } });
    expect(screen.getByText("默认的一句")).not.toBeNull();
    act(() => setLanguage("en"));
    expect(screen.getByText("Ask the R&D assistant.")).not.toBeNull();
  });

  it("shows markup as plain text", () => {
    const { container } = show({ text: '<img src=x onerror="alert(1)"> **粗体** [链接](http://x)' });
    expect(container.querySelector("img")).toBeNull();
    expect(container.querySelector("a")).toBeNull();
    expect(container.querySelector("p")!.textContent).toBe('<img src=x onerror="alert(1)"> **粗体** [链接](http://x)');
  });

  it("falls back when the configured text is blank or not a string", () => {
    expect(welcomeText({ text: "   " }, "zh")).toBeUndefined();
    expect(welcomeText({ text: "默认", byLanguage: { ja: " " } }, "ja")).toBe("默认");
    expect(welcomeText({ text: 42 as unknown as string }, "zh")).toBeUndefined();
    expect(welcomeText(undefined, "zh")).toBeUndefined();
    // 语言名不是自己的属性时不算
    expect(welcomeText({ byLanguage: {} }, "constructor")).toBeUndefined();
  });
});
