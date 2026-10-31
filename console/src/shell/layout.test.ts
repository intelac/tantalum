import { afterEach, describe, expect, it } from "vitest";
import { clampWidth, modeFor, readCollapsed, readWidth, saveCollapsed, saveWidth } from "./layout";

afterEach(() => localStorage.clear());

describe("layout", () => {
  it("keeps the sidebar width between 264 and 420 with 280 as default", () => {
    expect(clampWidth(100)).toBe(264);
    expect(clampWidth(999)).toBe(420);
    expect(clampWidth(300.4)).toBe(300);
    expect(clampWidth(Number.NaN)).toBe(280);
    expect(readWidth()).toBe(280);
  });

  it("remembers width and collapsed state", () => {
    saveWidth(350);
    expect(readWidth()).toBe(350);
    saveWidth(10);
    expect(readWidth()).toBe(264);
    expect(readCollapsed()).toBe(false);
    saveCollapsed(true);
    expect(readCollapsed()).toBe(true);
  });

  it("maps viewport widths to the three modes", () => {
    expect(modeFor(1280)).toBe("wide");
    expect(modeFor(1024)).toBe("wide");
    expect(modeFor(1023)).toBe("medium");
    expect(modeFor(768)).toBe("medium");
    expect(modeFor(767)).toBe("narrow");
    expect(modeFor(390)).toBe("narrow");
  });
});
