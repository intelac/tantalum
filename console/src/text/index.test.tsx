import { act, render, screen } from "@testing-library/react";
import { useEffect } from "react";
import { describe, expect, it, vi } from "vitest";
import { Sidebar } from "../shell/Sidebar";
import { currentLanguage, currentText, registerDictionary, setLanguage, useLanguage, type Text } from "./index";

/** 一份假字典：每条文字前面加 `[x]`，带参数的照样调原来的再加。结构和 zh 完全相同。 */
function marked(value: unknown): unknown {
  if (typeof value === "string") return `[x]${value}`;
  if (typeof value === "function") return (...args: unknown[]) => `[x]${(value as (...args: unknown[]) => string)(...args)}`;
  if (Array.isArray(value)) return value.map(marked);
  if (value !== null && typeof value === "object") return Object.fromEntries(Object.entries(value).map(([key, item]) => [key, marked(item)]));
  return value;
}

describe("current language", () => {
  it("setLanguage 写 <html lang> 并通知订阅者", () => {
    const seen: string[] = [];
    function Probe() {
      const language = useLanguage();
      useEffect(() => {
        seen.push(language);
      }, [language]);
      return null;
    }
    render(<Probe />);
    act(() => setLanguage("ja"));
    expect(currentLanguage()).toBe("ja");
    expect(document.documentElement.lang).toBe("ja");
    act(() => setLanguage("zh"));
    expect(document.documentElement.lang).toBe("zh-CN");
    expect(seen).toEqual(["zh", "ja", "zh"]);
  });

  // I4：切换语言当场生效，组件不重新挂载（状态都在）。这个文件里 en 被换成了假字典，vitest 每个文件单独一份模块，不影响别的测试。
  it("切换后侧栏的文字跟着变，组件没有重新挂载", () => {
    registerDictionary("en", marked(currentText()) as Text);
    const mounts = vi.fn();
    function Counted() {
      useEffect(() => mounts(), []);
      return (
        <Sidebar
          sessions={[]}
          loading={false}
          error={null}
          currentSession={null}
          rail={false}
          onToggle={() => undefined}
          onNewSession={() => undefined}
          onSelectSession={() => undefined}
          onSettings={() => undefined}
          onPlugins={() => undefined}
          onLogout={() => undefined}
          username="yi"
        />
      );
    }
    render(<Counted />);
    expect(screen.getByRole("button", { name: "新会话" })).toBeTruthy();
    act(() => setLanguage("en"));
    expect(screen.getByRole("button", { name: "[x]新会话" })).toBeTruthy();
    expect(screen.queryByRole("button", { name: "新会话" })).toBeNull();
    expect(mounts).toHaveBeenCalledTimes(1);
  });
});
