import { afterEach, describe, expect, it, vi } from "vitest";
import { THEME_KEY, applyTheme, readTheme, resolveTheme, saveTheme, setTheme } from "./theme";

describe("theme", () => {
  afterEach(() => {
    localStorage.clear();
    vi.unstubAllGlobals();
  });

  it("falls back to system when nothing or garbage is stored", () => {
    expect(readTheme()).toBe("system");
    localStorage.setItem(THEME_KEY, "purple");
    expect(readTheme()).toBe("system");
  });

  it("keeps the choice across reloads", () => {
    saveTheme("dark");
    expect(localStorage.getItem(THEME_KEY)).toBe("dark");
    expect(readTheme()).toBe("dark");
  });

  it("resolves system by the OS preference", () => {
    expect(resolveTheme("system", true)).toBe("dark");
    expect(resolveTheme("system", false)).toBe("light");
    expect(resolveTheme("light", true)).toBe("light");
  });

  it("writes the resolved theme on the root element", () => {
    vi.stubGlobal("matchMedia", () => ({ matches: true, addEventListener: () => undefined, removeEventListener: () => undefined }));
    const root = document.createElement("html");
    expect(applyTheme("system", root)).toBe("dark");
    expect(root.dataset.theme).toBe("dark");
    expect(applyTheme("light", root)).toBe("light");
    expect(root.dataset.theme).toBe("light");
  });

  it("setTheme saves and applies at once", () => {
    setTheme("dark");
    expect(document.documentElement.dataset.theme).toBe("dark");
    expect(readTheme()).toBe("dark");
  });
});
